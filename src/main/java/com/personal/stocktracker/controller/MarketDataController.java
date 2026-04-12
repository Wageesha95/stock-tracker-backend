package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.repository.MarketDataRepository;
import com.personal.stocktracker.service.MarketDataScraperService;
import com.personal.stocktracker.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private static final ZoneId TZ = ZoneId.of("Asia/Colombo");

    private final MarketDataService marketDataService;
    private final MarketDataRepository marketDataRepository;
    private final MarketDataScraperService marketDataScraperService;
    private final MongoTemplate mongoTemplate;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<Map<String, Object>>> previewCsv(@RequestParam("file") MultipartFile file) {
        List<Map<String, Object>> preview = marketDataService.previewCsv(file);
        return ResponseEntity.ok(preview);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Integer>> uploadCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("tradeDate") LocalDate tradeDate) {
        int count = marketDataService.parseAndSaveCsv(file, tradeDate);
        return ResponseEntity.ok(Map.of("recordsUpdated", count));
    }

    @GetMapping
    public ResponseEntity<List<MarketData>> getAll() {
        return ResponseEntity.ok(marketDataService.getAll());
    }

    @GetMapping("/ytd")
    public ResponseEntity<Map<String, Object>> getYtd() {
        // Use Java Date for the match to ensure timezone consistency with stored dates
        java.util.Date yearStartDate = java.util.Date.from(
                LocalDate.now().withDayOfYear(1).atStartOfDay(TZ).toInstant()
        );
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        org.springframework.data.mongodb.core.query.Criteria.where("tradeDate").gte(yearStartDate).and("lastTrade").ne(null)
                ),
                org.springframework.data.mongodb.core.aggregation.Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "tradeDate"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("companyCode")
                        .first("lastTrade").as("firstPrice")
                        .first("tradeDate").as("firstDate")
                        .last("lastTrade").as("lastPrice")
        );
        List<org.bson.Document> docs = mongoTemplate.aggregate(agg, "market_data", org.bson.Document.class).getMappedResults();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        docs.forEach(d -> {
            double first = toDouble(d.get("firstPrice"));
            double last = toDouble(d.get("lastPrice"));
            if (first > 0 && last > 0) {
                double ytd = ((last - first) / first) * 100;
                Object fd = d.get("firstDate");
                String firstDateStr = "";
                if (fd instanceof java.util.Date dt) firstDateStr = dt.toInstant().atZone(TZ).toLocalDate().toString();
                else if (fd != null) firstDateStr = fd.toString();
                result.put(d.getString("_id"), java.util.Map.of(
                    "ytd", Math.round(ytd * 100.0) / 100.0,
                    "firstPrice", Math.round(first * 100.0) / 100.0,
                    "firstDate", firstDateStr,
                    "lastPrice", Math.round(last * 100.0) / 100.0
                ));
            }
        });
        return ResponseEntity.ok(result);
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof org.bson.types.Decimal128 d) return d.bigDecimalValue().doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return 0; }
    }

    @GetMapping("/year-low")
    public ResponseEntity<Map<String, Object>> getYearLow() {
        java.util.Date yearStartDate = java.util.Date.from(
                LocalDate.now().withDayOfYear(1).atStartOfDay(TZ).toInstant()
        );
        // Use MarketData.class mapping to handle Decimal128 correctly
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        org.springframework.data.mongodb.core.query.Criteria.where("tradeDate").gte(yearStartDate).and("low").ne(null)
                ),
                org.springframework.data.mongodb.core.aggregation.Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "low"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("companyCode")
                        .first("low").as("lowVal")
                        .first("tradeDate").as("lowDate")
                        .first("companyCode").as("code")
        );
        List<org.bson.Document> docs = mongoTemplate.aggregate(agg, "market_data", org.bson.Document.class).getMappedResults();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        docs.forEach(d -> {
            double low = toDouble(d.get("lowVal"));
            if (low > 0) {
                Object date = d.get("lowDate");
                String dateStr = "";
                if (date instanceof java.util.Date dt) dateStr = dt.toInstant().atZone(TZ).toLocalDate().toString();
                else if (date != null) dateStr = date.toString();
                result.put(d.getString("_id"), java.util.Map.of("price", low, "date", dateStr));
            }
        });
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{code}")
    public ResponseEntity<MarketData> getByCompanyCode(@PathVariable String code) {
        return ResponseEntity.ok(marketDataService.getByCompanyCode(code));
    }

    @GetMapping("/{code}/history")
    public ResponseEntity<List<MarketData>> getHistory(@PathVariable String code) {
        return ResponseEntity.ok(marketDataRepository.findByCompanyCodeOrderByTradeDateDesc(code));
    }

    @GetMapping("/dates")
    public ResponseEntity<List<String>> getAvailableDates() {
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("tradeDate"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "_id")
        );
        List<String> dates = mongoTemplate.aggregate(agg, "market_data", org.bson.Document.class)
                .getMappedResults().stream()
                .map(doc -> {
                    Object id = doc.get("_id");
                    if (id instanceof java.time.LocalDate ld) return ld.toString();
                    if (id instanceof java.util.Date d) return d.toInstant().atZone(TZ).toLocalDate().toString();
                    return id.toString();
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(dates);
    }

    @GetMapping("/by-date/{date}")
    public ResponseEntity<List<MarketData>> getByDate(@PathVariable LocalDate date) {
        return ResponseEntity.ok(marketDataRepository.findByTradeDate(date));
    }

    @PostMapping("/scrape/{companyCode}/preview")
    public ResponseEntity<List<Map<String, Object>>> scrapePreview(@PathVariable String companyCode) {
        return ResponseEntity.ok(marketDataScraperService.scrapeCompany(companyCode));
    }

    @PostMapping("/scrape/{companyCode}/confirm")
    public ResponseEntity<Map<String, Object>> scrapeConfirm(@PathVariable String companyCode) {
        return ResponseEntity.ok(marketDataScraperService.scrapeAndSave(companyCode));
    }

    @PostMapping("/scrape/{companyCode}/save-bar")
    public ResponseEntity<Map<String, Object>> saveSingleBar(
            @PathVariable String companyCode,
            @RequestBody Map<String, Object> bar) {
        return ResponseEntity.ok(marketDataScraperService.saveBar(companyCode, bar));
    }

    @PostMapping("/scrape/{companyCode}/save-bars")
    public ResponseEntity<Map<String, Object>> saveBars(
            @PathVariable String companyCode,
            @RequestBody List<Map<String, Object>> bars) {
        int saved = marketDataScraperService.saveBars(companyCode, bars);
        return ResponseEntity.ok(Map.of("companyCode", companyCode, "totalBars", bars.size(), "newRecords", saved));
    }

    @PostMapping("/scrape-all")
    public ResponseEntity<Map<String, Object>> scrapeAll() {
        return ResponseEntity.ok(marketDataScraperService.scrapeAllCompanies());
    }

    @DeleteMapping("/range")
    public ResponseEntity<Map<String, Object>> deleteByDateRange(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        long deleted;
        String description;
        if (from != null && to != null) {
            deleted = marketDataRepository.deleteByTradeDateBetween(from, to);
            description = from + " to " + to;
        } else if (from != null) {
            deleted = marketDataRepository.deleteByTradeDateGreaterThanEqual(from);
            description = "from " + from;
        } else if (to != null) {
            deleted = marketDataRepository.deleteByTradeDateLessThanEqual(to);
            description = "up to " + to;
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Provide at least 'from' or 'to' parameter"));
        }
        return ResponseEntity.ok(Map.of("deletedCount", deleted, "range", description));
    }
}

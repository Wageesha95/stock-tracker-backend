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

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
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

    @GetMapping("/sparklines")
    public ResponseEntity<Map<String, Object>> getSparklines(@RequestParam(defaultValue = "30") int days) {
        java.util.Date cutoff = java.util.Date.from(
                LocalDate.now().minusDays(days).atStartOfDay(TZ).toInstant()
        );
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.match(
                        org.springframework.data.mongodb.core.query.Criteria.where("tradeDate").gte(cutoff).and("lastTrade").ne(null)
                ),
                org.springframework.data.mongodb.core.aggregation.Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "tradeDate"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("companyCode")
                        .push("lastTrade").as("prices")
                        .push("tradeDate").as("dates")
        ).withOptions(org.springframework.data.mongodb.core.aggregation.AggregationOptions.builder().allowDiskUse(true).build());
        List<org.bson.Document> docs = mongoTemplate.aggregate(agg, "market_data", org.bson.Document.class).getMappedResults();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        docs.forEach(d -> {
            String code = d.getString("_id");
            List<?> rawPrices = d.getList("prices", Object.class);
            List<?> rawDates = d.getList("dates", Object.class);
            if (rawPrices != null && rawPrices.size() >= 2) {
                List<Double> prices = rawPrices.stream().map(v -> toDouble(v)).filter(v -> v > 0).collect(java.util.stream.Collectors.toList());
                List<String> dates = rawDates != null ? rawDates.stream().map(v -> {
                    if (v instanceof java.util.Date dt) return dt.toInstant().atZone(TZ).toLocalDate().toString();
                    return v != null ? v.toString() : "";
                }).collect(java.util.stream.Collectors.toList()) : List.of();
                if (prices.size() >= 2) result.put(code, java.util.Map.of("prices", prices, "dates", dates));
            }
        });
        return ResponseEntity.ok(result);
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
        ).withOptions(org.springframework.data.mongodb.core.aggregation.AggregationOptions.builder().allowDiskUse(true).build());
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
        ).withOptions(org.springframework.data.mongodb.core.aggregation.AggregationOptions.builder().allowDiskUse(true).build());
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
        return ResponseEntity.ok(marketDataService.getByCompanyCode(code.toUpperCase()));
    }

    @GetMapping("/{code}/history")
    public ResponseEntity<List<MarketData>> getHistory(@PathVariable String code) {
        return ResponseEntity.ok(marketDataRepository.findByCompanyCodeOrderByTradeDateDesc(code.toUpperCase()));
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

    @GetMapping("/date-summary")
    public ResponseEntity<List<Map<String, Object>>> getDateSummary() {
        org.springframework.data.mongodb.core.aggregation.Aggregation agg = org.springframework.data.mongodb.core.aggregation.Aggregation.newAggregation(
                org.springframework.data.mongodb.core.aggregation.Aggregation.group("tradeDate").count().as("count"),
                org.springframework.data.mongodb.core.aggregation.Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "_id")
        );
        List<Map<String, Object>> result = mongoTemplate.aggregate(agg, "market_data", org.bson.Document.class)
                .getMappedResults().stream()
                .map(d -> {
                    Object id = d.get("_id");
                    String date;
                    if (id instanceof LocalDate ld) date = ld.toString();
                    else if (id instanceof java.util.Date dt) date = dt.toInstant().atZone(TZ).toLocalDate().toString();
                    else date = String.valueOf(id);
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("date", date);
                    entry.put("count", d.get("count"));
                    return entry;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/scrape/{companyCode}/preview")
    public ResponseEntity<?> scrapePreview(@PathVariable String companyCode) {
        try {
            return ResponseEntity.ok(marketDataScraperService.scrapeCompany(companyCode));
        } catch (Exception e) {
            log.error("scrapePreview failed for {}: {}", companyCode, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", errorMessage(e)));
        }
    }

    @PostMapping("/scrape/{companyCode}/confirm")
    public ResponseEntity<?> scrapeConfirm(@PathVariable String companyCode) {
        try {
            return ResponseEntity.ok(marketDataScraperService.scrapeAndSave(companyCode));
        } catch (Exception e) {
            log.error("scrapeConfirm failed for {}: {}", companyCode, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", errorMessage(e)));
        }
    }

    @PostMapping("/scrape/{companyCode}/save-bar")
    public ResponseEntity<?> saveSingleBar(
            @PathVariable String companyCode,
            @RequestBody Map<String, Object> bar) {
        try {
            return ResponseEntity.ok(marketDataScraperService.saveBar(companyCode, bar));
        } catch (Exception e) {
            log.error("saveSingleBar failed for {} bar={}: {}", companyCode, bar, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", errorMessage(e)));
        }
    }

    @PostMapping("/scrape/{companyCode}/save-bars")
    public ResponseEntity<?> saveBars(
            @PathVariable String companyCode,
            @RequestBody List<Map<String, Object>> bars) {
        try {
            int saved = marketDataScraperService.saveBars(companyCode, bars);
            return ResponseEntity.ok(Map.of("companyCode", companyCode, "totalBars", bars.size(), "newRecords", saved));
        } catch (Exception e) {
            log.error("saveBars failed for {} (count={}): {}", companyCode, bars.size(), e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", errorMessage(e)));
        }
    }

    @PostMapping("/scrape-all")
    public ResponseEntity<?> scrapeAll() {
        try {
            return ResponseEntity.ok(marketDataScraperService.scrapeAllCompanies());
        } catch (Exception e) {
            log.error("scrapeAll failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", errorMessage(e)));
        }
    }

    private String errorMessage(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String causeMsg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
            msg = msg + " | cause: " + causeMsg;
        }
        return msg;
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

package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.repository.MarketDataRepository;
import com.personal.stocktracker.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final MarketDataRepository marketDataRepository;
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
        // Get dates from actual MarketData documents to ensure consistency
        List<String> dates = marketDataRepository.findAll().stream()
                .map(md -> md.getTradeDate().toString())
                .distinct()
                .sorted(java.util.Comparator.reverseOrder())
                .collect(Collectors.toList());
        return ResponseEntity.ok(dates);
    }

    @GetMapping("/by-date/{date}")
    public ResponseEntity<List<MarketData>> getByDate(@PathVariable LocalDate date) {
        return ResponseEntity.ok(marketDataRepository.findByTradeDate(date));
    }
}

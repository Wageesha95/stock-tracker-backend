package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.repository.MarketDataRepository;
import com.personal.stocktracker.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/market-data")
@RequiredArgsConstructor
public class MarketDataController {

    private final MarketDataService marketDataService;
    private final MarketDataRepository marketDataRepository;

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
}

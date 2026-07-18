package com.personal.stocktracker.controller;

import com.personal.stocktracker.service.CseMarketDataScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Trigger for the lightweight, browserless CSE market-data scraper.
 * Under /api/admin/** so it is ADMIN-gated by SecurityConfig.
 */
@RestController
@RequiredArgsConstructor
public class CseMarketDataController {

    private final CseMarketDataScraperService cseMarketDataScraperService;

    @PostMapping("/api/admin/scrape/market-data-cse")
    public ResponseEntity<Map<String, Object>> scrapeAll() {
        return ResponseEntity.ok(cseMarketDataScraperService.scrapeAllCompanies());
    }

    /** The last actual CSE trading day — the UI resolves it once, then passes it per company. */
    @GetMapping("/api/admin/scrape/market-data-cse/trade-date")
    public ResponseEntity<Map<String, Object>> tradeDate() {
        return ResponseEntity.ok(Map.of("tradeDate", cseMarketDataScraperService.resolveTradeDate().toString()));
    }

    /** Per-company fetch, so the UI can loop and show live progress. */
    @PostMapping("/api/admin/scrape/market-data-cse/{companyCode}")
    public ResponseEntity<Map<String, Object>> scrapeOne(
            @PathVariable String companyCode,
            @RequestParam(required = false) String tradeDate) {
        LocalDate date = (tradeDate != null && !tradeDate.isBlank()) ? LocalDate.parse(tradeDate) : null;
        return ResponseEntity.ok(cseMarketDataScraperService.scrapeOne(companyCode, date));
    }
}

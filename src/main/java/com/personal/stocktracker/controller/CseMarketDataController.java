package com.personal.stocktracker.controller;

import com.personal.stocktracker.service.CseMarketDataScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

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
}

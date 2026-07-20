package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.MarketDataSettings;
import com.personal.stocktracker.service.MarketDataScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin settings for market-data ingestion. Under /api/admin/** so it is ADMIN-gated by SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin/market-data/settings")
@RequiredArgsConstructor
public class MarketDataSettingsController {

    private final MarketDataScraperService marketDataScraperService;

    @GetMapping
    public ResponseEntity<MarketDataSettings> get() {
        return ResponseEntity.ok(marketDataScraperService.getSettings());
    }

    /** Toggle whether the TradingView scraper may overwrite CSE-API rows. */
    @PostMapping("/scraper-overwrite-cse")
    public ResponseEntity<MarketDataSettings> setScraperOverwriteCse(@RequestParam boolean enabled) {
        return ResponseEntity.ok(marketDataScraperService.setScraperOverwriteCse(enabled));
    }
}

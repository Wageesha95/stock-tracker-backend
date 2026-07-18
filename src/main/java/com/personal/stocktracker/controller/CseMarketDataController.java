package com.personal.stocktracker.controller;

import com.personal.stocktracker.service.CseMarketDataScraperService;
import lombok.RequiredArgsConstructor;
import com.personal.stocktracker.document.CseScrapeStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Last-run time (server) + status + auto-fetch flag, for the admin panel. */
    @GetMapping("/api/admin/scrape/market-data-cse/status")
    public ResponseEntity<CseScrapeStatus> status() {
        return ResponseEntity.ok(cseMarketDataScraperService.getStatus());
    }

    /** Start/stop the server's 15-min auto-fetch. */
    @PostMapping("/api/admin/scrape/market-data-cse/auto")
    public ResponseEntity<CseScrapeStatus> setAuto(@RequestParam boolean enabled) {
        return ResponseEntity.ok(cseMarketDataScraperService.setAutoEnabled(enabled));
    }

    /** The UI records the outcome after its per-company loop finishes (server stamps the time). */
    @PostMapping("/api/admin/scrape/market-data-cse/record")
    public ResponseEntity<CseScrapeStatus> record(@RequestBody Map<String, Object> body) {
        int total = ((Number) body.getOrDefault("total", 0)).intValue();
        int saved = ((Number) body.getOrDefault("saved", 0)).intValue();
        int failed = ((Number) body.getOrDefault("failed", 0)).intValue();
        String tradeDate = body.get("tradeDate") != null ? body.get("tradeDate").toString() : null;
        return ResponseEntity.ok(cseMarketDataScraperService.recordRun(total, saved, failed, tradeDate));
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

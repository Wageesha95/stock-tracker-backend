package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.DividendPayout;
import com.personal.stocktracker.repository.DividendPayoutRepository;
import com.personal.stocktracker.service.DividendScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DividendPayoutController {

    private final DividendScraperService dividendScraperService;
    private final DividendPayoutRepository dividendPayoutRepository;

    @PostMapping("/api/admin/scrape/dividends/{companyCode}/debug")
    public ResponseEntity<String> scrapeDebug(@PathVariable String companyCode) {
        return ResponseEntity.ok(dividendScraperService.scrapeDebugPageSource(companyCode));
    }

    @PostMapping("/api/admin/scrape/dividends/{companyCode}/preview")
    public ResponseEntity<List<DividendPayout>> scrapePreview(@PathVariable String companyCode) {
        return ResponseEntity.ok(dividendScraperService.scrapeCompanyPreview(companyCode));
    }

    @PostMapping("/api/admin/scrape/dividends/{companyCode}/confirm")
    public ResponseEntity<Map<String, Object>> scrapeConfirm(
            @PathVariable String companyCode,
            @RequestBody List<DividendPayout> payouts) {
        int saved = dividendScraperService.saveDividendPayouts(companyCode, payouts);
        return ResponseEntity.ok(Map.of(
                "companyCode", companyCode,
                "newRecords", saved,
                "totalScraped", payouts.size()
        ));
    }

    @PostMapping("/api/admin/scrape/dividends")
    public ResponseEntity<Map<String, Object>> scrapeAll() {
        return ResponseEntity.ok(dividendScraperService.scrapeAllCompanies());
    }

    @GetMapping("/api/dividend-payouts")
    public ResponseEntity<List<DividendPayout>> getAll() {
        return ResponseEntity.ok(dividendPayoutRepository.findAll());
    }

    @GetMapping("/api/dividend-payouts/company/{code}")
    public ResponseEntity<List<DividendPayout>> getByCompany(@PathVariable String code) {
        return ResponseEntity.ok(
                dividendPayoutRepository.findByCompanyCodeOrderByExDividendDateDesc(code)
        );
    }
}

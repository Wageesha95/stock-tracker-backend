package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.DividendPayout;
import com.personal.stocktracker.repository.DividendPayoutRepository;
import com.personal.stocktracker.document.DividendFinancial;
import com.personal.stocktracker.repository.DividendFinancialRepository;
import com.personal.stocktracker.service.DividendCalendarScraperService;
import com.personal.stocktracker.service.DividendFinancialScraperService;
import com.personal.stocktracker.service.DividendScraperService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@lombok.extern.slf4j.Slf4j
@RestController
@RequiredArgsConstructor
public class DividendPayoutController {

    private final DividendScraperService dividendScraperService;
    private final DividendCalendarScraperService dividendCalendarScraperService;
    private final DividendFinancialScraperService dividendFinancialScraperService;
    private final DividendFinancialRepository dividendFinancialRepository;
    private final DividendPayoutRepository dividendPayoutRepository;
    private final com.personal.stocktracker.repository.MarketDataRepository marketDataRepository;

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

    // ---- Manual admin CRUD for dividend payout (calendar) records ----
    // Lets an admin fix or add the "to be received" dividends (value / XD date /
    // payment date / type) that drive the pending-dividend and upcoming views.

    @PostMapping("/api/admin/dividend-payouts")
    public ResponseEntity<?> createPayout(@RequestBody DividendPayout payout) {
        if (payout.getCompanyCode() == null || payout.getCompanyCode().isBlank() || payout.getExDividendDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "companyCode and exDividendDate are required"));
        }
        String code = payout.getCompanyCode().toUpperCase();
        if (dividendPayoutRepository.existsByCompanyCodeAndExDividendDate(code, payout.getExDividendDate())) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "A payout already exists for " + code + " with XD date " + payout.getExDividendDate()));
        }
        payout.setId(null);
        payout.setCompanyCode(code);
        payout.setScrapedAt(LocalDateTime.now());
        return ResponseEntity.ok(dividendPayoutRepository.save(payout));
    }

    @PutMapping("/api/admin/dividend-payouts/{id}")
    public ResponseEntity<?> updatePayout(@PathVariable String id, @RequestBody DividendPayout body) {
        DividendPayout existing = dividendPayoutRepository.findById(id).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        if (body.getExDividendDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "exDividendDate is required"));
        }
        String code = body.getCompanyCode() != null && !body.getCompanyCode().isBlank()
                ? body.getCompanyCode().toUpperCase() : existing.getCompanyCode();
        // Protect the unique (companyCode, exDividendDate) index from collisions with a different record.
        Optional<DividendPayout> clash = dividendPayoutRepository.findByCompanyCodeAndExDividendDate(code, body.getExDividendDate());
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "Another payout already exists for " + code + " with XD date " + body.getExDividendDate()));
        }
        existing.setCompanyCode(code);
        existing.setExDividendDate(body.getExDividendDate());
        existing.setAmountPerShare(body.getAmountPerShare());
        existing.setPaymentDate(body.getPaymentDate());
        existing.setAnnouncementDate(body.getAnnouncementDate());
        existing.setDividendType(body.getDividendType());
        existing.setPriceOnXdDate(body.getPriceOnXdDate());
        existing.setPriceOnAnnouncementDate(body.getPriceOnAnnouncementDate());
        return ResponseEntity.ok(dividendPayoutRepository.save(existing));
    }

    @DeleteMapping("/api/admin/dividend-payouts/{id}")
    public ResponseEntity<?> deletePayout(@PathVariable String id) {
        if (!dividendPayoutRepository.existsById(id)) return ResponseEntity.notFound().build();
        dividendPayoutRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    @GetMapping("/api/dividend-payouts")
    public ResponseEntity<?> getAll() {
        try {
            List<DividendPayout> all = dividendPayoutRepository.findAll();
            LocalDate twoYearsAgo = LocalDate.now().minusYears(2);
            List<DividendPayout> filtered = all.stream()
                    .filter(p -> p.getExDividendDate() != null && !p.getExDividendDate().isBefore(twoYearsAgo))
                    .collect(java.util.stream.Collectors.toList());
            return ResponseEntity.ok(filtered);
        } catch (Exception e) {
            log.error("Failed to load dividend payouts: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(java.util.Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/dividend-payouts/company/{code}")
    public ResponseEntity<List<DividendPayout>> getByCompany(@PathVariable String code) {
        code = code.toUpperCase();
        List<DividendPayout> payouts = dividendPayoutRepository.findByCompanyCodeOrderByExDividendDateDesc(code);

        // Load the company's full market history once, build a date -> lastTrade map, and
        // look up missing payout prices in memory. Avoids N * (1+7) Mongo round-trips.
        boolean needsPrice = false;
        for (DividendPayout p : payouts) {
            if ((p.getPriceOnXdDate() == null && p.getExDividendDate() != null)
                    || (p.getPriceOnAnnouncementDate() == null && p.getAnnouncementDate() != null)) {
                needsPrice = true;
                break;
            }
        }
        if (needsPrice) {
            var history = marketDataRepository.findByCompanyCodeOrderByTradeDateDesc(code);
            Map<LocalDate, BigDecimal> priceByDate = new HashMap<>();
            for (var md : history) {
                if (md.getTradeDate() != null && md.getLastTrade() != null) {
                    priceByDate.put(md.getTradeDate(), md.getLastTrade());
                }
            }
            for (DividendPayout p : payouts) {
                if (p.getPriceOnXdDate() == null && p.getExDividendDate() != null) {
                    p.setPriceOnXdDate(lookupPriceInMap(priceByDate, p.getExDividendDate()));
                }
                if (p.getPriceOnAnnouncementDate() == null && p.getAnnouncementDate() != null) {
                    p.setPriceOnAnnouncementDate(lookupPriceInMap(priceByDate, p.getAnnouncementDate()));
                }
            }
        }
        return ResponseEntity.ok(payouts);
    }

    private BigDecimal lookupPriceInMap(Map<LocalDate, BigDecimal> priceByDate, LocalDate date) {
        BigDecimal p = priceByDate.get(date);
        if (p != null) return p;
        for (int i = 1; i <= 7; i++) {
            p = priceByDate.get(date.minusDays(i));
            if (p != null) return p;
        }
        return null;
    }

    @PostMapping("/api/admin/scrape/dividend-calendar/preview")
    public ResponseEntity<List<Map<String, Object>>> scrapeCalendarPreview(
            @RequestParam(defaultValue = "") String dateStart,
            @RequestParam(defaultValue = "") String dateEnd) {
        if (dateStart.isBlank()) dateStart = LocalDate.now().minusYears(1).toString();
        if (dateEnd.isBlank()) dateEnd = LocalDate.now().plusMonths(3).toString();
        return ResponseEntity.ok(dividendCalendarScraperService.scrapeCalendar(dateStart, dateEnd));
    }

    @PostMapping("/api/admin/scrape/dividend-calendar/confirm")
    public ResponseEntity<Map<String, Object>> scrapeCalendarConfirm(
            @RequestBody List<Map<String, Object>> records) {
        return ResponseEntity.ok(dividendCalendarScraperService.saveRecords(records));
    }

    // Dividend Financials (FY data: DPS, yield, payout ratio)
    @PostMapping("/api/admin/scrape/dividend-financials/{companyCode}/preview")
    public ResponseEntity<List<Map<String, Object>>> scrapeFinancialsPreview(@PathVariable String companyCode) {
        return ResponseEntity.ok(dividendFinancialScraperService.scrapeCompany(companyCode));
    }

    @PostMapping("/api/admin/scrape/dividend-financials/{companyCode}/confirm")
    public ResponseEntity<Map<String, Object>> scrapeFinancialsConfirm(@PathVariable String companyCode) {
        return ResponseEntity.ok(dividendFinancialScraperService.scrapeAndSave(companyCode));
    }

    @PostMapping("/api/admin/scrape/dividend-financials")
    public ResponseEntity<Map<String, Object>> scrapeFinancialsAll() {
        return ResponseEntity.ok(dividendFinancialScraperService.scrapeAllCompanies());
    }

    @GetMapping("/api/dividend-financials/company/{code}")
    public ResponseEntity<List<DividendFinancial>> getFinancialsByCompany(@PathVariable String code) {
        return ResponseEntity.ok(dividendFinancialRepository.findByCompanyCodeOrderByYearDesc(code.toUpperCase()));
    }

    @GetMapping("/api/dividend-payouts/upcoming")
    public ResponseEntity<List<Map<String, Object>>> getUpcoming(@RequestParam(defaultValue = "1") int months) {
        if (months < 1) months = 1;
        if (months > 3) months = 3;

        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusMonths(months);
        int windowStartDay = today.getDayOfYear();
        int windowEndDay = windowEnd.getDayOfYear();
        int windowStartMonth = today.getMonthValue();
        int windowEndMonth = windowEnd.getMonthValue();

        // Look back 5 years for historical patterns
        LocalDate fiveYearsAgo = today.minusYears(5);
        List<DividendPayout> allRecent = dividendPayoutRepository.findByExDividendDateBetween(fiveYearsAgo, today);

        // Group by company
        Map<String, List<DividendPayout>> byCompany = new LinkedHashMap<>();
        for (DividendPayout p : allRecent) {
            byCompany.computeIfAbsent(p.getCompanyCode(), k -> new ArrayList<>()).add(p);
        }

        List<Map<String, Object>> results = new ArrayList<>();

        for (Map.Entry<String, List<DividendPayout>> entry : byCompany.entrySet()) {
            String code = entry.getKey();
            List<DividendPayout> payouts = entry.getValue();

            // Find payouts whose month falls within the upcoming window
            List<DividendPayout> matching = new ArrayList<>();
            for (DividendPayout p : payouts) {
                int pMonth = p.getExDividendDate().getMonthValue();
                int pDay = p.getExDividendDate().getDayOfMonth();
                boolean inWindow;
                if (windowStartMonth <= windowEndMonth) {
                    // Same year range (e.g., Apr-Jun)
                    inWindow = (pMonth > windowStartMonth && pMonth < windowEndMonth)
                            || (pMonth == windowStartMonth && pDay >= today.getDayOfMonth())
                            || (pMonth == windowEndMonth && pDay <= windowEnd.getDayOfMonth());
                } else {
                    // Wraps around year end (e.g., Nov-Feb)
                    inWindow = (pMonth > windowStartMonth || pMonth < windowEndMonth)
                            || (pMonth == windowStartMonth && pDay >= today.getDayOfMonth())
                            || (pMonth == windowEndMonth && pDay <= windowEnd.getDayOfMonth());
                }
                if (inWindow) {
                    matching.add(p);
                }
            }

            if (matching.isEmpty()) continue;

            // Count distinct years
            Set<Integer> years = new TreeSet<>();
            for (DividendPayout p : matching) {
                years.add(p.getExDividendDate().getYear());
            }

            // Build historical detail per year
            List<Map<String, Object>> history = new ArrayList<>();
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (DividendPayout p : matching) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("year", p.getExDividendDate().getYear());
                h.put("exDividendDate", p.getExDividendDate().toString());
                h.put("amountPerShare", p.getAmountPerShare());
                h.put("paymentDate", p.getPaymentDate() != null ? p.getPaymentDate().toString() : null);
                h.put("announcementDate", p.getAnnouncementDate() != null ? p.getAnnouncementDate().toString() : null);
                h.put("dividendType", p.getDividendType());
                h.put("priceOnXdDate", p.getPriceOnXdDate());
                history.add(h);
                if (p.getAmountPerShare() != null) {
                    totalAmount = totalAmount.add(p.getAmountPerShare());
                }
            }
            history.sort((a, b) -> ((String) b.get("exDividendDate")).compareTo((String) a.get("exDividendDate")));

            BigDecimal avgAmount = !matching.isEmpty() && totalAmount.compareTo(BigDecimal.ZERO) > 0
                    ? totalAmount.divide(BigDecimal.valueOf(matching.size()), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("companyCode", code);
            result.put("yearsAppeared", years.size());
            result.put("avgAmountPerShare", avgAmount);
            result.put("history", history);
            results.add(result);
        }

        // Sort by yearsAppeared desc, then by company code
        results.sort((a, b) -> {
            int cmp = Integer.compare((int) b.get("yearsAppeared"), (int) a.get("yearsAppeared"));
            return cmp != 0 ? cmp : ((String) a.get("companyCode")).compareTo((String) b.get("companyCode"));
        });

        return ResponseEntity.ok(results);
    }
}

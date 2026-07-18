package com.personal.stocktracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.CseScrapeStatus;
import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.CseScrapeStatusRepository;
import com.personal.stocktracker.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, browserless market-data scraper.
 *
 * Instead of driving a headless Chrome against TradingView (heavy — see
 * {@link MarketDataScraperService}), this pulls the same numbers straight from the
 * Colombo Stock Exchange's public JSON API over plain HTTP. No browser, so the peak
 * memory footprint is just the JVM (~150–250 MB) — comfortably inside a 512 MB box.
 *
 * The existing Selenium scraper is intentionally left untouched; this is an
 * independent, memory-optimized alternative for the daily price snapshot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CseMarketDataScraperService {

    private final MarketDataRepository marketDataRepository;
    private final CompanyRepository companyRepository;
    private final CseScrapeStatusRepository cseScrapeStatusRepository;

    private static final String CSE_INFO_URL = "https://www.cse.lk/api/companyInfoSummery";
    private static final String CSE_MARKET_SUMMARY_URL = "https://www.cse.lk/api/marketSummery";
    // App company codes omit the CSE symbol's trailing "0000" (e.g. "JKH.N" -> "JKH.N0000").
    private static final String SYMBOL_SUFFIX = "0000";
    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final long DELAY_MS = 250L; // polite spacing between requests

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** Persist the outcome of a run (server-stamped time) for the admin panel status display. */
    public CseScrapeStatus recordRun(int total, int saved, int failed, String tradeDate) {
        String status = failed == 0 ? "success" : (saved > 0 ? "partial" : "failed");
        CseScrapeStatus s = cseScrapeStatusRepository.findById(CseScrapeStatus.SINGLETON_ID)
                .orElseGet(() -> CseScrapeStatus.builder().id(CseScrapeStatus.SINGLETON_ID).build());
        s.setLastRunAt(LocalDateTime.now());
        s.setTradeDate(tradeDate);
        s.setTotal(total);
        s.setSaved(saved);
        s.setFailed(failed);
        s.setStatus(status);
        return cseScrapeStatusRepository.save(s); // autoEnabled preserved
    }

    public CseScrapeStatus getStatus() {
        return cseScrapeStatusRepository.findById(CseScrapeStatus.SINGLETON_ID).orElse(null);
    }

    /** Auto-fetch is on unless explicitly turned off (null defaults to on). */
    public boolean isAutoEnabled() {
        CseScrapeStatus s = getStatus();
        return s == null || s.getAutoEnabled() == null || s.getAutoEnabled();
    }

    public CseScrapeStatus setAutoEnabled(boolean enabled) {
        CseScrapeStatus s = cseScrapeStatusRepository.findById(CseScrapeStatus.SINGLETON_ID)
                .orElseGet(() -> CseScrapeStatus.builder().id(CseScrapeStatus.SINGLETON_ID).build());
        s.setAutoEnabled(enabled);
        return cseScrapeStatusRepository.save(s);
    }

    /**
     * Server-side auto-fetch on Colombo quarter-hour marks (00:00, 00:15, …) — runs 24/7
     * regardless of whether anyone is logged in, as long as the backend is up. Toggle via
     * the admin panel (isAutoEnabled).
     */
    @Scheduled(cron = "0 0/15 * * * *", zone = "Asia/Colombo")
    public void scheduledFetch() {
        if (!isAutoEnabled()) {
            log.debug("CSE auto-fetch is disabled; skipping scheduled run");
            return;
        }
        try {
            log.info("Running scheduled CSE market-data fetch");
            scrapeAllCompanies();
        } catch (Exception e) {
            log.error("Scheduled CSE fetch failed: {}", e.getMessage(), e);
        }
    }

    /**
     * The last actual trading day per CSE (its marketSummery.tradeDate), so data fetched
     * on a weekend/holiday is aligned to the day it really belongs to — not calendar today.
     * Falls back to today (Colombo) if the market summary can't be read.
     */
    public LocalDate resolveTradeDate() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(CSE_MARKET_SUMMARY_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", "Mozilla/5.0 (stock-tracker CSE market-data)")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode td = mapper.readTree(res.body()).get("tradeDate");
                if (td != null && td.isNumber()) {
                    return Instant.ofEpochMilli(td.asLong()).atZone(COLOMBO).toLocalDate();
                }
            }
        } catch (Exception e) {
            log.warn("Could not resolve CSE market trade date; using today: {}", e.getMessage());
        }
        return LocalDate.now(COLOMBO);
    }

    /** Fetch and upsert market data for every registered company, aligned to the last market day. */
    public Map<String, Object> scrapeAllCompanies() {
        List<Company> companies = companyRepository.findAll();
        LocalDate tradeDate = resolveTradeDate();
        log.info("CSE market-data scrape: {} companies for {}", companies.size(), tradeDate);

        int succeeded = 0, failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (int i = 0; i < companies.size(); i++) {
            String code = companies.get(i).getCode();
            try {
                boolean saved = scrapeAndSave(code, tradeDate);
                if (saved) {
                    succeeded++;
                } else {
                    details.add(Map.of("companyCode", code, "skipped", "no price"));
                }
            } catch (Exception e) {
                failed++;
                log.warn("CSE market-data failed for {}: {}", code, e.getMessage());
                details.add(Map.of("companyCode", code, "error", String.valueOf(e.getMessage())));
            }
            if (i < companies.size() - 1) {
                try { Thread.sleep(DELAY_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }

        recordRun(companies.size(), succeeded, failed, tradeDate.toString());
        log.info("CSE market-data scrape complete: {} saved, {} failed of {}", succeeded, failed, companies.size());
        return Map.of(
                "source", "cse.lk",
                "tradeDate", tradeDate.toString(),
                "totalCompanies", companies.size(),
                "succeeded", succeeded,
                "failed", failed,
                "details", details
        );
    }

    /**
     * Fetch + upsert one company; never throws (status carries the outcome). The caller
     * passes the resolved last-market-day so a whole run aligns to one date (and avoids
     * re-fetching the market summary per company); null resolves it here as a fallback.
     */
    public Map<String, Object> scrapeOne(String companyCode, LocalDate tradeDate) {
        LocalDate date = tradeDate != null ? tradeDate : resolveTradeDate();
        try {
            boolean saved = scrapeAndSave(companyCode, date);
            return Map.of("companyCode", companyCode, "status", saved ? "saved" : "skipped", "tradeDate", date.toString());
        } catch (Exception e) {
            log.warn("CSE market-data failed for {}: {}", companyCode, e.getMessage());
            return Map.of("companyCode", companyCode, "status", "error", "error", String.valueOf(e.getMessage()));
        }
    }

    /** Fetch a single company's snapshot and upsert it for the given trade date. */
    public boolean scrapeAndSave(String companyCode, LocalDate tradeDate) throws Exception {
        JsonNode info = fetchSymbolInfo(companyCode);
        if (info == null) return false;

        BigDecimal lastTrade = bd(info, "lastTradedPrice");
        if (lastTrade == null) lastTrade = bd(info, "closingPrice");
        if (lastTrade == null) return false; // nothing traded / no price — skip

        BigDecimal prevClose = bd(info, "previousClose");
        BigDecimal change = null, changePercent = null;
        if (prevClose != null && prevClose.signum() != 0) {
            change = lastTrade.subtract(prevClose);
            changePercent = change.divide(prevClose, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
        }

        String name = info.hasNonNull("name") ? info.get("name").asText() : companyCode;

        MarketData md = marketDataRepository.findByCompanyCodeAndTradeDate(companyCode, tradeDate)
                .orElseGet(() -> MarketData.builder().companyCode(companyCode).tradeDate(tradeDate).build());
        md.setCompanyName(name);
        md.setLastTrade(lastTrade);
        md.setHigh(bd(info, "hiTrade"));
        md.setLow(bd(info, "lowTrade"));
        md.setVolume(bd(info, "tdyShareVolume"));
        md.setChange(change);
        md.setChangePercent(changePercent);
        md.setSource("CSE");
        md.setUpdatedAt(LocalDateTime.now());
        marketDataRepository.save(md);
        return true;
    }

    private JsonNode fetchSymbolInfo(String companyCode) throws Exception {
        String symbol = companyCode + SYMBOL_SUFFIX;
        String body = "symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(CSE_INFO_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "Mozilla/5.0 (stock-tracker CSE market-data)")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("HTTP " + res.statusCode());
        }
        JsonNode root = mapper.readTree(res.body());
        JsonNode info = root.get("reqSymbolInfo");
        return info != null && !info.isNull() ? info : null;
    }

    /** Read a numeric field as BigDecimal, or null when absent/blank/non-numeric. */
    private static BigDecimal bd(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.personal.stocktracker.service;

import com.personal.stocktracker.config.ScraperConfig;
import com.personal.stocktracker.document.DividendFinancial;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.DividendFinancialRepository;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;

@Slf4j
@Service
@RequiredArgsConstructor
public class DividendFinancialScraperService {

    private final DividendFinancialRepository dividendFinancialRepository;
    private final CompanyRepository companyRepository;
    private final ScraperConfig scraperConfig;

    /**
     * Scrape FY dividend data (DPS, yield, payout ratio) for a single company.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> scrapeCompany(String companyCode) {
        WebDriver driver = createDriver();
        try {
            String url = scraperConfig.getBaseUrl() + companyCode + scraperConfig.getUrlSuffix();
            log.info("Scraping dividend financials for {} from {}", companyCode, url);

            // Inject WebSocket interceptor
            if (driver instanceof ChromeDriver chromeDriver) {
                chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", """
                    window.__fullWsData = '';
                    var OrigWS = window.WebSocket;
                    window.WebSocket = function(url, protocols) {
                        var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
                        ws.addEventListener('message', function(e) {
                            var data = typeof e.data === 'string' ? e.data : '';
                            if (data.length > window.__fullWsData.length) {
                                window.__fullWsData = data;
                            }
                        });
                        return ws;
                    };
                    window.WebSocket.prototype = OrigWS.prototype;
                    window.WebSocket.CONNECTING = OrigWS.CONNECTING;
                    window.WebSocket.OPEN = OrigWS.OPEN;
                    window.WebSocket.CLOSING = OrigWS.CLOSING;
                    window.WebSocket.CLOSED = OrigWS.CLOSED;
                """));
            }

            driver.get(url);
            try { Thread.sleep(8000); } catch (InterruptedException ignored) {}

            JavascriptExecutor js = (JavascriptExecutor) driver;

            // Extract FY data from WebSocket - look for fiscal year arrays
            Map<String, Object> fyData = (Map<String, Object>) js.executeScript("""
                if (!window.__fullWsData) return {error: 'no ws data'};
                var raw = window.__fullWsData;

                function extractArray(key) {
                    var idx = raw.indexOf('"' + key + '"');
                    if (idx < 0) return null;
                    var arrStart = raw.indexOf('[', idx);
                    if (arrStart < 0 || arrStart - idx > 50) return null;
                    var depth = 0, end = arrStart;
                    for (var i = arrStart; i < raw.length; i++) {
                        if (raw[i] === '[') depth++;
                        else if (raw[i] === ']') { depth--; if (depth === 0) { end = i; break; } }
                    }
                    try { return JSON.parse(raw.substring(arrStart, end + 1)); }
                    catch(e) { return null; }
                }

                // Try multiple possible field names for FY data
                // Historical arrays use _h suffix
                var dps = extractArray('dps_common_stock_prim_issue_fy_h')
                    || extractArray('dps_common_stock_prim_issue_fy');

                var yieldArr = extractArray('dividends_yield_fy_h')
                    || extractArray('dividends_yield_fy')
                    || extractArray('dividends_yield_h')
                    || extractArray('dividend_yield_fy_h');

                var epsArr = extractArray('earnings_per_share_diluted_fy_h')
                    || extractArray('earnings_per_share_fy_h')
                    || extractArray('earnings_per_share_basic_fy_h');

                // Fiscal years - stored as timestamps
                var fyPeriods = extractArray('fiscal_period_fy_h')
                    || extractArray('fiscal_year_end_fy_h')
                    || extractArray('fiscal_period_end_fy_h');

                // Debug: check what keys exist and what follows them
                var keysSample = [];
                var searchTerms = ['dps', 'dividend', 'yield', 'payout', 'fiscal', 'period', 'ratio'];
                for (var term of searchTerms) {
                    var searchIdx = 0;
                    var count = 0;
                    while (count < 10) {
                        var found = raw.indexOf('"' + term, searchIdx);
                        if (found < 0) break;
                        var keyEnd = raw.indexOf('"', found + 1);
                        if (keyEnd < 0) break;
                        keysSample.push(raw.substring(found, Math.min(keyEnd + 1, found + 60)));
                        searchIdx = keyEnd + 1;
                        count++;
                    }
                }

                // Dump content around specific keys
                var dpsDebug = '';
                var dpsIdx = raw.indexOf('"dps_common_stock_prim_issue_fy_h"');
                if (dpsIdx >= 0) {
                    dpsDebug = raw.substring(dpsIdx, Math.min(dpsIdx + 300, raw.length));
                }
                var periodsDebug = '';
                var perIdx = raw.indexOf('"fiscal_period_fy_h"');
                if (perIdx >= 0) {
                    periodsDebug = raw.substring(perIdx, Math.min(perIdx + 300, raw.length));
                }

                // Find ALL keys containing _fy_h to discover yield/payout key names
                var fyHKeys = [];
                var fyIdx = 0;
                while (fyHKeys.length < 30) {
                    fyIdx = raw.indexOf('_fy_h"', fyIdx);
                    if (fyIdx < 0) break;
                    // Walk back to find the key start
                    var keyStart = raw.lastIndexOf('"', fyIdx - 1);
                    if (keyStart >= 0) {
                        fyHKeys.push(raw.substring(keyStart + 1, fyIdx + 4));
                    }
                    fyIdx += 5;
                }

                return {
                    dps: dps,
                    eps: epsArr,
                    yield: yieldArr,
                    periods: fyPeriods,
                    debug: {
                        wsDataLen: raw.length,
                        dpsFound: !!dps, dpsLen: dps ? dps.length : 0,
                        yieldFound: !!yieldArr, yieldLen: yieldArr ? yieldArr.length : 0,
                        epsFound: !!epsArr, epsLen: epsArr ? epsArr.length : 0,
                        periodsFound: !!fyPeriods, periodsLen: fyPeriods ? fyPeriods.length : 0,
                        keysSample: keysSample,
                        dpsRawSample: dpsDebug,
                        periodsRawSample: periodsDebug,
                        allFyHKeys: fyHKeys
                    }
                };
            """);

            log.info("FY data for {}: debug={}", companyCode, fyData != null ? fyData.get("debug") : "null");

            if (fyData == null || fyData.containsKey("error")) {
                log.warn("No FY data found for {}: {}", companyCode, fyData);
                Map<String, Object> debugRow = new LinkedHashMap<>();
                debugRow.put("_debug", fyData != null ? fyData.toString() : "null ws data");
                return List.of(debugRow);
            }

            List<Object> dpsArr = (List<Object>) fyData.get("dps");
            List<Object> epsArr = (List<Object>) fyData.get("eps");
            List<Object> yieldArr = (List<Object>) fyData.get("yield");
            List<Object> periodsArr = (List<Object>) fyData.get("periods");

            if (dpsArr == null || dpsArr.isEmpty()) {
                log.warn("No DPS data found for {}. Debug: {}", companyCode, fyData.get("debug"));
                Map<String, Object> debugRow = new LinkedHashMap<>();
                debugRow.put("_debug", "No DPS arrays found. Keys: " + fyData.get("debug"));
                return List.of(debugRow);
            }

            List<Map<String, Object>> results = new ArrayList<>();
            for (int i = 0; i < dpsArr.size(); i++) {
                Map<String, Object> row = new LinkedHashMap<>();

                int year = 0;
                if (periodsArr != null && i < periodsArr.size() && periodsArr.get(i) != null) {
                    Object p = periodsArr.get(i);
                    if (p instanceof String) {
                        try { year = Integer.parseInt(((String) p).trim()); } catch (Exception ignored) {}
                    } else if (p instanceof Number) {
                        long ts = ((Number) p).longValue();
                        year = ts > 3000 ? java.time.Instant.ofEpochSecond(ts).atZone(java.time.ZoneOffset.UTC).getYear() : (int) ts;
                    }
                }
                if (year == 0) {
                    year = java.time.LocalDate.now().getYear() - (dpsArr.size() - 1 - i);
                }

                row.put("year", year);
                row.put("dps", dpsArr.get(i));
                row.put("eps", epsArr != null && i < epsArr.size() ? epsArr.get(i) : null);
                row.put("yield", yieldArr != null && i < yieldArr.size() ? yieldArr.get(i) : null);
                results.add(row);
            }

            log.info("Extracted {} FY records for {}", results.size(), companyCode);
            return results;
        } finally {
            driver.quit();
        }
    }

    /**
     * Scrape and save FY data for a single company.
     */
    public Map<String, Object> scrapeAndSave(String companyCode) {
        List<Map<String, Object>> records = scrapeCompany(companyCode);
        int saved = saveRecords(companyCode, records);
        return Map.of("companyCode", companyCode, "totalScraped", records.size(), "saved", saved);
    }

    /**
     * Scrape and save FY data for all companies.
     */
    public Map<String, Object> scrapeAllCompanies() {
        var companies = companyRepository.findAll();
        log.info("Starting FY dividend scraping for {} companies", companies.size());

        WebDriver driver = createDriver();
        int succeeded = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        try {
            for (int i = 0; i < companies.size(); i++) {
                String code = companies.get(i).getCode();
                try {
                    List<Map<String, Object>> records = scrapeCompanyWithDriver(driver, code);
                    int saved = saveRecords(code, records);
                    details.add(Map.of("companyCode", code, "scraped", records.size(), "saved", saved));
                    succeeded++;
                } catch (WebDriverException wde) {
                    log.error("WebDriver error for {}. Recreating driver.", code, wde);
                    failed++;
                    details.add(Map.of("companyCode", code, "error", wde.getMessage()));
                    try { driver.quit(); } catch (Exception ignored) {}
                    driver = createDriver();
                } catch (Exception e) {
                    log.error("Failed to scrape FY data for {}: {}", code, e.getMessage());
                    failed++;
                    details.add(Map.of("companyCode", code, "error", e.getMessage()));
                }

                if (i < companies.size() - 1) {
                    try { Thread.sleep(scraperConfig.getDelayMs()); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            try { driver.quit(); } catch (Exception ignored) {}
        }

        return Map.of("totalCompanies", companies.size(), "succeeded", succeeded, "failed", failed, "details", details);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scrapeCompanyWithDriver(WebDriver driver, String companyCode) {
        String url = scraperConfig.getBaseUrl() + companyCode + scraperConfig.getUrlSuffix();
        log.info("Scraping FY dividends for {} from {}", companyCode, url);

        if (driver instanceof ChromeDriver chromeDriver) {
            chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", """
                window.__fullWsData = '';
                var OrigWS = window.WebSocket;
                window.WebSocket = function(url, protocols) {
                    var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
                    ws.addEventListener('message', function(e) {
                        var data = typeof e.data === 'string' ? e.data : '';
                        if (data.length > window.__fullWsData.length) {
                            window.__fullWsData = data;
                        }
                    });
                    return ws;
                };
                window.WebSocket.prototype = OrigWS.prototype;
                window.WebSocket.CONNECTING = OrigWS.CONNECTING;
                window.WebSocket.OPEN = OrigWS.OPEN;
                window.WebSocket.CLOSING = OrigWS.CLOSING;
                window.WebSocket.CLOSED = OrigWS.CLOSED;
            """));
        }

        driver.get(url);
        try { Thread.sleep(8000); } catch (InterruptedException ignored) {}

        JavascriptExecutor js = (JavascriptExecutor) driver;
        Map<String, Object> fyData = (Map<String, Object>) js.executeScript("""
            if (!window.__fullWsData) return null;
            var raw = window.__fullWsData;
            function extractArray(key) {
                var idx = raw.indexOf('"' + key + '"');
                if (idx < 0) return null;
                var arrStart = raw.indexOf('[', idx);
                if (arrStart < 0 || arrStart - idx > 50) return null;
                var depth = 0, end = arrStart;
                for (var i = arrStart; i < raw.length; i++) {
                    if (raw[i] === '[') depth++;
                    else if (raw[i] === ']') { depth--; if (depth === 0) { end = i; break; } }
                }
                try { return JSON.parse(raw.substring(arrStart, end + 1)); }
                catch(e) { return null; }
            }
            return {
                dps: extractArray('dps_common_stock_prim_issue_fy_h') || extractArray('dps_common_stock_prim_issue_fy'),
                eps: extractArray('earnings_per_share_diluted_fy_h') || extractArray('earnings_per_share_fy_h') || extractArray('earnings_per_share_basic_fy_h'),
                yield: extractArray('dividends_yield_fy_h') || extractArray('dividends_yield_fy'),
                periods: extractArray('fiscal_period_fy_h') || extractArray('fiscal_year_end_fy_h')
            };
        """);

        if (fyData == null) return Collections.emptyList();

        List<Object> dpsArr = (List<Object>) fyData.get("dps");
        List<Object> epsArr2 = (List<Object>) fyData.get("eps");
        List<Object> yieldArr = (List<Object>) fyData.get("yield");
        List<Object> periodsArr = (List<Object>) fyData.get("periods");

        if (dpsArr == null || dpsArr.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < dpsArr.size(); i++) {
            int year = 0;
            if (periodsArr != null && i < periodsArr.size() && periodsArr.get(i) != null) {
                Object p = periodsArr.get(i);
                if (p instanceof String) {
                    try { year = Integer.parseInt(((String) p).trim()); } catch (Exception ignored) {}
                } else if (p instanceof Number) {
                    long ts = ((Number) p).longValue();
                    year = ts > 3000 ? java.time.Instant.ofEpochSecond(ts).atZone(java.time.ZoneOffset.UTC).getYear() : (int) ts;
                }
            }
            if (year == 0) {
                year = java.time.LocalDate.now().getYear() - (dpsArr.size() - 1 - i);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", year);
            row.put("dps", dpsArr.get(i));
            row.put("eps", epsArr2 != null && i < epsArr2.size() ? epsArr2.get(i) : null);
            row.put("yield", yieldArr != null && i < yieldArr.size() ? yieldArr.get(i) : null);
            results.add(row);
        }
        log.info("Extracted {} FY records for {}", results.size(), companyCode);
        return results;
    }

    private int saveRecords(String companyCode, List<Map<String, Object>> records) {
        int saved = 0;
        for (Map<String, Object> record : records) {
            try {
                int year = ((Number) record.get("year")).intValue();
                if (year <= 0) continue;

                BigDecimal dps = toBigDecimal(record.get("dps"));
                BigDecimal eps = toBigDecimal(record.get("eps"));
                BigDecimal yieldVal = toBigDecimal(record.get("yield"));

                if (dps == null && eps == null && yieldVal == null) continue;

                Optional<DividendFinancial> existing = dividendFinancialRepository.findByCompanyCodeAndYear(companyCode, year);
                if (existing.isPresent()) {
                    DividendFinancial df = existing.get();
                    if (dps != null) df.setDividendPerShare(dps);
                    if (eps != null) df.setEarningsPerShare(eps);
                    if (yieldVal != null) df.setDividendYield(yieldVal);
                    df.setScrapedAt(LocalDateTime.now());
                    dividendFinancialRepository.save(df);
                } else {
                    dividendFinancialRepository.save(DividendFinancial.builder()
                            .companyCode(companyCode)
                            .year(year)
                            .dividendPerShare(dps)
                            .earningsPerShare(eps)
                            .dividendYield(yieldVal)
                            .scrapedAt(LocalDateTime.now())
                            .build());
                }
                saved++;
            } catch (Exception e) {
                log.warn("Failed to save FY record for {} year {}: {}", companyCode, record.get("year"), e.getMessage());
            }
        }
        return saved;
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        try { return new BigDecimal(val.toString()); } catch (Exception e) { return null; }
    }

    private WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        if (scraperConfig.isHeadless()) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
                "--window-size=1920,8000",
                "--disable-blink-features=AutomationControlled",
                "--blink-settings=imagesEnabled=false",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        );
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);
        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(scraperConfig.getPageLoadTimeoutSec()));
        return driver;
    }
}

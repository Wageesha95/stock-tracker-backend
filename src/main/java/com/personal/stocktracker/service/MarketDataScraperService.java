package com.personal.stocktracker.service;

import com.personal.stocktracker.config.ScraperConfig;
import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.MarketDataRepository;
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
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.logging.Level;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataScraperService {

    private final MarketDataRepository marketDataRepository;
    private final CompanyRepository companyRepository;
    private final ScraperConfig scraperConfig;

    private static final String CHART_URL = "https://www.tradingview.com/chart/?symbol=CSELK:%s0000";

    /**
     * Scrape historical OHLC data for a single company from TradingView chart.
     * Returns bars as a list of maps with: date, open, high, low, close, volume.
     */
    public List<Map<String, Object>> scrapeCompany(String companyCode) {
        WebDriver driver = null;
        try {
            driver = createDriver();
            return scrapeChartData(driver, companyCode);
        } catch (Exception e) {
            log.error("Failed to scrape market data for {}: {}", companyCode, e.getMessage(), e);
            throw new RuntimeException("Scrape failed for " + companyCode + ": " + e.getMessage());
        } finally {
            if (driver != null) try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    /**
     * Scrape and save market data for a single company.
     */
    public Map<String, Object> scrapeAndSave(String companyCode) {
        List<Map<String, Object>> bars = scrapeCompany(companyCode);
        int saved = saveBars(companyCode, bars);
        return Map.of(
                "companyCode", companyCode,
                "totalScraped", bars.size(),
                "newRecords", saved
        );
    }

    /**
     * Scrape and save market data for all registered companies.
     */
    public Map<String, Object> scrapeAllCompanies() {
        var companies = companyRepository.findAll();
        log.info("Starting market data scraping for {} companies", companies.size());

        WebDriver driver = createDriver();
        int succeeded = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        try {
            for (int i = 0; i < companies.size(); i++) {
                String code = companies.get(i).getCode();
                try {
                    List<Map<String, Object>> bars = scrapeChartData(driver, code);
                    int saved = saveBars(code, bars);
                    details.add(Map.of("companyCode", code, "newRecords", saved, "totalScraped", bars.size()));
                    succeeded++;
                } catch (WebDriverException wde) {
                    log.error("WebDriver error for {}. Recreating driver.", code, wde);
                    failed++;
                    details.add(Map.of("companyCode", code, "error", wde.getMessage()));
                    try { driver.quit(); } catch (Exception ignored) {}
                    driver = createDriver();
                } catch (Exception e) {
                    log.error("Failed to scrape market data for {}: {}", code, e.getMessage());
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

        log.info("Market data scraping complete: {} succeeded, {} failed out of {}", succeeded, failed, companies.size());
        return Map.of(
                "totalCompanies", companies.size(),
                "succeeded", succeeded,
                "failed", failed,
                "details", details
        );
    }

    private WebDriver createDriver() {
        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();
        if (scraperConfig.isHeadless()) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,1080",
                "--disable-blink-features=AutomationControlled",
                "--blink-settings=imagesEnabled=false",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        );

        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(
                Duration.ofSeconds(scraperConfig.getPageLoadTimeoutSec())
        );
        return driver;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scrapeChartData(WebDriver driver, String companyCode) {
        String url = String.format(CHART_URL, companyCode);
        log.info("Scraping market data for {} from {}", companyCode, url);

        // Inject WebSocket interceptor BEFORE page load to capture chart bar data
        if (driver instanceof ChromeDriver chromeDriver) {
            chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", """
                window.__chartBars = [];
                window.__wsDebug = {msgCount: 0, msgSizes: [], matchedMsgs: 0, errors: [], sampleMessages: []};
                var OrigWS = window.WebSocket;
                window.WebSocket = function(url, protocols) {
                    var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
                    window.__wsDebug.wsUrl = url;
                    ws.addEventListener('message', function(e) {
                        var data = typeof e.data === 'string' ? e.data : '';
                        window.__wsDebug.msgCount++;
                        window.__wsDebug.msgSizes.push(data.length);
                        // Store first 10 message previews for debugging
                        if (window.__wsDebug.sampleMessages.length < 10) {
                            window.__wsDebug.sampleMessages.push(data.substring(0, 500));
                        }
                        try {
                            // TradingView sends chart data in messages containing "timescale_update" or "du" or bar arrays
                            if (data.indexOf('timescale_update') > -1 || data.indexOf('"s":[{') > -1 || data.indexOf('"v":[') > -1) {
                                window.__wsDebug.matchedMsgs++;
                                // Extract all bar arrays from the message
                                var regex = /"v":\\[([\\d.]+),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*)\\]/g;
                                var match;
                                var barsInMsg = 0;
                                while ((match = regex.exec(data)) !== null) {
                                    window.__chartBars.push({
                                        t: parseFloat(match[1]),
                                        o: parseFloat(match[2]),
                                        h: parseFloat(match[3]),
                                        l: parseFloat(match[4]),
                                        c: parseFloat(match[5]),
                                        v: parseFloat(match[6])
                                    });
                                    barsInMsg++;
                                }
                                if (barsInMsg === 0 && data.indexOf('"v":[') > -1) {
                                    // Regex didn't match but "v" arrays exist - capture sample for debugging
                                    var vIdx = data.indexOf('"v":[');
                                    window.__wsDebug.unmatchedVSample = data.substring(vIdx, vIdx + 200);
                                }
                            }
                        } catch(err) {
                            window.__wsDebug.errors.push(err.message);
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

        // Wait for initial chart data to load
        try { Thread.sleep(10000); } catch (InterruptedException ignored) {}

        JavascriptExecutor js = (JavascriptExecutor) driver;

        int initialBars = ((List<?>) js.executeScript("return window.__chartBars || [];")).size();
        log.info("Initial bars for {}: {}", companyCode, initialBars);

        // Step 1: Try to click the "5Y" or "ALL" range button on TradingView
        boolean rangeSelected = false;
        try {
            rangeSelected = (Boolean) js.executeScript("""
                // Look for range buttons (5Y, ALL, 60M)
                var buttons = document.querySelectorAll('button');
                for (var b of buttons) {
                    var text = b.textContent.trim();
                    if (text === '5Y' || text === 'ALL' || text === '60M') {
                        b.click();
                        return true;
                    }
                }
                // Also try the date range selector in the toolbar
                var items = document.querySelectorAll('[data-name="date-ranges-tab"] button, [class*="dateRange"] button, [id*="header-toolbar-intervals"] button');
                for (var item of items) {
                    var t = item.textContent.trim();
                    if (t === '5Y' || t === 'ALL' || t === '5y') {
                        item.click();
                        return true;
                    }
                }
                return false;
            """);
        } catch (Exception e) {
            log.warn("Failed to click range button: {}", e.getMessage());
        }

        if (rangeSelected) {
            log.info("Clicked 5Y/ALL range button for {}, waiting for data...", companyCode);
            try { Thread.sleep(8000); } catch (InterruptedException ignored) {}
        }

        int afterRangeBars = ((List<?>) js.executeScript("return window.__chartBars || [];")).size();
        log.info("After range select for {}: {} bars (was {})", companyCode, afterRangeBars, initialBars);

        // Step 2: Repeatedly scroll left to load more historical data
        int currentBars = afterRangeBars;
        for (int attempt = 0; attempt < 15; attempt++) {
            js.executeScript("""
                // Focus the chart area first
                var iframe = document.querySelector('iframe');
                var doc = iframe ? iframe.contentDocument : document;
                var body = doc.body || document.body;
                body.focus();

                // Method 1: Keyboard shortcuts
                // Ctrl+Shift+Left = go to beginning in TradingView
                document.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37, ctrlKey: true, shiftKey: true, bubbles: true}));
                body.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37, ctrlKey: true, shiftKey: true, bubbles: true}));

                // Method 2: Many left arrow presses
                for (var i = 0; i < 50; i++) {
                    body.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37, bubbles: true}));
                }

                // Method 3: Zoom out with Ctrl+minus
                for (var i = 0; i < 3; i++) {
                    body.dispatchEvent(new KeyboardEvent('keydown', {key: '-', code: 'Minus', keyCode: 189, ctrlKey: true, bubbles: true}));
                }

                // Method 4: Mouse wheel scroll on the chart canvas
                var canvas = document.querySelector('canvas');
                if (canvas) {
                    canvas.focus();
                    var rect = canvas.getBoundingClientRect();
                    // Shift+wheel = horizontal scroll in TradingView
                    for (var i = 0; i < 5; i++) {
                        canvas.dispatchEvent(new WheelEvent('wheel', {
                            deltaX: -1000, deltaY: 0, shiftKey: true,
                            clientX: rect.left + 100, clientY: rect.top + rect.height / 2,
                            bubbles: true, cancelable: true
                        }));
                    }
                    // Also zoom out with Ctrl+wheel
                    canvas.dispatchEvent(new WheelEvent('wheel', {
                        deltaY: 200, ctrlKey: true,
                        clientX: rect.left + rect.width / 2, clientY: rect.top + rect.height / 2,
                        bubbles: true, cancelable: true
                    }));
                }
            """);

            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

            int newBars = ((List<?>) js.executeScript("return window.__chartBars || [];")).size();
            log.info("Scroll attempt {} for {}: {} bars (was {})", attempt + 1, companyCode, newBars, currentBars);

            if (newBars <= currentBars) {
                // No new bars, try 2 more times then give up
                boolean foundMore = false;
                for (int retry = 0; retry < 2; retry++) {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    newBars = ((List<?>) js.executeScript("return window.__chartBars || [];")).size();
                    if (newBars > currentBars) { foundMore = true; break; }
                }
                if (!foundMore) {
                    log.info("No more historical data for {} after {} scroll attempts. Total: {} bars", companyCode, attempt + 1, newBars);
                    break;
                }
            }
            currentBars = newBars;
        }

        // Read debug info first
        @SuppressWarnings("unchecked")
        Map<String, Object> wsDebug = (Map<String, Object>) js.executeScript("return window.__wsDebug || {};");
        log.info("WS debug for {}: msgCount={}, matchedMsgs={}, wsUrl={}, errors={}",
                companyCode,
                wsDebug.get("msgCount"),
                wsDebug.get("matchedMsgs"),
                wsDebug.get("wsUrl"),
                wsDebug.get("errors"));

        if (wsDebug.get("unmatchedVSample") != null) {
            log.warn("Unmatched 'v' array sample for {}: {}", companyCode, wsDebug.get("unmatchedVSample"));
        }

        // Log sample messages if no bars found
        @SuppressWarnings("unchecked")
        List<String> samples = (List<String>) wsDebug.get("sampleMessages");
        if (samples != null && !samples.isEmpty()) {
            log.info("WS sample messages for {} (first {}):", companyCode, samples.size());
            for (int i = 0; i < samples.size(); i++) {
                log.info("  msg[{}]: {}", i, samples.get(i));
            }
        }

        // Read intercepted bar data
        List<Map<String, Object>> bars = (List<Map<String, Object>>) js.executeScript(
                "return window.__chartBars || [];"
        );

        log.info("Intercepted {} bars from WebSocket for {}", bars != null ? bars.size() : 0, companyCode);

        if (bars != null && !bars.isEmpty()) {
            // Log first and last bar for verification
            Map<String, Object> first = bars.get(0);
            Map<String, Object> last = bars.get(bars.size() - 1);
            log.info("First bar: t={}, o={}, h={}, l={}, c={}, v={}",
                    first.get("t"), first.get("o"), first.get("h"), first.get("l"), first.get("c"), first.get("v"));
            log.info("Last bar: t={}, o={}, h={}, l={}, c={}, v={}",
                    last.get("t"), last.get("o"), last.get("h"), last.get("l"), last.get("c"), last.get("v"));
        }

        if (bars == null || bars.isEmpty()) {
            log.error("No chart bars intercepted for {}. WebSocket messages received: {}, matched: {}",
                    companyCode, wsDebug.get("msgCount"), wsDebug.get("matchedMsgs"));
            log.info("Trying fallback extraction for {}", companyCode);
            bars = fallbackExtraction(js, companyCode);
        }

        if (bars == null || bars.isEmpty()) {
            log.error("Fallback extraction also returned 0 bars for {}. Page URL: {}", companyCode, driver.getCurrentUrl());
            log.error("Page title: {}", driver.getTitle());
        }

        // Deduplicate by timestamp (keep latest)
        Map<Long, Map<String, Object>> deduped = new LinkedHashMap<>();
        if (bars != null) {
            for (Map<String, Object> bar : bars) {
                long ts = ((Number) bar.get("t")).longValue();
                deduped.put(ts, bar);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> bar : deduped.values()) {
            long ts = ((Number) bar.get("t")).longValue();
            LocalDate date = Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", date.toString());
            row.put("open", toNumber(bar.get("o")));
            row.put("high", toNumber(bar.get("h")));
            row.put("low", toNumber(bar.get("l")));
            row.put("close", toNumber(bar.get("c")));
            row.put("volume", toNumber(bar.get("v")));
            result.add(row);
        }

        result.sort((a, b) -> ((String) b.get("date")).compareTo((String) a.get("date"))); // desc
        log.info("Parsed {} unique daily bars for {}", result.size(), companyCode);
        return result;
    }

    /**
     * Fallback: try to extract bar data from the largest WebSocket message directly.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fallbackExtraction(JavascriptExecutor js, String companyCode) {
        // Check if __fullWsData exists (from dividend scraper interceptor pattern)
        Map<String, Object> debugInfo = (Map<String, Object>) js.executeScript("""
            var info = {hasFullWsData: !!window.__fullWsData, fullWsDataLen: 0, allWsMsgCount: 0};
            if (window.__fullWsData) info.fullWsDataLen = window.__fullWsData.length;
            // Also try to find bar data by scanning all stored messages
            if (window.__wsDebug && window.__wsDebug.sampleMessages) {
                info.allWsMsgCount = window.__wsDebug.msgCount;
                // Look for any "v":[ patterns in sample messages
                var vCount = 0;
                for (var msg of window.__wsDebug.sampleMessages) {
                    var idx = 0;
                    while ((idx = msg.indexOf('"v":[', idx)) > -1) { vCount++; idx += 5; }
                }
                info.vPatternsInSamples = vCount;
            }
            return info;
        """);
        log.info("Fallback debug for {}: {}", companyCode, debugInfo);

        List<Map<String, Object>> bars = (List<Map<String, Object>>) js.executeScript("""
            // Re-intercept: look for bar data in any stored large WS message
            if (window.__fullWsData) {
                var raw = window.__fullWsData;
                var regex = /"v":\\[([\\d.]+),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*),(\\d+\\.?\\d*)\\]/g;
                var bars = [];
                var match;
                while ((match = regex.exec(raw)) !== null) {
                    bars.push({
                        t: parseFloat(match[1]),
                        o: parseFloat(match[2]),
                        h: parseFloat(match[3]),
                        l: parseFloat(match[4]),
                        c: parseFloat(match[5]),
                        v: parseFloat(match[6])
                    });
                }
                return bars;
            }
            return [];
        """);
        log.info("Fallback extraction found {} bars for {}", bars != null ? bars.size() : 0, companyCode);
        if (bars == null || bars.isEmpty()) {
            log.error("Fallback also failed for {}. No __fullWsData available or no bar patterns matched.", companyCode);
        }
        return bars;
    }

    public int saveBars(String companyCode, List<Map<String, Object>> bars) {
        if (bars.isEmpty()) {
            log.warn("No bars to save for {}", companyCode);
            return 0;
        }

        Optional<Company> company = companyRepository.findByCode(companyCode);
        String companyName = company.map(Company::getName).orElse(companyCode);
        log.info("Saving {} bars for {} (name: {})", bars.size(), companyCode, companyName);

        int newCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;
        for (Map<String, Object> bar : bars) {
            try {
                LocalDate date = LocalDate.parse((String) bar.get("date"));
                BigDecimal close = toBigDecimal(bar.get("close"));
                BigDecimal high = toBigDecimal(bar.get("high"));
                BigDecimal low = toBigDecimal(bar.get("low"));

                BigDecimal volume = toBigDecimal(bar.get("volume"));

                if (close == null || close.compareTo(BigDecimal.ZERO) == 0) {
                    skippedCount++;
                    continue;
                }
                if (volume == null || volume.compareTo(BigDecimal.ZERO) == 0) {
                    skippedCount++;
                    continue;
                }

                BigDecimal open = toBigDecimal(bar.get("open"));
                Optional<MarketData> existing = marketDataRepository.findByCompanyCodeAndTradeDate(companyCode, date);
                if (existing.isPresent()) {
                    MarketData md = existing.get();
                    md.setOpen(open);
                    md.setLastTrade(close);
                    md.setHigh(high);
                    md.setLow(low);
                    md.setVolume(volume);
                    md.setUpdatedAt(LocalDateTime.now());
                    marketDataRepository.save(md);
                    updatedCount++;
                } else {
                    BigDecimal change = (open != null && open.compareTo(BigDecimal.ZERO) != 0)
                            ? close.subtract(open) : BigDecimal.ZERO;
                    BigDecimal changePct = (open != null && open.compareTo(BigDecimal.ZERO) != 0)
                            ? change.divide(open, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                            : BigDecimal.ZERO;

                    marketDataRepository.save(MarketData.builder()
                            .companyCode(companyCode)
                            .companyName(companyName)
                            .open(open)
                            .lastTrade(close)
                            .high(high)
                            .low(low)
                            .volume(volume)
                            .change(change)
                            .changePercent(changePct)
                            .tradeDate(date)
                            .updatedAt(LocalDateTime.now())
                            .build());
                    newCount++;
                }
            } catch (Exception e) {
                log.error("Failed to save bar for {} date={}: {}", companyCode, bar.get("date"), e.getMessage());
            }
        }
        log.info("Save complete for {}: {} new, {} updated, {} skipped (zero close)", companyCode, newCount, updatedCount, skippedCount);
        return newCount;
    }

    /**
     * Save a single bar for a company.
     */
    public Map<String, Object> saveBar(String companyCode, Map<String, Object> bar) {
        Optional<Company> company = companyRepository.findByCode(companyCode);
        String companyName = company.map(Company::getName).orElse(companyCode);

        LocalDate date = LocalDate.parse((String) bar.get("date"));
        BigDecimal close = toBigDecimal(bar.get("close"));
        BigDecimal high = toBigDecimal(bar.get("high"));
        BigDecimal low = toBigDecimal(bar.get("low"));
        BigDecimal open = toBigDecimal(bar.get("open"));
        BigDecimal volume = toBigDecimal(bar.get("volume"));

        Optional<MarketData> existing = marketDataRepository.findByCompanyCodeAndTradeDate(companyCode, date);
        boolean isNew;
        if (existing.isPresent()) {
            MarketData md = existing.get();
            md.setOpen(open);
            md.setLastTrade(close);
            md.setHigh(high);
            md.setLow(low);
            md.setVolume(volume);
            md.setUpdatedAt(LocalDateTime.now());
            marketDataRepository.save(md);
            isNew = false;
        } else {
            BigDecimal change = (open != null && open.compareTo(BigDecimal.ZERO) != 0)
                    ? close.subtract(open) : BigDecimal.ZERO;
            BigDecimal changePct = (open != null && open.compareTo(BigDecimal.ZERO) != 0)
                    ? change.divide(open, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            marketDataRepository.save(MarketData.builder()
                    .companyCode(companyCode)
                    .companyName(companyName)
                    .open(open)
                    .lastTrade(close)
                    .high(high)
                    .low(low)
                    .volume(volume)
                    .change(change)
                    .changePercent(changePct)
                    .tradeDate(date)
                    .updatedAt(LocalDateTime.now())
                    .build());
            isNew = true;
        }
        log.info("Saved single bar for {} date={} ({})", companyCode, date, isNew ? "new" : "updated");
        return Map.of("date", date.toString(), "status", isNew ? "new" : "updated");
    }

    private double toNumber(Object val) {
        if (val == null) return 0;
        return ((Number) val).doubleValue();
    }

    private BigDecimal toBigDecimal(Object val) {
        if (val == null) return null;
        return BigDecimal.valueOf(((Number) val).doubleValue());
    }
}

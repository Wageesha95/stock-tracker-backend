package com.personal.stocktracker.service;

import com.personal.stocktracker.config.ScraperConfig;
import com.personal.stocktracker.document.DividendPayout;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.DividendPayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.util.logging.Level;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DividendScraperService {

    private final DividendPayoutRepository dividendPayoutRepository;
    private final CompanyRepository companyRepository;
    private final ScraperConfig scraperConfig;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("MMM dd, yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy")
    );

    public List<DividendPayout> scrapeCompanyPreview(String companyCode) {
        WebDriver driver = createDriver();
        try {
            return scrapeCompanyPage(driver, companyCode);
        } finally {
            driver.quit();
        }
    }

    public String scrapeDebugPageSource(String companyCode) {
        WebDriver driver = createDriver();
        try {
            String url = scraperConfig.getBaseUrl() + companyCode + scraperConfig.getUrlSuffix();
            log.info("Debug: loading {} ", url);
            driver.get(url);
            try { Thread.sleep(10000); } catch (InterruptedException ignored) {}
            dismissOverlays(driver);
            String pageSource = driver.getPageSource();
            // Save to file for debugging
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of("debug-page.html"), pageSource);
                log.info("Debug page saved to debug-page.html");
            } catch (Exception e) {
                log.error("Failed to save debug page: {}", e.getMessage());
            }
            return pageSource;
        } finally {
            driver.quit();
        }
    }

    public int saveDividendPayouts(String companyCode, List<DividendPayout> payouts) {
        return savePayouts(companyCode, payouts);
    }

    public Map<String, Object> scrapeSingleCompany(String companyCode) {
        List<DividendPayout> payouts = scrapeCompanyPreview(companyCode);
        int saved = savePayouts(companyCode, payouts);
        return Map.of(
                "companyCode", companyCode,
                "newRecords", saved,
                "totalScraped", payouts.size()
        );
    }

    public Map<String, Object> scrapeAllCompanies() {
        var companies = companyRepository.findAll();
        log.info("Starting dividend scraping for {} companies", companies.size());

        WebDriver driver = createDriver();
        int succeeded = 0;
        int failed = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        try {
            for (int i = 0; i < companies.size(); i++) {
                String code = companies.get(i).getCode();
                try {
                    List<DividendPayout> payouts = scrapeCompanyPage(driver, code);
                    int saved = savePayouts(code, payouts);
                    details.add(Map.of("companyCode", code, "newRecords", saved, "totalScraped", payouts.size()));
                    succeeded++;
                } catch (WebDriverException wde) {
                    log.error("WebDriver error for {}. Recreating driver.", code, wde);
                    failed++;
                    details.add(Map.of("companyCode", code, "error", wde.getMessage()));
                    try { driver.quit(); } catch (Exception ignored) {}
                    driver = createDriver();
                } catch (Exception e) {
                    log.error("Failed to scrape {}: {}", code, e.getMessage());
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

        log.info("Scraping complete: {} succeeded, {} failed out of {}", succeeded, failed, companies.size());
        return Map.of(
                "totalCompanies", companies.size(),
                "succeeded", succeeded,
                "failed", failed,
                "details", details
        );
    }

    private WebDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        if (scraperConfig.isHeadless()) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--window-size=1920,8000",
                "--disable-blink-features=AutomationControlled",
                "--blink-settings=imagesEnabled=false",
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
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

    private List<DividendPayout> scrapeCompanyPage(WebDriver driver, String companyCode) {
        String url = scraperConfig.getBaseUrl() + companyCode + scraperConfig.getUrlSuffix();
        log.info("Scraping dividends for {} from {}", companyCode, url);

        // Inject WebSocket interceptor BEFORE page load using CDP
        if (driver instanceof ChromeDriver chromeDriver) {
            chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", Map.of("source", """
                window.__fullWsData = '';
                var OrigWS = window.WebSocket;
                window.WebSocket = function(url, protocols) {
                    var ws = protocols ? new OrigWS(url, protocols) : new OrigWS(url);
                    ws.addEventListener('message', function(e) {
                        var data = typeof e.data === 'string' ? e.data : '';
                        // Capture the largest WS message (which contains all financial data)
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

        // Wait for the dividend section to appear
        WebDriverWait wait = new WebDriverWait(driver,
                Duration.ofSeconds(scraperConfig.getElementWaitTimeoutSec()));
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.xpath("//*[contains(text(), 'Dividend payout history') or contains(text(), 'Ex-dividend date')]")
            ));
        } catch (TimeoutException e) {
            log.warn("Dividend section did not appear for {}", companyCode);
            return Collections.emptyList();
        }

        // Wait for initial rows to render
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        dismissOverlays(driver);

        // Read captured WebSocket messages containing dividend data
        JavascriptExecutor jsExec = (JavascriptExecutor) driver;
        // Extract dividend history arrays directly from the WebSocket data
        @SuppressWarnings("unchecked")
        Map<String, Object> wsResult = (Map<String, Object>) jsExec.executeScript("""
            if (!window.__fullWsData) return {error: 'no data'};
            var raw = window.__fullWsData;

            function extractArray(key) {
                var idx = raw.indexOf('"' + key + '"');
                if (idx < 0) return null;
                var arrStart = raw.indexOf('[', idx);
                if (arrStart < 0 || arrStart - idx > 50) return null;
                var arrEnd = raw.indexOf(']', arrStart);
                if (arrEnd < 0) return null;
                try { return JSON.parse(raw.substring(arrStart, arrEnd + 1)); }
                catch(e) { return null; }
            }

            var amounts = extractArray('dividend_amount_h');
            var exDates = extractArray('dividend_ex_date_h');
            var payDates = extractArray('dividend_payment_date_h');
            var recDates = extractArray('dividend_record_date_h');
            // Try multiple possible key names for frequency
            var freqs = extractArray('dividend_frequency_h')
                || extractArray('dividends_frequency_h')
                || extractArray('dividend_payout_frequency_h')
                || extractArray('frequency_h');

            // If no array found, check for a single frequency value
            var singleFreq = null;
            if (!freqs) {
                var fIdx = raw.indexOf('"dividend_frequency"');
                if (fIdx < 0) fIdx = raw.indexOf('"frequency"');
                if (fIdx >= 0) {
                    var fMatch = raw.substring(fIdx, fIdx + 100).match(new RegExp(':\\s*"?([^",}\\]]+)"?'));
                    if (fMatch) singleFreq = fMatch[1];
                }
            }

            if (!amounts) return {error: 'dividend_amount_h not found'};

            // Log what we found/didn't find for debugging
            var debug = {
                amounts: amounts ? amounts.length : 'NOT FOUND',
                exDates: exDates ? exDates.length : 'NOT FOUND',
                payDates: payDates ? payDates.length : 'NOT FOUND',
                recDates: recDates ? recDates.length : 'NOT FOUND',
                freqs: freqs ? freqs.length : 'NOT FOUND',
                singleFreq: singleFreq
            };

            // Frequency is a company-level value, not per-record
            // last_report_frequency: 4=Quarterly, 2=Semi-Annual, 1=Annual
            if (!freqs && !singleFreq) {
                var lrfIdx = raw.indexOf('"last_report_frequency"');
                if (lrfIdx >= 0) {
                    var lrfMatch = raw.substring(lrfIdx, lrfIdx + 40).match(new RegExp(':\\s*(\\d+)'));
                    if (lrfMatch) {
                        var code = parseInt(lrfMatch[1]);
                        var freqMap = {1: 'Annual', 2: 'Semi-Annual', 4: 'Quarterly', 12: 'Monthly'};
                        singleFreq = freqMap[code] || ('Frequency-' + code);
                    }
                }
            }

            var rows = [];
            for (var i = 0; i < amounts.length; i++) {
                rows.push({
                    amount: amounts[i],
                    exDate: exDates && exDates[i] ? exDates[i] : null,
                    payDate: payDates && payDates[i] ? payDates[i] : null,
                    recDate: recDates && recDates[i] ? recDates[i] : null,
                    freq: freqs && freqs[i] ? freqs[i] : singleFreq
                });
            }
            return {count: rows.length, rows: rows, debug: debug};
        """);
        log.info("WS extraction for {}: count={}, debug={}", companyCode,
                wsResult != null ? wsResult.get("count") : "null",
                wsResult != null ? wsResult.get("debug") : "null");

        if (wsResult != null && wsResult.containsKey("rows")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) wsResult.get("rows");
            if (rows != null && !rows.isEmpty()) {
                List<DividendPayout> payouts = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    try {
                        BigDecimal amount = row.get("amount") != null ?
                                new BigDecimal(row.get("amount").toString()) : null;
                        LocalDate exDate = parseTimestamp(row.get("exDate"));
                        LocalDate payDate = parseTimestamp(row.get("payDate"));
                        LocalDate recDate = parseTimestamp(row.get("recDate"));
                        if (exDate != null) {
                            payouts.add(DividendPayout.builder()
                                    .companyCode(companyCode)
                                    .exDividendDate(exDate)
                                    .paymentDate(payDate)
                                    .amountPerShare(amount)
                                    .scrapedAt(LocalDateTime.now())
                                    .build());
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse WS row for {}: {} - {}", companyCode, row, e.getMessage());
                    }
                }
                log.info("Extracted {} dividend records from WebSocket for {}", payouts.size(), companyCode);
                return payouts;
            }
        }

        log.info("WS extraction failed, falling back to DOM scraping for {}", companyCode);
        return wheelScrollAndCollect(driver, companyCode);
    }

    @SuppressWarnings("unchecked")
    private List<DividendPayout> wheelScrollAndCollect(WebDriver driver, String companyCode) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Map<String, List<String>> allRows = new LinkedHashMap<>();

        // First collect initial visible rows
        collectVisibleRows(js, allRows);
        log.info("Initial rows for {}: {}", companyCode, allRows.size());

        // Simulate wheel events on the wrapper to trigger virtual scroll
        for (int i = 0; i < 50; i++) {
            int prevSize = allRows.size();

            js.executeScript("""
                var headings = document.querySelectorAll('div[class*="heading-"]');
                for (var h of headings) {
                    if (h.textContent.trim() === 'Dividend payout history') {
                        var section = h.nextElementSibling;
                        if (!section) return;
                        var wrapper = section.querySelector('div[class*="wrapper-"]');
                        var target = wrapper || section;
                        // Dispatch wheel event to scroll down
                        target.dispatchEvent(new WheelEvent('wheel', {
                            deltaY: 120, deltaMode: 0, bubbles: true, cancelable: true
                        }));
                        break;
                    }
                }
            """);

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            collectVisibleRows(js, allRows);

            if (allRows.size() == prevSize) {
                // No new rows after wheel - try a few more times then stop
                if (i > 0) {
                    // Give it 2 more attempts
                    boolean found = false;
                    for (int j = 0; j < 2; j++) {
                        js.executeScript("""
                            var headings = document.querySelectorAll('div[class*="heading-"]');
                            for (var h of headings) {
                                if (h.textContent.trim() === 'Dividend payout history') {
                                    var section = h.nextElementSibling;
                                    if (!section) return;
                                    var wrapper = section.querySelector('div[class*="wrapper-"]');
                                    (wrapper || section).dispatchEvent(new WheelEvent('wheel', {
                                        deltaY: 120, deltaMode: 0, bubbles: true, cancelable: true
                                    }));
                                    break;
                                }
                            }
                        """);
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        collectVisibleRows(js, allRows);
                        if (allRows.size() > prevSize) { found = true; break; }
                    }
                    if (!found) {
                        log.info("No more rows after {} wheel events, total: {}", i + 3, allRows.size());
                        break;
                    }
                }
            }
        }

        log.info("Collected {} unique dividend rows for {} via wheel scroll", allRows.size(), companyCode);

        List<DividendPayout> payouts = new ArrayList<>();
        for (List<String> cells : allRows.values()) {
            try {
                DividendPayout payout = parseJsRow(cells, companyCode);
                if (payout != null) payouts.add(payout);
            } catch (Exception e) {
                log.warn("Failed to parse row for {}: {} - {}", companyCode, cells, e.getMessage());
            }
        }
        return payouts;
    }

    @SuppressWarnings("unchecked")
    private void collectVisibleRows(JavascriptExecutor js, Map<String, List<String>> allRows) {
        List<List<String>> visible = (List<List<String>>) js.executeScript("""
            var results = [];
            var headings = document.querySelectorAll('div[class*="heading-"]');
            for (var h of headings) {
                if (h.textContent.trim() === 'Dividend payout history') {
                    var section = h.nextElementSibling;
                    if (!section) return results;
                    var rows = section.querySelectorAll('div[data-name]');
                    for (var row of rows) {
                        var exDate = row.getAttribute('data-name');
                        var values = row.querySelectorAll('div[class*="value-"]');
                        var cells = [exDate];
                        for (var v of values) cells.push(v.textContent.trim());
                        if (cells.length >= 4) results.push(cells);
                    }
                    break;
                }
            }
            return results;
        """);
        if (visible != null) {
            for (List<String> row : visible) {
                allRows.putIfAbsent(row.get(0), row);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<DividendPayout> scrollAndCollectAllRows(WebDriver driver, String companyCode) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        // Collect all unique rows by scrolling inside the dividend table.
        // TradingView virtualizes the list, so only visible rows are in the DOM at a time.
        // We scroll incrementally and collect rows on each pass.
        Map<String, List<String>> allRows = new LinkedHashMap<>();

        for (int i = 0; i < 30; i++) {
            // Read current visible rows
            @SuppressWarnings("unchecked")
            List<List<String>> visibleRows = (List<List<String>>) js.executeScript("""
                var results = [];
                var headings = document.querySelectorAll('div[class*="heading-"]');
                for (var h of headings) {
                    if (h.textContent.trim() === 'Dividend payout history') {
                        var section = h.nextElementSibling;
                        if (!section) return results;
                        var rows = section.querySelectorAll('div[data-name]');
                        for (var row of rows) {
                            var exDate = row.getAttribute('data-name');
                            var values = row.querySelectorAll('div[class*="value-"]');
                            var cells = [exDate];
                            for (var v of values) cells.push(v.textContent.trim());
                            if (cells.length >= 4) results.push(cells);
                        }
                        break;
                    }
                }
                return results;
            """);

            if (visibleRows != null) {
                for (List<String> row : visibleRows) {
                    String key = row.get(0); // ex-dividend date as key
                    allRows.putIfAbsent(key, row);
                }
            }

            // Scroll to load more rows - try every scrollable element in the hierarchy
            @SuppressWarnings("unchecked")
            Map<String, Object> scrollInfo = (Map<String, Object>) js.executeScript("""
                var headings = document.querySelectorAll('div[class*="heading-"]');
                for (var h of headings) {
                    if (h.textContent.trim() === 'Dividend payout history') {
                        var section = h.nextElementSibling;
                        if (!section) return false;

                        // Try scrolling every ancestor and child that might be scrollable
                        var scrolled = false;
                        var targets = [section];
                        // Add all children with scroll-related class names
                        var all = section.querySelectorAll('div[class*="scroll"], div[class*="wrapper"], div[class*="container"], div[class*="shadow"]');
                        for (var el of all) targets.push(el);
                        // Add ancestors up to body
                        var parent = section.parentElement;
                        while (parent && parent !== document.body) {
                            targets.push(parent);
                            parent = parent.parentElement;
                        }

                        var scrollDebug = [];
                        for (var t of targets) {
                            var info = {
                                tag: t.tagName,
                                cls: (t.className||'').substring(0, 40),
                                scrollH: t.scrollHeight,
                                clientH: t.clientHeight,
                                scrollTop: t.scrollTop,
                                overflow: getComputedStyle(t).overflow,
                                overflowY: getComputedStyle(t).overflowY
                            };
                            if (t.scrollHeight > t.clientHeight) {
                                var prev = t.scrollTop;
                                t.scrollTop += 300;
                                info.scrolledTo = t.scrollTop;
                                info.didScroll = t.scrollTop > prev;
                                if (t.scrollTop > prev) scrolled = true;
                            }
                            scrollDebug.push(info);
                        }

                        var rows = section.querySelectorAll('div[data-name]');
                        if (rows.length > 0) {
                            rows[rows.length - 1].scrollIntoView({block: 'end'});
                        }

                        return {scrolled: scrolled, targets: scrollDebug, rowCount: rows.length};
                    }
                }
                return {scrolled: false, targets: [], rowCount: 0};
            """);

            log.info("Scroll attempt {}: {}", i + 1, scrollInfo);

            Boolean hasMore = scrollInfo != null && Boolean.TRUE.equals(scrollInfo.get("scrolled"));

            if (!Boolean.TRUE.equals(hasMore)) {
                log.debug("Scroll reached bottom after {} iterations, collected {} rows", i + 1, allRows.size());
                break;
            }

            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
        }

        log.info("Collected {} unique dividend rows for {} via scroll", allRows.size(), companyCode);

        // Parse collected rows
        List<DividendPayout> payouts = new ArrayList<>();
        for (List<String> cells : allRows.values()) {
            try {
                DividendPayout payout = parseJsRow(cells, companyCode);
                if (payout != null) payouts.add(payout);
            } catch (Exception e) {
                log.warn("Failed to parse row for {}: {} - {}", companyCode, cells, e.getMessage());
            }
        }
        return payouts;
    }

    @SuppressWarnings("unchecked")
    private List<DividendPayout> extractDividendsViaJS(WebDriver driver, String companyCode) {
        // TradingView DOM structure (from debug HTML):
        //   <div class="heading-...">Dividend payout history</div>
        //   <div class="container-...">  (next sibling)
        //     ...wrapper divs...
        //       <div class="container-... gridLayout-...">  (the grid)
        //         <div class="container-OWKkVLyj">  (header row)
        //         <div data-name="Mar 10, 2026" class="container-C9MdAMrq">  (data row)
        //           <div class="value-...">Record date</div>
        //           <div class="value-...">Payment date</div>
        //           <div class="value-...">7.000</div>
        //           <div class="value-...">Quarterly</div>
        String script = """
            var debug = {headingsFound: 0, sectionFound: false, sectionTag: '', totalDataNameDivs: 0, rows: []};
            var results = [];

            var headings = document.querySelectorAll('div[class*="heading-"]');
            debug.headingsFound = headings.length;
            var section = null;
            for (var h of headings) {
                if (h.textContent.trim() === 'Dividend payout history') {
                    section = h.nextElementSibling;
                    break;
                }
            }
            if (!section) return {debug: debug, results: results};
            debug.sectionFound = true;
            debug.sectionTag = section.tagName + '.' + section.className.substring(0, 30);

            var rows = section.querySelectorAll('div[data-name]');
            debug.totalDataNameDivs = rows.length;
            for (var row of rows) {
                var exDate = row.getAttribute('data-name');
                var values = row.querySelectorAll('div[class*="value-"]');
                var cells = [exDate];
                for (var v of values) {
                    cells.push(v.textContent.trim());
                }
                debug.rows.push({date: exDate, cellCount: cells.length, cells: cells});
                if (cells.length >= 4) {
                    results.push(cells);
                }
            }
            return {debug: debug, results: results};
            """;

        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object rawResult = js.executeScript(script);
        log.info("JS extraction raw result for {}: {}", companyCode, rawResult);

        // Extract results from the debug wrapper
        Object result = null;
        if (rawResult instanceof Map<?, ?> map) {
            result = map.get("results");
        } else if (rawResult instanceof List<?>) {
            result = rawResult;
        }

        List<DividendPayout> payouts = new ArrayList<>();
        if (result instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof List<?> cells) {
                    try {
                        DividendPayout payout = parseJsRow(cells, companyCode);
                        if (payout != null) {
                            payouts.add(payout);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse JS row for {}: {} - {}", companyCode, cells, e.getMessage());
                    }
                }
            }
        }

        log.info("Extracted {} dividend records for {} via JS", payouts.size(), companyCode);
        return payouts;
    }

    private DividendPayout parseJsRow(List<?> cells, String companyCode) {
        // TradingView columns: Ex-dividend date, Record date, Payment date, Amount, Frequency
        if (cells.size() < 4) return null;

        String exDateText = cells.get(0).toString().trim();
        if (exDateText.isBlank() || exDateText.equals("\u2014") || exDateText.equals("-")) {
            return null;
        }

        String recordDateText = cells.size() > 1 ? cells.get(1).toString().trim() : null;
        String paymentDateText = cells.size() > 2 ? cells.get(2).toString().trim() : null;
        String amountText = cells.size() > 3 ? cells.get(3).toString().trim() : null;
        String frequency = cells.size() > 4 ? cells.get(4).toString().trim() : null;

        return DividendPayout.builder()
                .companyCode(companyCode)
                .exDividendDate(parseDate(exDateText))
                .paymentDate(parseDateSafe(paymentDateText))
                .amountPerShare(parseBigDecimal(amountText))
                .scrapedAt(LocalDateTime.now())
                .build();
    }

    private void dismissOverlays(WebDriver driver) {
        // Cookie consent
        tryClick(driver, By.cssSelector(
                "button[class*='acceptAll'], button[class*='accept-all'], " +
                "button[aria-label*='Accept'], button[aria-label*='accept']"
        ));
        // Close modals
        tryClick(driver, By.cssSelector(
                "button[aria-label='Close'], div[data-dialog-name] button[class*='close']"
        ));
        // "Got it" buttons
        tryClick(driver, By.xpath(
                "//button[contains(text(), 'Got it') or contains(text(), 'I understand')]"
        ));
    }

    private void tryClick(WebDriver driver, By locator) {
        try {
            List<WebElement> elements = driver.findElements(locator);
            for (WebElement el : elements) {
                if (el.isDisplayed()) {
                    el.click();
                    Thread.sleep(500);
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleLoadMore(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // TradingView uses virtualized rendering - only visible rows exist in the DOM.
        // Scroll inside the dividend table's scroll container to load all rows.
        for (int i = 0; i < 30; i++) {
            try {
                Long prevCount = (Long) js.executeScript("""
                    var headings = document.querySelectorAll('div[class*="heading-"]');
                    for (var h of headings) {
                        if (h.textContent.trim() === 'Dividend payout history') {
                            var section = h.nextElementSibling;
                            if (!section) return 0;

                            // Find the scrollable wrapper inside the section
                            var wrapper = section.querySelector('div[class*="wrapper-"]');
                            if (wrapper) {
                                wrapper.scrollTop = wrapper.scrollHeight;
                            }
                            // Also try scrolling the section itself
                            section.scrollTop = section.scrollHeight;

                            return section.querySelectorAll('div[data-name]').length;
                        }
                    }
                    return 0;
                """);

                Thread.sleep(1500);

                Long newCount = (Long) js.executeScript("""
                    var headings = document.querySelectorAll('div[class*="heading-"]');
                    for (var h of headings) {
                        if (h.textContent.trim() === 'Dividend payout history') {
                            var section = h.nextElementSibling;
                            return section ? section.querySelectorAll('div[data-name]').length : 0;
                        }
                    }
                    return 0;
                """);

                log.debug("Scroll attempt {}: {} -> {} rows", i + 1, prevCount, newCount);
                if (newCount != null && prevCount != null && newCount.equals(prevCount)) {
                    break; // No new rows loaded
                }
            } catch (Exception e) {
                log.debug("Scroll failed: {}", e.getMessage());
                break;
            }
        }
    }

    private int savePayouts(String companyCode, List<DividendPayout> payouts) {
        int newCount = 0;
        for (DividendPayout payout : payouts) {
            Optional<DividendPayout> existing = dividendPayoutRepository
                    .findByCompanyCodeAndExDividendDate(companyCode, payout.getExDividendDate());
            if (existing.isPresent()) {
                DividendPayout e = existing.get();
                e.setAmountPerShare(payout.getAmountPerShare());
                e.setPaymentDate(payout.getPaymentDate());
                e.setScrapedAt(payout.getScrapedAt());
                dividendPayoutRepository.save(e);
            } else {
                dividendPayoutRepository.save(payout);
                newCount++;
            }
        }
        log.info("Saved {} new dividend payouts for {}", newCount, companyCode);
        return newCount;
    }

    private String getCellText(List<WebElement> cells, Integer index) {
        if (index == null || index < 0 || index >= cells.size()) return null;
        return cells.get(index).getText().trim();
    }

    private LocalDate parseTimestamp(Object value) {
        if (value == null) return null;
        try {
            long ts = ((Number) value).longValue();
            if (ts <= 0) return null;
            return java.time.Instant.ofEpochSecond(ts).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        text = text.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(text, fmt); } catch (Exception ignored) {}
        }
        throw new RuntimeException("Cannot parse date: " + text);
    }

    private LocalDate parseDateSafe(String text) {
        try { return parseDate(text); } catch (Exception e) { return null; }
    }

    private BigDecimal parseBigDecimal(String text) {
        if (text == null || text.isBlank() || text.equals("\u2014") || text.equals("-")) return null;
        try {
            return new BigDecimal(text.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) { return null; }
    }

    private BigDecimal parseYield(String text) {
        if (text == null || text.isBlank() || text.equals("\u2014") || text.equals("-")) return null;
        String cleaned = text.replace("%", "").trim();
        try {
            return new BigDecimal(cleaned).divide(BigDecimal.valueOf(100));
        } catch (Exception e) { return null; }
    }
}

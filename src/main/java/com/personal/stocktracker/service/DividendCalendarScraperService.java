package com.personal.stocktracker.service;

import com.personal.stocktracker.document.DividendPayout;
import com.personal.stocktracker.repository.DividendPayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DividendCalendarScraperService {

    private final DividendPayoutRepository dividendPayoutRepository;

    private static final String API_URL = "https://api.stockdecision.com/api/valid-company-data/get-latest-dividends/";
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd MMM yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    );

    /**
     * Fetch dividend calendar from stockdecision.com API.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> scrapeCalendar(String dateStart, String dateEnd) {
        log.info("Fetching dividend calendar: {} to {}", dateStart, dateEnd);

        RestTemplate rest = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Accept-Language", "en-US,en;q=0.9");
        headers.set("Origin", "https://www.stockdecision.com");
        headers.set("Referer", "https://www.stockdecision.com/");
        headers.set("Sec-Fetch-Dest", "empty");
        headers.set("Sec-Fetch-Mode", "cors");
        headers.set("Sec-Fetch-Site", "same-site");
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 OPR/129.0.0.0");

        String url = API_URL + "?limit=500&type=all&include_scrip=false"
                + "&date_start=" + dateStart + "&date_end=" + dateEnd
                + "&date_field=xd_date";

        log.info("Fetching: {}", url);

        List<Map<String, Object>> allRecords;
        try {
            ResponseEntity<List> response = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), List.class);
            allRecords = response.getBody() != null ? response.getBody() : new ArrayList<>();
            log.info("Fetched {} records", allRecords.size());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("HTTP error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("API returned " + e.getStatusCode() + ": " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Failed to fetch: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch dividend calendar: " + e.getMessage());
        }

        // Deduplicate by ticker + xd_date
        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        for (Map<String, Object> r : allRecords) {
            String key = r.get("ticker_symbol") + "|" + r.get("xd_date");
            deduped.putIfAbsent(key, r);
        }
        log.info("Total records fetched: {}, after dedup: {}", allRecords.size(), deduped.size());
        return new ArrayList<>(deduped.values());
    }

    /**
     * Scrape and save: match records to existing DividendPayout by companyCode + exDividendDate,
     * update dividendType and announcementDate. Create new records if not found.
     */
    public Map<String, Object> saveRecords(List<Map<String, Object>> records) {
        int created = 0;
        int updated = 0;
        int skipped = 0;

        for (Map<String, Object> record : records) {
            try {
                String ticker = (String) record.get("ticker_symbol");
                if (ticker == null || ticker.isBlank()) { skipped++; continue; }

                String companyCode = ticker.endsWith("0000") ? ticker.substring(0, ticker.length() - 4) : ticker;

                LocalDate exDate = parseDate((String) record.get("xd_date"));
                if (exDate == null) { skipped++; continue; }

                String dividendType = (String) record.get("dividend_type");
                if (dividendType == null || dividendType.isBlank()) {
                    dividendType = (String) record.get("type");
                }
                LocalDate announcementDate = parseDate((String) record.get("announcement_date"));
                LocalDate payDate = parseDate((String) record.get("payment_date"));

                BigDecimal amount = parseBigDecimal(record.get("dividend_per_share"));
                BigDecimal priceXd = parseBigDecimal(record.get("market_price_xd"));
                BigDecimal priceAnnounce = parseBigDecimal(record.get("market_price_announce_date"));

                Optional<DividendPayout> existing = dividendPayoutRepository
                        .findByCompanyCodeAndExDividendDate(companyCode, exDate);

                if (existing.isPresent()) {
                    DividendPayout payout = existing.get();
                    boolean changed = false;

                    if (dividendType != null && !dividendType.isBlank() &&
                            (payout.getDividendType() == null || payout.getDividendType().isBlank())) {
                        payout.setDividendType(dividendType);
                        changed = true;
                    }
                    if (announcementDate != null && payout.getAnnouncementDate() == null) {
                        payout.setAnnouncementDate(announcementDate);
                        changed = true;
                    }
                    if (payDate != null && payout.getPaymentDate() == null) {
                        payout.setPaymentDate(payDate);
                        changed = true;
                    }
                    if (amount != null && payout.getAmountPerShare() == null) {
                        payout.setAmountPerShare(amount);
                        changed = true;
                    }
                    if (priceXd != null && payout.getPriceOnXdDate() == null) {
                        payout.setPriceOnXdDate(priceXd);
                        changed = true;
                    }
                    if (priceAnnounce != null && payout.getPriceOnAnnouncementDate() == null) {
                        payout.setPriceOnAnnouncementDate(priceAnnounce);
                        changed = true;
                    }

                    if (changed) {
                        dividendPayoutRepository.save(payout);
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    dividendPayoutRepository.save(DividendPayout.builder()
                            .companyCode(companyCode)
                            .exDividendDate(exDate)
                            .amountPerShare(amount)
                            .paymentDate(payDate)
                            .announcementDate(announcementDate)
                            .dividendType(dividendType)
                            .priceOnXdDate(priceXd)
                            .priceOnAnnouncementDate(priceAnnounce)
                            .scrapedAt(LocalDateTime.now())
                            .build());
                    created++;
                }
            } catch (Exception e) {
                log.warn("Failed to process record: {} - {}", record, e.getMessage());
                skipped++;
            }
        }

        log.info("Dividend calendar: {} created, {} updated, {} skipped out of {} records",
                created, updated, skipped, records.size());
        return Map.of("totalScraped", records.size(), "created", created, "updated", updated, "skipped", skipped);
    }

    private BigDecimal parseBigDecimal(Object val) {
        if (val == null) return null;
        String s = val.toString().trim().replaceAll("[^0-9.\\-]", "");
        if (s.isBlank() || s.equals("N/A")) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank() || text.equals("-") || text.equals("N/A")) return null;
        // Normalize case: "12 DEC 2025" -> "12 Dec 2025"
        text = text.trim().toLowerCase();
        String[] parts = text.split("\\s+");
        if (parts.length == 3 && parts[1].length() == 3) {
            parts[1] = parts[1].substring(0, 1).toUpperCase() + parts[1].substring(1);
            text = String.join(" ", parts);
        }
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(text, fmt); } catch (Exception ignored) {}
        }
        log.warn("Could not parse date: {}", text);
        return null;
    }
}

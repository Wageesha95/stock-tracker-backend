package com.personal.stocktracker.service;

import com.personal.stocktracker.document.Company;
import com.personal.stocktracker.document.MarketData;
import com.personal.stocktracker.repository.CompanyRepository;
import com.personal.stocktracker.repository.MarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataService {

    private final MarketDataRepository marketDataRepository;
    private final CompanyRepository companyRepository;
    private final MongoTemplate mongoTemplate;

    public int parseAndSaveCsv(MultipartFile file, LocalDate tradeDate) {
        int count = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean headerSkipped = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }

                try {
                    String[] fields = line.split(",");
                    if (fields.length < 11) {
                        log.warn("Skipping line with insufficient fields: {}", line);
                        continue;
                    }

                    String companyName = fields[0].trim();
                    String symbol = fields[1].trim();
                    String companyCode = symbol.substring(0, symbol.length() - 4);

                    // Auto-register company if not exists
                    if (!companyRepository.existsByCode(companyCode)) {
                        Company company = Company.builder()
                                .code(companyCode)
                                .name(companyName)
                                .createdAt(LocalDateTime.now())
                                .build();
                        companyRepository.save(company);
                    }

                    BigDecimal high = parseBigDecimal(fields[6]);
                    BigDecimal low = parseBigDecimal(fields[7]);
                    BigDecimal lastTrade = parseBigDecimal(fields[8]);
                    BigDecimal change = parseBigDecimal(fields[9]);
                    BigDecimal changePercent = parseBigDecimal(fields[10]);

                    Optional<MarketData> existing = marketDataRepository.findByCompanyCodeAndTradeDate(companyCode, tradeDate);

                    MarketData marketData;
                    if (existing.isPresent()) {
                        marketData = existing.get();
                    } else {
                        marketData = new MarketData();
                        marketData.setCompanyCode(companyCode);
                    }

                    marketData.setCompanyName(companyName);
                    marketData.setHigh(high);
                    marketData.setLow(low);
                    marketData.setLastTrade(lastTrade);
                    marketData.setChange(change);
                    marketData.setChangePercent(changePercent);
                    marketData.setTradeDate(tradeDate);
                    marketData.setUpdatedAt(LocalDateTime.now());

                    marketDataRepository.save(marketData);
                    count++;
                } catch (Exception e) {
                    log.warn("Skipping line due to parse error: {} — {}", line, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse CSV file: " + e.getMessage(), e);
        }

        log.info("Parsed and saved {} market data records", count);
        return count;
    }

    public List<Map<String, Object>> previewCsv(MultipartFile file) {
        List<Map<String, Object>> preview = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            String line;
            boolean headerSkipped = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!headerSkipped) { headerSkipped = true; continue; }

                try {
                    String[] fields = line.split(",");
                    if (fields.length < 11) continue;

                    String companyName = fields[0].trim();
                    String symbol = fields[1].trim();
                    String companyCode = symbol.substring(0, symbol.length() - 4);

                    // Show all companies in preview

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("companyCode", companyCode);
                    item.put("companyName", companyName);
                    item.put("lastTrade", parseBigDecimal(fields[8]));
                    item.put("change", parseBigDecimal(fields[9]));
                    item.put("changePercent", parseBigDecimal(fields[10]));
                    preview.add(item);
                } catch (Exception e) {
                    log.warn("Skipping preview line: {} — {}", line, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse CSV file: " + e.getMessage(), e);
        }

        return preview;
    }

    public List<MarketData> getAll() {
        return getLatestPerCompany();
    }

    /**
     * Returns only the latest MarketData record per company using MongoDB aggregation.
     * Much faster than findAll() when historical data exists.
     */
    public List<MarketData> getLatestPerCompany() {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.sort(Sort.Direction.DESC, "tradeDate"),
                Aggregation.group("companyCode").first("$$ROOT").as("doc"),
                Aggregation.replaceRoot("doc")
        );
        AggregationResults<MarketData> results = mongoTemplate.aggregate(agg, "market_data", MarketData.class);
        return results.getMappedResults();
    }

    public MarketData getByCompanyCode(String code) {
        return marketDataRepository.findFirstByCompanyCodeOrderByTradeDateDesc(code)
                .orElseThrow(() -> new RuntimeException("Market data not found for company code: " + code));
    }

    private BigDecimal parseBigDecimal(String value) {
        String cleaned = value.trim().replace(",", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(cleaned);
    }
}

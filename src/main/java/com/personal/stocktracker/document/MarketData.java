package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "market_data")
@CompoundIndex(name = "company_date_idx", def = "{'companyCode': 1, 'tradeDate': 1}", unique = true)
public class MarketData {

    @Id
    private String id;

    private String companyCode;

    private String companyName;

    private BigDecimal open;

    private BigDecimal lastTrade;

    private BigDecimal high;

    private BigDecimal low;

    private BigDecimal change;

    private BigDecimal changePercent;

    private BigDecimal volume;

    // Which fetcher wrote this row: "CSE" (HTTP API) or "TRADINGVIEW" (Selenium), null for
    // legacy/CSV. The Selenium scraper never overwrites a row sourced from "CSE".
    private String source;

    private LocalDate tradeDate;

    private LocalDateTime updatedAt;
}

package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "dividend_financials")
@CompoundIndex(name = "company_year_idx", def = "{'companyCode': 1, 'year': 1}", unique = true)
public class DividendFinancial {

    @Id
    private String id;

    @Indexed
    private String companyCode;

    private int year;
    private BigDecimal dividendPerShare;
    private BigDecimal earningsPerShare;
    private BigDecimal dividendYield;

    private LocalDateTime scrapedAt;
}

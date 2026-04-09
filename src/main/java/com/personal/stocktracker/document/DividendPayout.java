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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "dividend_payouts")
@CompoundIndex(name = "company_exdate_idx", def = "{'companyCode': 1, 'exDividendDate': 1}", unique = true)
public class DividendPayout {

    @Id
    private String id;

    @Indexed
    private String companyCode;

    private LocalDate exDividendDate;
    private BigDecimal amountPerShare;
    private LocalDate paymentDate;

    private LocalDateTime scrapedAt;
}

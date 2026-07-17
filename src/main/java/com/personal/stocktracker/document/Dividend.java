package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import org.springframework.data.mongodb.core.index.Indexed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "dividends")
public class Dividend {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String companyCode;

    private DividendType type;

    private BigDecimal amount;

    private LocalDate date;

    private LocalDate xdDate;

    private Integer shares;

    private Integer scripShares;

    private BigDecimal totalAmount;

    private Boolean taxed;

    // Broker this dividend is attributed to. Derived from the holdings that earned
    // it (the shares held at the XD date). Null for holdings with no known broker.
    private String brokerId;

    private String transactionId;

    private LocalDateTime createdAt;
}

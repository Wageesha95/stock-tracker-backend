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
@Document(collection = "transactions")
public class Transaction {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String companyCode;

    private LocalDate date;

    private TransactionType type;

    private Integer count;

    private BigDecimal price;

    private BigDecimal commission;

    // Broker this transaction was traded through, when known (set from the trade
    // confirmation PDF's broker). Null for manually-entered transactions.
    private String brokerId;

    // When true, the transaction is excluded from all portfolio/gain calculations.
    // Used to retire a ".R" rights holding once it has been converted to shares.
    private Boolean disabled;

    private LocalDateTime createdAt;
}

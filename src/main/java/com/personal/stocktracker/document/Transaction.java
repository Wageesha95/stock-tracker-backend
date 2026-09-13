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

    // When shares arrive by transfer rather than purchase, the date the money was
    // actually committed — the cost-weighted average acquisition date of the shares
    // moved. Opportunity cost accrues from here, not from the transfer date, since
    // moving brokers does not give the money back. Null for ordinary trades, where
    // `date` already is the acquisition date.
    private LocalDate costBasisDate;

    // When true, the transaction is excluded from all portfolio/gain calculations.
    // Used to retire a ".R" rights holding (converted to shares, or lapsed/wasted).
    private Boolean disabled;

    // Distinguishes the two reasons a ".R" holding is disabled:
    //  - converted == true  → exercised into ".N" shares; cost lives in those shares,
    //                          so it is NOT a loss.
    //  - converted != true  → the right lapsed/was wasted; the money paid for it is a
    //                          realized loss.
    private Boolean converted;

    private LocalDateTime createdAt;
}

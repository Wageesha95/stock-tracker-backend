package com.personal.stocktracker.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A broker-to-broker transfer of shares: the same holding moves from one broker to
 * another without being sold. No money changes hands and no commission is charged,
 * so the transfer is value-neutral -- total shares, total cost basis and the average
 * price are unchanged, and no gain is realized. Only the broker attribution moves.
 * <p>
 * Backed by a matched pair of transactions: a TRANSFER_OUT on the source broker and a
 * TRANSFER_IN on the destination broker, both carrying {@code count} shares at
 * {@code price} with zero commission.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "share_transfers")
public class ShareTransfer {

    @Id
    private String id;

    @Indexed
    private String userId;

    private String companyCode;

    /** The date the shares actually moved between brokers. */
    private LocalDate date;

    private Integer count;

    /**
     * The holding's average price at the transfer date, captured when the transfer is
     * recorded. Both legs are priced at this value, which is what keeps the transfer
     * cost-neutral no matter what is booked afterwards.
     */
    private BigDecimal price;

    private String fromBrokerId;

    private String toBrokerId;

    /** Free-text detail about the transfer, e.g. a CDS reference or the reason. */
    private String note;

    private String outTransactionId;

    private String inTransactionId;

    private LocalDateTime createdAt;
}

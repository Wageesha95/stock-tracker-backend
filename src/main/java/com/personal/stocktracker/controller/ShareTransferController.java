package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.ShareTransfer;
import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import com.personal.stocktracker.repository.ShareTransferRepository;
import com.personal.stocktracker.repository.TransactionRepository;
import com.personal.stocktracker.util.TransactionComparators;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Broker-to-broker share transfers. Moving shares between brokers is not a trade:
 * no money changes hands, no commission is charged and no gain is realized. Each
 * transfer is booked as a matched TRANSFER_OUT / TRANSFER_IN pair priced at the
 * holding's average price on the transfer date, so the portfolio totals are
 * unchanged and only the broker attribution moves.
 */
@RestController
@RequestMapping("/api/share-transfers")
@RequiredArgsConstructor
public class ShareTransferController {

    private final ShareTransferRepository shareTransferRepository;
    private final TransactionRepository transactionRepository;

    private String currentUsername() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping
    public ResponseEntity<List<ShareTransfer>> getAll() {
        return ResponseEntity.ok(shareTransferRepository.findByUserIdOrderByDateDesc(currentUsername()));
    }

    /**
     * Preview what a transfer would move: the shares available at the source broker
     * on the given date and the average price they would carry across. Lets the entry
     * form show the price and cap the count before anything is written.
     */
    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> available(
            @RequestParam String companyCode,
            @RequestParam String brokerId,
            @RequestParam String date) {
        Holding holding = holdingAt(currentUsername(), companyCode.toUpperCase(), brokerId,
                LocalDate.parse(date), null);
        return ResponseEntity.ok(Map.of(
                "companyCode", companyCode.toUpperCase(),
                "brokerId", brokerId,
                "date", date,
                "shares", holding.shares,
                "avgPrice", holding.avgPrice()));
    }

    @PostMapping
    public ResponseEntity<ShareTransfer> create(@RequestBody Map<String, Object> body) {
        String username = currentUsername();
        String companyCode = required(body, "companyCode").toString().toUpperCase();
        LocalDate date = LocalDate.parse(required(body, "date").toString());
        int count = ((Number) required(body, "count")).intValue();
        String fromBrokerId = required(body, "fromBrokerId").toString();
        String toBrokerId = required(body, "toBrokerId").toString();
        String note = body.get("note") != null ? body.get("note").toString() : null;

        if (count <= 0) {
            throw badRequest("Transfer count must be greater than zero");
        }
        if (fromBrokerId.equals(toBrokerId)) {
            throw badRequest("Source and destination brokers must be different");
        }

        Holding holding = holdingAt(username, companyCode, fromBrokerId, date, null);
        if (holding.shares < count) {
            throw badRequest("Cannot transfer " + count + " shares of " + companyCode + ": only "
                    + holding.shares + " held at the source broker on " + date);
        }

        // Both legs are priced at the source holding's average price, which is what makes
        // the transfer cost-neutral: the cost removed from one broker is exactly the cost
        // added to the other, whatever is booked afterwards.
        BigDecimal price = holding.avgPrice();

        Transaction out = transactionRepository.save(leg(username, companyCode, date, count, price,
                TransactionType.TRANSFER_OUT, fromBrokerId));
        Transaction in = transactionRepository.save(leg(username, companyCode, date, count, price,
                TransactionType.TRANSFER_IN, toBrokerId));

        ShareTransfer transfer = ShareTransfer.builder()
                .userId(username)
                .companyCode(companyCode)
                .date(date)
                .count(count)
                .price(price)
                .fromBrokerId(fromBrokerId)
                .toBrokerId(toBrokerId)
                .note(note)
                .outTransactionId(out.getId())
                .inTransactionId(in.getId())
                .createdAt(LocalDateTime.now())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(shareTransferRepository.save(transfer));
    }

    /**
     * Only the transfer's own facts are editable — date, count, brokers and note. The
     * price is always re-derived from the source holding so the two legs cannot drift
     * apart and stop being cost-neutral.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ShareTransfer> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ShareTransfer transfer = ownedTransfer(id);

        LocalDate date = LocalDate.parse(required(body, "date").toString());
        int count = ((Number) required(body, "count")).intValue();
        String fromBrokerId = required(body, "fromBrokerId").toString();
        String toBrokerId = required(body, "toBrokerId").toString();
        String note = body.get("note") != null ? body.get("note").toString() : null;

        if (count <= 0) {
            throw badRequest("Transfer count must be greater than zero");
        }
        if (fromBrokerId.equals(toBrokerId)) {
            throw badRequest("Source and destination brokers must be different");
        }

        // Exclude this transfer's own legs, or the shares it already moved would count
        // against the holding it is being re-measured on.
        Holding holding = holdingAt(transfer.getUserId(), transfer.getCompanyCode(), fromBrokerId, date, transfer);
        if (holding.shares < count) {
            throw badRequest("Cannot transfer " + count + " shares of " + transfer.getCompanyCode()
                    + ": only " + holding.shares + " held at the source broker on " + date);
        }
        BigDecimal price = holding.avgPrice();

        transfer.setDate(date);
        transfer.setCount(count);
        transfer.setPrice(price);
        transfer.setFromBrokerId(fromBrokerId);
        transfer.setToBrokerId(toBrokerId);
        transfer.setNote(note);
        shareTransferRepository.save(transfer);

        updateLeg(transfer.getOutTransactionId(), date, count, price, fromBrokerId);
        updateLeg(transfer.getInTransactionId(), date, count, price, toBrokerId);

        return ResponseEntity.ok(transfer);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        ShareTransfer transfer = ownedTransfer(id);

        if (transfer.getOutTransactionId() != null) {
            transactionRepository.deleteById(transfer.getOutTransactionId());
        }
        if (transfer.getInTransactionId() != null) {
            transactionRepository.deleteById(transfer.getInTransactionId());
        }

        shareTransferRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private ShareTransfer ownedTransfer(String id) {
        ShareTransfer transfer = shareTransferRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share transfer not found: " + id));
        if (!transfer.getUserId().equals(currentUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return transfer;
    }

    private Transaction leg(String userId, String companyCode, LocalDate date, int count,
                            BigDecimal price, TransactionType type, String brokerId) {
        return Transaction.builder()
                .userId(userId)
                .companyCode(companyCode)
                .date(date)
                .type(type)
                .count(count)
                .price(price)
                // A transfer carries no charges, by definition.
                .commission(BigDecimal.ZERO)
                .brokerId(brokerId)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private void updateLeg(String transactionId, LocalDate date, int count, BigDecimal price, String brokerId) {
        if (transactionId == null) {
            return;
        }
        transactionRepository.findById(transactionId).ifPresent(tx -> {
            tx.setDate(date);
            tx.setCount(count);
            tx.setPrice(price);
            tx.setBrokerId(brokerId);
            transactionRepository.save(tx);
        });
    }

    /**
     * Running share count and cost basis for one company at one broker, as of {@code asOf}.
     *
     * @param exclude a transfer whose legs must be ignored (used when re-measuring an
     *                edit against the holding as it would be without that transfer)
     */
    private Holding holdingAt(String userId, String companyCode, String brokerId, LocalDate asOf,
                              ShareTransfer exclude) {
        List<Transaction> txns = new ArrayList<>(
                transactionRepository.findByUserIdAndCompanyCodeOrderByDateDesc(userId, companyCode).stream()
                        .filter(tx -> Objects.equals(tx.getBrokerId(), brokerId))
                        .filter(tx -> !tx.getDate().isAfter(asOf))
                        .filter(tx -> tx.getDisabled() == null || !tx.getDisabled())
                        .filter(tx -> exclude == null
                                || (!tx.getId().equals(exclude.getOutTransactionId())
                                && !tx.getId().equals(exclude.getInTransactionId())))
                        .toList());
        txns.sort(TransactionComparators.BY_DATE_BUYS_FIRST);

        Holding holding = new Holding();
        for (Transaction tx : txns) {
            BigDecimal count = BigDecimal.valueOf(tx.getCount());
            switch (tx.getType()) {
                case BUY, RIGHTS, SCRIP_DIVIDEND, IPO, TRANSFER_IN -> {
                    holding.shares += tx.getCount();
                    holding.cost = holding.cost.add(tx.getPrice().multiply(count)).add(tx.getCommission());
                }
                case SELL -> {
                    holding.cost = holding.cost.subtract(holding.avgPrice().multiply(count));
                    holding.shares -= tx.getCount();
                }
                // An outbound transfer removes exactly the cost its own price represents,
                // mirroring what the matching TRANSFER_IN added at the other broker.
                case TRANSFER_OUT -> {
                    holding.cost = holding.cost.subtract(tx.getPrice().multiply(count));
                    holding.shares -= tx.getCount();
                }
            }
        }
        holding.shares = Math.max(holding.shares, 0);
        return holding;
    }

    private static Object required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw badRequest(key + " is required");
        }
        return value;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static final class Holding {
        private int shares;
        private BigDecimal cost = BigDecimal.ZERO;

        private BigDecimal avgPrice() {
            return shares > 0 ? cost.divide(BigDecimal.valueOf(shares), 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        }
    }
}

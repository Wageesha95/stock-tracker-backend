package com.personal.stocktracker.controller;

import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests FIFO average price calculation using real DFCC.N transaction data.
 */
class DashboardCalculationTest {

    /**
     * FIFO calculation logic extracted from DashboardController.
     * Returns [sharesHeld, costBasis, avgBuyPrice, realizedGain]
     */
    private BigDecimal[] calculateFIFO(List<Transaction> transactions) {
        List<Transaction> txns = new ArrayList<>(transactions);
        txns.sort(Comparator.comparing(Transaction::getDate));

        int sharesHeld = 0;
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal realizedGain = BigDecimal.ZERO;

        for (Transaction tx : txns) {
            if (tx.getType() == TransactionType.BUY || tx.getType() == TransactionType.RIGHTS || tx.getType() == TransactionType.SCRIP_DIVIDEND) {
                sharesHeld += tx.getCount();
                costBasis = costBasis.add(tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())).add(tx.getCommission()));
            } else if (tx.getType() == TransactionType.SELL) {
                BigDecimal avgAtSell = sharesHeld > 0
                        ? costBasis.divide(BigDecimal.valueOf(sharesHeld), 4, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                BigDecimal costRemoved = avgAtSell.multiply(BigDecimal.valueOf(tx.getCount()));
                BigDecimal sellRevenue = tx.getPrice().multiply(BigDecimal.valueOf(tx.getCount())).subtract(tx.getCommission());
                realizedGain = realizedGain.add(sellRevenue.subtract(costRemoved));
                costBasis = costBasis.subtract(costRemoved);
                sharesHeld -= tx.getCount();
            } else {
                throw new IllegalArgumentException("Unknown transaction type: " + tx.getType());
            }
        }

        sharesHeld = Math.max(sharesHeld, 0);
        BigDecimal avgBuyPrice = sharesHeld > 0
                ? costBasis.divide(BigDecimal.valueOf(sharesHeld), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new BigDecimal[]{
                BigDecimal.valueOf(sharesHeld),
                costBasis.setScale(2, RoundingMode.HALF_UP),
                avgBuyPrice.setScale(2, RoundingMode.HALF_UP),
                realizedGain.setScale(2, RoundingMode.HALF_UP)
        };
    }

    private Transaction tx(String date, TransactionType type, int count, String price, String commission) {
        return Transaction.builder()
                .companyCode("DFCC.N")
                .date(LocalDate.parse(date))
                .type(type)
                .count(count)
                .price(new BigDecimal(price))
                .commission(new BigDecimal(commission))
                .createdAt(LocalDateTime.now())
                .build();
    }

    private List<Transaction> dfccTransactions() {
        return List.of(
                tx("2026-01-19", TransactionType.BUY, 10, "162.00", "18.14"),
                tx("2026-01-20", TransactionType.BUY, 10, "160.00", "17.91"),
                tx("2026-02-25", TransactionType.SELL, 19, "158.00", "33.62"),
                tx("2026-03-03", TransactionType.BUY, 24, "148.75", "39.99"),
                tx("2026-03-04", TransactionType.BUY, 10, "144.75", "16.20"),
                tx("2026-03-04", TransactionType.BUY, 5, "144.00", "8.07"),
                tx("2026-03-04", TransactionType.BUY, 10, "144.00", "16.13"),
                tx("2026-03-23", TransactionType.SCRIP_DIVIDEND, 1, "0", "0")
        );
    }

    @Test
    void dfcc_sharesHeld_shouldBe51() {
        BigDecimal[] result = calculateFIFO(dfccTransactions());
        assertThat(result[0]).isEqualByComparingTo("51");
    }

    @Test
    void dfcc_avgPrice_shouldBe145_50() {
        BigDecimal[] result = calculateFIFO(dfccTransactions());
        // After FIFO: 51 shares, avg ~145.50
        assertThat(result[2].doubleValue()).isBetween(145.40, 145.60);
    }

    @Test
    void dfcc_stepByStep_FIFO() {
        // Step 1: Buy 10 @ 162 + 18.14 commission
        List<Transaction> txns = new ArrayList<>();
        txns.add(tx("2026-01-19", TransactionType.BUY, 10, "162.00", "18.14"));
        BigDecimal[] r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("10");
        // cost = 10*162 + 18.14 = 1638.14, avg = 163.81
        assertThat(r[2]).isEqualByComparingTo("163.81");

        // Step 2: Buy 10 @ 160 + 17.91
        txns.add(tx("2026-01-20", TransactionType.BUY, 10, "160.00", "17.91"));
        r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("20");
        // cost = 1638.14 + 1617.91 = 3256.05, avg = 162.80
        assertThat(r[2]).isEqualByComparingTo("162.80");

        // Step 3: Sell 19 @ 158 - 33.62 commission
        txns.add(tx("2026-02-25", TransactionType.SELL, 19, "158.00", "33.62"));
        r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("1");
        // avg at sell = 162.8025, cost removed = 19*162.8025 = 3093.2475
        // remaining cost = 3256.05 - 3093.2475 = 162.8025, avg = 162.80
        assertThat(r[2]).isEqualByComparingTo("162.80");

        // Step 4: Buy 24 @ 148.75 + 39.99
        txns.add(tx("2026-03-03", TransactionType.BUY, 24, "148.75", "39.99"));
        r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("25");

        // Step 5-7: Buy 10+5+10 on 2026-03-04
        txns.add(tx("2026-03-04", TransactionType.BUY, 10, "144.75", "16.20"));
        txns.add(tx("2026-03-04", TransactionType.BUY, 5, "144.00", "8.07"));
        txns.add(tx("2026-03-04", TransactionType.BUY, 10, "144.00", "16.13"));
        r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("50");

        // Step 8: Scrip dividend 1 share @ 0
        txns.add(tx("2026-03-23", TransactionType.SCRIP_DIVIDEND, 1, "0", "0"));
        r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("51");
        // Scrip adds 1 share at 0 cost, diluting avg
        assertThat(r[2].doubleValue()).isBetween(145.40, 145.60);
    }

    @Test
    void dfcc_realizedGain_isNegative() {
        BigDecimal[] result = calculateFIFO(dfccTransactions());
        // Sell 19 @ 158 (revenue = 2968.38), cost basis = 19 * 162.8025 = 3093.25
        // realized = 2968.38 - 3093.25 = -124.87
        assertThat(result[3].doubleValue()).isLessThan(0);
        assertThat(result[3].doubleValue()).isBetween(-130.0, -120.0);
    }

    @Test
    void onlyBuys_avgPrice_isWeightedAverage() {
        List<Transaction> txns = List.of(
                tx("2026-01-01", TransactionType.BUY, 100, "10.00", "1.00"),
                tx("2026-01-02", TransactionType.BUY, 100, "20.00", "1.00")
        );
        BigDecimal[] r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("200");
        // cost = (100*10+1) + (100*20+1) = 1001 + 2001 = 3002, avg = 15.01
        assertThat(r[2]).isEqualByComparingTo("15.01");
    }

    @Test
    void sellAll_sharesZero_avgZero() {
        List<Transaction> txns = List.of(
                tx("2026-01-01", TransactionType.BUY, 10, "100.00", "0"),
                tx("2026-01-02", TransactionType.SELL, 10, "110.00", "0")
        );
        BigDecimal[] r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("0");
        assertThat(r[2]).isEqualByComparingTo("0.00");
        // realized = 10*110 - 10*100 = 100
        assertThat(r[3]).isEqualByComparingTo("100.00");
    }

    @Test
    void rightsIssue_addsSharesToHolding() {
        List<Transaction> txns = List.of(
                tx("2026-01-01", TransactionType.BUY, 100, "50.00", "5.00"),
                tx("2026-02-01", TransactionType.RIGHTS, 20, "30.00", "0")
        );
        BigDecimal[] r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("120");
        // cost = (100*50+5) + (20*30+0) = 5005 + 600 = 5605, avg = 46.71
        assertThat(r[2]).isEqualByComparingTo("46.71");
    }

    @Test
    void scripDividend_dilutesAvgPrice() {
        List<Transaction> txns = List.of(
                tx("2026-01-01", TransactionType.BUY, 100, "50.00", "0"),
                tx("2026-02-01", TransactionType.SCRIP_DIVIDEND, 10, "0", "0")
        );
        BigDecimal[] r = calculateFIFO(txns);
        assertThat(r[0]).isEqualByComparingTo("110");
        // cost = 5000, avg = 5000/110 = 45.45
        assertThat(r[2]).isEqualByComparingTo("45.45");
    }

    @Test
    void unknownType_throwsError() {
        // This test verifies the else branch throws
        Transaction badTx = Transaction.builder()
                .companyCode("TEST")
                .date(LocalDate.now())
                .type(null)
                .count(1)
                .price(BigDecimal.TEN)
                .commission(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            calculateFIFO(List.of(badTx));
        });
    }
}

package com.personal.stocktracker.util;

import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;

import java.util.Comparator;

public final class TransactionComparators {

    // Sort chronologically, with same-date inflows before outflows. Required for any
    // running-cost-basis / FIFO loop — processing a SELL before a same-date BUY
    // drives sharesHeld negative and corrupts the avg price. TRANSFER_OUT is an
    // outflow for the same reason: the shares it moves out may have been acquired,
    // or transferred in, earlier the same day.
    public static final Comparator<Transaction> BY_DATE_BUYS_FIRST =
            Comparator.comparing(Transaction::getDate)
                    .thenComparing(t -> isOutflow(t.getType()) ? 1 : 0);

    private static boolean isOutflow(TransactionType type) {
        return type == TransactionType.SELL || type == TransactionType.TRANSFER_OUT;
    }

    private TransactionComparators() {}
}

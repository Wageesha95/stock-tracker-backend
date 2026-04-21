package com.personal.stocktracker.util;

import com.personal.stocktracker.document.Transaction;
import com.personal.stocktracker.document.TransactionType;

import java.util.Comparator;

public final class TransactionComparators {

    // Sort chronologically, with same-date BUYs before SELLs. Required for any
    // running-cost-basis / FIFO loop — processing a SELL before a same-date BUY
    // drives sharesHeld negative and corrupts the avg price.
    public static final Comparator<Transaction> BY_DATE_BUYS_FIRST =
            Comparator.comparing(Transaction::getDate)
                    .thenComparing(t -> t.getType() == TransactionType.SELL ? 1 : 0);

    private TransactionComparators() {}
}

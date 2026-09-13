package com.personal.stocktracker.document;

public enum TransactionType {
    BUY,
    SELL,
    RIGHTS,
    SCRIP_DIVIDEND,
    IPO,
    // The two legs of a broker-to-broker share transfer. Both legs carry the same
    // count and the same price (the holding's average price at the transfer date)
    // and zero commission, so together they leave total shares, total cost basis
    // and the average price untouched -- only the broker attribution moves.
    // A TRANSFER_OUT never realizes a gain; it is not a disposal.
    TRANSFER_OUT,
    TRANSFER_IN;

    /** Types that add shares to a holding and add their cost to its basis. */
    public boolean isAcquisition() {
        return this == BUY || this == RIGHTS || this == SCRIP_DIVIDEND || this == IPO || this == TRANSFER_IN;
    }

    /** Types that remove shares from a holding. Only SELL realizes a gain. */
    public boolean isDisposal() {
        return this == SELL || this == TRANSFER_OUT;
    }
}

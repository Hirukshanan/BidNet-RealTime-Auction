package com.bidnet.auction;

public enum BidRejectionReason {
    INVALID_BIDDER,
    INVALID_AMOUNT,
    NON_POSITIVE_AMOUNT,
    TOO_MANY_DECIMAL_PLACES,
    BID_TOO_LOW,
    AUCTION_CLOSED
}

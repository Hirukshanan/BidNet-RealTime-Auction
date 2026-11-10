package com.bidnet.auction;

public final class AuctionNotFoundException extends RuntimeException {
    public AuctionNotFoundException(String auctionId) {
        super("Unknown auction: " + auctionId);
    }
}

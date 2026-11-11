package com.bidnet.auction;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record AuctionSnapshot(
        String auctionId,
        AuctionStatus status,
        BigDecimal startingPrice,
        BigDecimal currentPrice,
        BigDecimal minimumNextBid,
        String highestBidderId,
        long version) {

    public AuctionSnapshot {
        Objects.requireNonNull(auctionId, "auctionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startingPrice, "startingPrice");
        Objects.requireNonNull(currentPrice, "currentPrice");
        Objects.requireNonNull(minimumNextBid, "minimumNextBid");
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
    }

    public Optional<String> winnerId() {
        return Optional.ofNullable(highestBidderId);
    }

    public boolean hasAcceptedBid() {
        return highestBidderId != null;
    }
}

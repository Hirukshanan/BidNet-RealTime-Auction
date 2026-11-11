package com.bidnet.auction;

import java.util.Objects;
import java.util.Optional;

public record BidResult(
        boolean accepted,
        BidRejectionReason rejectionReason,
        AuctionSnapshot snapshot) {

    public BidResult {
        Objects.requireNonNull(snapshot, "snapshot");
        if (accepted == (rejectionReason != null)) {
            throw new IllegalArgumentException(
                    "An accepted bid cannot have a rejection reason, and a rejected bid must have one");
        }
    }

    static BidResult accepted(AuctionSnapshot snapshot) {
        return new BidResult(true, null, snapshot);
    }

    static BidResult rejected(BidRejectionReason reason, AuctionSnapshot snapshot) {
        return new BidResult(false, Objects.requireNonNull(reason, "reason"), snapshot);
    }

    public Optional<BidRejectionReason> rejection() {
        return Optional.ofNullable(rejectionReason);
    }
}

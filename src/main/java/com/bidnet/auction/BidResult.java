package com.bidnet.auction;

import java.util.Objects;
import java.util.Optional;

public record BidResult(
        boolean accepted,
        BidRejectionReason rejectionReason,
        AuctionSnapshot snapshot,
        boolean stateChanged) {

    public BidResult {
        Objects.requireNonNull(snapshot, "snapshot");
        if (accepted == (rejectionReason != null)) {
            throw new IllegalArgumentException(
                    "An accepted bid cannot have a rejection reason, and a rejected bid must have one");
        }
        if (accepted && !stateChanged) {
            throw new IllegalArgumentException("An accepted bid must change auction state");
        }
    }

    static BidResult accepted(AuctionSnapshot snapshot) {
        return new BidResult(true, null, snapshot, true);
    }

    static BidResult rejected(BidRejectionReason reason, AuctionSnapshot snapshot) {
        return rejected(reason, snapshot, false);
    }

    static BidResult rejected(
            BidRejectionReason reason,
            AuctionSnapshot snapshot,
            boolean stateChanged) {
        return new BidResult(
                false,
                Objects.requireNonNull(reason, "reason"),
                snapshot,
                stateChanged);
    }

    public Optional<BidRejectionReason> rejection() {
        return Optional.ofNullable(rejectionReason);
    }

    public boolean closedNow() {
        return stateChanged && snapshot.status() == AuctionStatus.CLOSED;
    }
}

package com.bidnet.auction;

import java.util.Objects;

public record CloseResult(boolean closedNow, AuctionSnapshot snapshot) {
    public CloseResult {
        Objects.requireNonNull(snapshot, "snapshot");
        if (closedNow && snapshot.status() != AuctionStatus.CLOSED) {
            throw new IllegalArgumentException("A new closure must return a closed snapshot");
        }
    }

    static CloseResult unchanged(AuctionSnapshot snapshot) {
        return new CloseResult(false, snapshot);
    }

    static CloseResult closed(AuctionSnapshot snapshot) {
        return new CloseResult(true, snapshot);
    }
}

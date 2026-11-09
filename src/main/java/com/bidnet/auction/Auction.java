package com.bidnet.auction;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

final class Auction {
    private final String auctionId;
    private final BigDecimal startingPrice;
    private final ReentrantLock lock = new ReentrantLock();

    private AuctionStatus status = AuctionStatus.OPEN;
    private BigDecimal currentPrice;
    private String highestBidderId;
    private long version;

    Auction(String auctionId, BigDecimal startingPrice) {
        if (auctionId == null || auctionId.isBlank()) {
            throw new IllegalArgumentException("auctionId must not be blank");
        }
        if (startingPrice == null) {
            throw new IllegalArgumentException("startingPrice must not be null");
        }
        if (!MoneyPolicy.hasSupportedPrecision(startingPrice)) {
            throw new IllegalArgumentException("startingPrice must have at most two decimal places");
        }
        if (startingPrice.signum() <= 0) {
            throw new IllegalArgumentException("startingPrice must be positive");
        }
        this.auctionId = auctionId;
        this.startingPrice = MoneyPolicy.normalize(startingPrice);
        this.currentPrice = this.startingPrice;
    }

    BidResult placeBid(String bidderId, BigDecimal offeredAmount) {
        lock.lock();
        try {
            AuctionSnapshot before = snapshotLocked();
            if (status != AuctionStatus.OPEN) {
                return BidResult.rejected(BidRejectionReason.AUCTION_CLOSED, before);
            }
            if (bidderId == null || bidderId.isBlank()) {
                return BidResult.rejected(BidRejectionReason.INVALID_BIDDER, before);
            }
            if (offeredAmount == null) {
                return BidResult.rejected(BidRejectionReason.INVALID_AMOUNT, before);
            }
            if (offeredAmount.signum() <= 0) {
                return BidResult.rejected(BidRejectionReason.NON_POSITIVE_AMOUNT, before);
            }
            if (!MoneyPolicy.hasSupportedPrecision(offeredAmount)) {
                return BidResult.rejected(BidRejectionReason.TOO_MANY_DECIMAL_PLACES, before);
            }

            BigDecimal normalizedAmount = MoneyPolicy.normalize(offeredAmount);
            BigDecimal requiredAmount = minimumNextBidLocked();
            if (normalizedAmount.compareTo(requiredAmount) < 0) {
                return BidResult.rejected(BidRejectionReason.BID_TOO_LOW, before);
            }

            currentPrice = normalizedAmount;
            highestBidderId = bidderId;
            version++;
            return BidResult.accepted(snapshotLocked());
        } finally {
            lock.unlock();
        }
    }

    AuctionSnapshot snapshot() {
        lock.lock();
        try {
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    private BigDecimal minimumNextBidLocked() {
        return highestBidderId == null ? startingPrice : MoneyPolicy.nextMinimum(currentPrice);
    }

    private AuctionSnapshot snapshotLocked() {
        return new AuctionSnapshot(
                auctionId,
                status,
                startingPrice,
                currentPrice,
                minimumNextBidLocked(),
                highestBidderId,
                version);
    }
}

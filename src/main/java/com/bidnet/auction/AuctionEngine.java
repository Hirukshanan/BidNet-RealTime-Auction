package com.bidnet.auction;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuctionEngine {
    private final ConcurrentMap<String, Auction> auctions = new ConcurrentHashMap<>();
    private final Clock clock;

    public AuctionEngine() {
        this(Clock.systemUTC());
    }

    public AuctionEngine(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AuctionSnapshot createAuction(
            String auctionId,
            BigDecimal startingPrice,
            Instant deadline) {
        Auction auction = new Auction(auctionId, startingPrice, deadline, clock);
        Auction existing = auctions.putIfAbsent(auctionId, auction);
        if (existing != null) {
            throw new IllegalArgumentException("Auction already exists: " + auctionId);
        }
        return auction.snapshot();
    }

    public BidResult placeBid(String auctionId, String bidderId, BigDecimal offeredAmount) {
        return requireAuction(auctionId).placeBid(bidderId, offeredAmount);
    }

    public AuctionSnapshot snapshot(String auctionId) {
        return requireAuction(auctionId).snapshot();
    }

    public CloseResult closeIfExpired(String auctionId) {
        return requireAuction(auctionId).closeIfExpired();
    }

    public Set<String> auctionIds() {
        return Set.copyOf(auctions.keySet());
    }

    private Auction requireAuction(String auctionId) {
        Auction auction = auctionId == null ? null : auctions.get(auctionId);
        if (auction == null) {
            throw new AuctionNotFoundException(auctionId);
        }
        return auction;
    }
}

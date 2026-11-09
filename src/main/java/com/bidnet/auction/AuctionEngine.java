package com.bidnet.auction;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AuctionEngine {
    private final ConcurrentMap<String, Auction> auctions = new ConcurrentHashMap<>();

    public AuctionSnapshot createAuction(String auctionId, BigDecimal startingPrice) {
        Auction auction = new Auction(auctionId, startingPrice);
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

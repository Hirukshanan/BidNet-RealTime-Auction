package com.bidnet.auction;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuctionEngineTest {
    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");
    private static final Instant DEADLINE = NOW.plusSeconds(60);
    private final MutableClock clock = new MutableClock(NOW);
    private final AuctionEngine engine = new AuctionEngine(clock);

    @Test
    void acceptsTheDocumentedBoundarySequence() {
        AuctionSnapshot created = createAuction("camera", money("1000.00"));
        assertEquals(money("1000.00"), created.minimumNextBid());
        assertEquals(0, created.version());
        assertFalse(created.hasAcceptedBid());

        BidResult first = engine.placeBid("camera", "alice", money("1000.00"));
        assertTrue(first.accepted());
        assertEquals(money("1050.00"), first.snapshot().minimumNextBid());
        assertEquals(1, first.snapshot().version());

        BidResult second = engine.placeBid("camera", "bob", money("1050.00"));
        assertTrue(second.accepted());
        assertEquals(money("1102.50"), second.snapshot().minimumNextBid());
        assertEquals(2, second.snapshot().version());

        BidResult third = engine.placeBid("camera", "carol", money("1102.50"));
        assertTrue(third.accepted());
        assertEquals("carol", third.snapshot().winnerId().orElseThrow());
    }

    @Test
    void roundsTheFivePercentMinimumUpToTwoDecimals() {
        createAuction("rounding", money("10.01"));
        assertTrue(engine.placeBid("rounding", "alice", money("10.01")).accepted());

        AuctionSnapshot snapshot = engine.snapshot("rounding");
        assertEquals(money("10.52"), snapshot.minimumNextBid());
        assertFalse(engine.placeBid("rounding", "bob", money("10.51")).accepted());
        assertTrue(engine.placeBid("rounding", "bob", money("10.52")).accepted());
    }

    @Test
    void acceptsAFirstBidAboveTheStartingPrice() {
        createAuction("painting", money("1000.00"));

        BidResult result = engine.placeBid("painting", "alice", money("1250.00"));

        assertTrue(result.accepted());
        assertEquals(money("1250.00"), result.snapshot().currentPrice());
        assertEquals("alice", result.snapshot().highestBidderId());
    }

    @Test
    void rejectedBidsNeverMutateTheAuction() {
        createAuction("invalid", money("1000.00"));
        AuctionSnapshot initial = engine.snapshot("invalid");

        List<BidResult> results = List.of(
                engine.placeBid("invalid", null, money("1000.00")),
                engine.placeBid("invalid", "  ", money("1000.00")),
                engine.placeBid("invalid", "alice", null),
                engine.placeBid("invalid", "alice", money("0.00")),
                engine.placeBid("invalid", "alice", money("-1.00")),
                engine.placeBid("invalid", "alice", new BigDecimal("1000.001")),
                engine.placeBid("invalid", "alice", money("999.99")));

        assertEquals(List.of(
                        BidRejectionReason.INVALID_BIDDER,
                        BidRejectionReason.INVALID_BIDDER,
                        BidRejectionReason.INVALID_AMOUNT,
                        BidRejectionReason.NON_POSITIVE_AMOUNT,
                        BidRejectionReason.NON_POSITIVE_AMOUNT,
                        BidRejectionReason.TOO_MANY_DECIMAL_PLACES,
                        BidRejectionReason.BID_TOO_LOW),
                results.stream().map(BidResult::rejectionReason).toList());
        assertTrue(results.stream().noneMatch(BidResult::accepted));
        assertEquals(initial, engine.snapshot("invalid"));
    }

    @Test
    void simultaneousEqualBidsProduceOneAtomicWinner() throws Exception {
        createAuction("concurrent", money("1000.00"));
        CyclicBarrier start = new CyclicBarrier(3);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BidResult> alice = executor.submit(() -> bidAfterBarrier(start, "alice"));
            Future<BidResult> bob = executor.submit(() -> bidAfterBarrier(start, "bob"));
            start.await(5, TimeUnit.SECONDS);

            List<BidResult> results = List.of(
                    alice.get(5, TimeUnit.SECONDS),
                    bob.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(BidResult::accepted).count());
            assertEquals(1, results.stream()
                    .filter(result -> result.rejectionReason() == BidRejectionReason.BID_TOO_LOW)
                    .count());

            AuctionSnapshot finalState = engine.snapshot("concurrent");
            assertEquals(1, finalState.version());
            assertEquals(money("1000.00"), finalState.currentPrice());
            assertTrue(finalState.winnerId().filter(id -> id.equals("alice") || id.equals("bob")).isPresent());
        }
    }

    @Test
    void validatesAuctionCreationAndLookup() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.createAuction("blank-price", new BigDecimal("1.001"), DEADLINE));
        assertThrows(IllegalArgumentException.class,
                () -> engine.createAuction("zero-price", BigDecimal.ZERO, DEADLINE));
        createAuction("unique", BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class,
                () -> engine.createAuction("unique", BigDecimal.TEN, DEADLINE));
        assertThrows(AuctionNotFoundException.class,
                () -> engine.snapshot("missing"));
    }

    private BidResult bidAfterBarrier(CyclicBarrier barrier, String bidderId) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return engine.placeBid("concurrent", bidderId, money("1000.00"));
    }

    private AuctionSnapshot createAuction(String auctionId, BigDecimal startingPrice) {
        return engine.createAuction(auctionId, startingPrice, DEADLINE);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new UnsupportedOperationException("This test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

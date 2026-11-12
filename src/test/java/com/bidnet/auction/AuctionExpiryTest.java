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

class AuctionExpiryTest {
    private static final Instant START = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(30);
    private final MutableClock clock = new MutableClock(START);
    private final AuctionEngine engine = new AuctionEngine(clock);

    @Test
    void acceptsImmediatelyBeforeDeadlineButRejectsAtDeadline() {
        engine.createAuction("before", money("1000.00"), DEADLINE);
        engine.createAuction("boundary", money("1000.00"), DEADLINE);

        clock.set(DEADLINE.minusNanos(1));
        BidResult before = engine.placeBid("before", "alice", money("1000.00"));
        assertTrue(before.accepted());

        clock.set(DEADLINE);
        BidResult atDeadline = engine.placeBid("boundary", "bob", money("1000.00"));
        assertFalse(atDeadline.accepted());
        assertEquals(BidRejectionReason.DEADLINE_REACHED, atDeadline.rejectionReason());
        assertTrue(atDeadline.closedNow());
        assertEquals(AuctionStatus.CLOSED, atDeadline.snapshot().status());
        assertEquals(1, atDeadline.snapshot().version());
        assertTrue(atDeadline.snapshot().winnerId().isEmpty());
    }

    @Test
    void earlyCloseDoesNothingAndExpiredCloseFreezesWinnerAndPrice() {
        engine.createAuction("freeze", money("1000.00"), DEADLINE);
        BidResult bid = engine.placeBid("freeze", "alice", money("1200.00"));
        assertTrue(bid.accepted());

        CloseResult early = engine.closeIfExpired("freeze");
        assertFalse(early.closedNow());
        assertEquals(AuctionStatus.OPEN, early.snapshot().status());
        assertEquals(1, early.snapshot().version());

        clock.set(DEADLINE);
        CloseResult closed = engine.closeIfExpired("freeze");
        assertTrue(closed.closedNow());
        assertEquals(AuctionStatus.CLOSED, closed.snapshot().status());
        assertEquals(2, closed.snapshot().version());
        assertEquals("alice", closed.snapshot().winnerId().orElseThrow());
        assertEquals(money("1200.00"), closed.snapshot().currentPrice());

        BidResult late = engine.placeBid("freeze", "bob", money("2000.00"));
        assertFalse(late.accepted());
        assertEquals(BidRejectionReason.AUCTION_CLOSED, late.rejectionReason());
        assertFalse(late.stateChanged());
        assertEquals(closed.snapshot(), late.snapshot());
    }

    @Test
    void repeatedCloseIsIdempotentAndOnlyFirstCallerAnnounces() {
        engine.createAuction("once", money("1000.00"), DEADLINE);
        clock.set(DEADLINE.plusSeconds(10));

        CloseResult first = engine.closeIfExpired("once");
        CloseResult second = engine.closeIfExpired("once");
        CloseResult third = engine.closeIfExpired("once");

        assertTrue(first.closedNow());
        assertFalse(second.closedNow());
        assertFalse(third.closedNow());
        assertEquals(first.snapshot(), second.snapshot());
        assertEquals(first.snapshot(), third.snapshot());
        assertEquals(1, first.snapshot().version());
        assertTrue(first.snapshot().winnerId().isEmpty());
    }

    @Test
    void simultaneousLateBidAndExpiryProduceOneCloseTransition() throws Exception {
        engine.createAuction("race", money("1000.00"), DEADLINE);
        clock.set(DEADLINE);
        CyclicBarrier start = new CyclicBarrier(3);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<BidResult> bid = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return engine.placeBid("race", "alice", money("1000.00"));
            });
            Future<CloseResult> expiry = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return engine.closeIfExpired("race");
            });
            start.await(5, TimeUnit.SECONDS);

            BidResult bidResult = bid.get(5, TimeUnit.SECONDS);
            CloseResult closeResult = expiry.get(5, TimeUnit.SECONDS);
            long transitions = List.of(bidResult.stateChanged(), closeResult.closedNow()).stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertFalse(bidResult.accepted());
            assertEquals(1, transitions, "exactly one caller owns the final announcement");
            assertEquals(AuctionStatus.CLOSED, engine.snapshot("race").status());
            assertEquals(1, engine.snapshot("race").version());
            assertTrue(engine.snapshot("race").winnerId().isEmpty());
        }
    }

    @Test
    void requiresAnAuthoritativeFutureDeadline() {
        assertThrows(IllegalArgumentException.class,
                () -> engine.createAuction("missing", money("1.00"), null));
        assertThrows(IllegalArgumentException.class,
                () -> engine.createAuction("at-now", money("1.00"), START));
        assertThrows(IllegalArgumentException.class,
                () -> engine.createAuction("past", money("1.00"), START.minusNanos(1)));
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
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

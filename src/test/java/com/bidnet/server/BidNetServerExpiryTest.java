package com.bidnet.server;

import com.bidnet.auction.AuctionEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BidNetServerExpiryTest {
    private static final Instant START = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(30);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock(START);
    private final AuctionEngine engine = new AuctionEngine(clock);
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    private BidNetServer server;
    private Thread serverThread;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
        if (serverThread != null) {
            serverThread.join(5000);
            assertFalse(serverThread.isAlive());
        }
        assertNull(serverFailure.get());
    }

    @Test
    void scheduledCallbackAnnouncesFinalNoBidSnapshotExactlyOnce() throws Exception {
        startServer("no-bids");
        try (Peer peer = new Peer()) {
            peer.join("no-bids");
            clock.set(DEADLINE);

            server.expireAuction("no-bids");
            JsonNode ended = peer.read("AUCTION_ENDED").path("snapshot");
            assertEquals("CLOSED", ended.path("status").asText());
            assertEquals(1, ended.path("version").asLong());
            assertTrue(ended.path("highestBidderId").isNull());
            assertEquals(DEADLINE.toString(), ended.path("deadline").asText());

            server.expireAuction("no-bids");
            peer.send("ECHO", "{}");
            peer.read("ECHO_RESPONSE");
        }
    }

    @Test
    void lateBidClosesAndPreservesWinnerWhenSchedulerCallbackIsDelayed() throws Exception {
        startServer("delayed");
        try (Peer alice = new Peer(); Peer bob = new Peer()) {
            String aliceId = alice.join("delayed").path("bidderId").asText();
            bob.join("delayed");

            alice.send("PLACE_BID", "{\"auctionId\":\"delayed\",\"amount\":\"1000.00\"}");
            assertTrue(alice.read("BID_RESULT").path("accepted").asBoolean());
            alice.read("AUCTION_UPDATE");
            bob.read("AUCTION_UPDATE");

            clock.set(DEADLINE);
            bob.send("PLACE_BID", "{\"auctionId\":\"delayed\",\"amount\":\"2000.00\"}");
            JsonNode rejected = bob.read("BID_RESULT");
            assertFalse(rejected.path("accepted").asBoolean());
            assertEquals("DEADLINE_REACHED", rejected.path("reason").asText());
            JsonNode finalState = rejected.path("snapshot");
            assertEquals("CLOSED", finalState.path("status").asText());
            assertEquals(2, finalState.path("version").asLong());
            assertEquals(aliceId, finalState.path("highestBidderId").asText());
            assertEquals("1000.00", finalState.path("currentPrice").asText());
            assertEquals(finalState, alice.read("AUCTION_ENDED").path("snapshot"));
            assertEquals(finalState, bob.read("AUCTION_ENDED").path("snapshot"));

            server.expireAuction("delayed");
            bob.send("ECHO", "{}");
            bob.read("ECHO_RESPONSE");
        }
    }

    @Test
    void closingServerReleasesExpiryScheduler() throws Exception {
        engine.createAuction("shutdown", new BigDecimal("1.00"), DEADLINE);
        server = new BidNetServer(0, engine, clock);
        assertFalse(server.expirySchedulerIsShutdown());

        server.close();

        assertTrue(server.expirySchedulerIsShutdown());
    }

    private void startServer(String auctionId) throws IOException {
        engine.createAuction(auctionId, new BigDecimal("1000.00"), DEADLINE);
        server = new BidNetServer(0, engine, clock);
        serverThread = Thread.startVirtualThread(() -> {
            try {
                server.start();
            } catch (Throwable problem) {
                serverFailure.set(problem);
            }
        });
    }

    private final class Peer implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader input;

        private Peer() throws IOException {
            socket = new Socket("127.0.0.1", server.getPort());
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
        }

        private JsonNode join(String auctionId) throws IOException {
            send("JOIN_AUCTION", "{\"auctionId\":\"" + auctionId + "\"}");
            return read("AUCTION_STATE");
        }

        private void send(String type, String payload) throws IOException {
            socket.getOutputStream().write(("{\"type\":\"" + type + "\",\"payload\":"
                    + payload + "}\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        private JsonNode read(String expectedType) throws IOException {
            String line = input.readLine();
            assertNotNull(line, "Unexpected server EOF");
            JsonNode frame = mapper.readTree(line);
            assertEquals(expectedType, frame.path("type").asText(), frame.toString());
            return frame.path("payload");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
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

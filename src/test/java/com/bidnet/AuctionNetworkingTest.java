package com.bidnet;

import com.bidnet.auction.AuctionEngine;
import com.bidnet.server.BidNetServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AuctionNetworkingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private BidNetServer server;
    private Thread serverThread;

    @BeforeEach
    void start() throws IOException {
        AuctionEngine engine = new AuctionEngine();
        Instant deadline = Instant.now().plus(Duration.ofHours(1));
        engine.createAuction("camera", new BigDecimal("1000.00"), deadline);
        engine.createAuction("painting", new BigDecimal("10.01"), deadline);
        server = new BidNetServer(0, engine);
        serverThread = Thread.startVirtualThread(() -> {
            try {
                server.start();
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        serverThread.join(5000);
        assertFalse(serverThread.isAlive());
        assertNull(failure.get());
    }

    @Test
    void joinsBidsBroadcastsAndSurvivesDisconnects() throws Exception {
        try (Peer alice = new Peer(); Peer bob = new Peer()) {
            JsonNode joined = alice.join("camera");
            String aliceId = joined.path("bidderId").asText();
            assertFalse(aliceId.isBlank());
            assertEquals(0, joined.path("snapshot").path("version").asLong());
            assertNotEquals(aliceId, bob.join("camera").path("bidderId").asText());
            alice.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1000.00\",\"senderId\":\"forged\"}");
            JsonNode accepted = alice.read("BID_RESULT");
            assertTrue(accepted.path("accepted").asBoolean());
            JsonNode state = accepted.path("snapshot");
            assertEquals(aliceId, state.path("highestBidderId").asText());
            assertEquals("1050.00", state.path("minimumNextBid").asText());
            assertEquals(state, alice.read("AUCTION_UPDATE").path("snapshot"));
            assertEquals(state, bob.read("AUCTION_UPDATE").path("snapshot"));

            bob.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1049.99\"}");
            JsonNode rejected = bob.read("BID_RESULT");
            assertFalse(rejected.path("accepted").asBoolean());
            assertEquals("BID_TOO_LOW", rejected.path("reason").asText());
            assertEquals(state, rejected.path("snapshot"));
            // An ECHO following a rejection must not be preceded by a broadcast.
            alice.send("ECHO", "{}");
            alice.read("ECHO_RESPONSE");
            bob.close();
            alice.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1050.00\"}");
            assertTrue(alice.read("BID_RESULT").path("accepted").asBoolean());
            assertEquals(2, alice.read("AUCTION_UPDATE").path("snapshot").path("version").asLong());
            try (Peer newcomer = new Peer()) {
                assertEquals(2, newcomer.join("camera").path("snapshot").path("version").asLong());
            }
        }
    }

    @Test
    void invalidAuctionRequestsRecoverWithoutChangingState() throws Exception {
        try (Peer peer = new Peer()) {
            peer.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1000.00\"}");
            assertEquals("NOT_JOINED", peer.read("ERROR").path("reason").asText());
            peer.send("JOIN_AUCTION", "{\"auctionId\":\"missing\"}");
            assertEquals("AUCTION_NOT_FOUND", peer.read("ERROR").path("reason").asText());
            peer.send("JOIN_AUCTION", "{\"auctionId\":42}");
            assertEquals("INVALID_AUCTION_ID", peer.read("ERROR").path("reason").asText());
            String identity = peer.join("camera").path("bidderId").asText();
            for (String amount : List.of("1000", "null", "\"NaN\"", "\"1e100000\"")) {
                peer.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":" + amount + "}");
                assertEquals("INVALID_AMOUNT", peer.read("ERROR").path("reason").asText());
            }
            peer.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1000.001\"}");
            assertEquals("TOO_MANY_DECIMAL_PLACES", peer.read("BID_RESULT").path("reason").asText());
            JsonNode rejoined = peer.join("camera");
            assertEquals(identity, rejoined.path("bidderId").asText());
            assertEquals(0, rejoined.path("snapshot").path("version").asLong());
            peer.send("ECHO", "{\"message\":\"still usable\"}");
            assertEquals("still usable", peer.read("ECHO_RESPONSE").path("message").asText());
        }
    }

    @Test
    void concurrentBidsProduceWholeFramesAndOneConsistentVersion() throws Exception {
        try (Peer alice = new Peer(); Peer bob = new Peer();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            alice.join("camera");
            bob.join("camera");
            CyclicBarrier start = new CyclicBarrier(3);
            var first = executor.submit(() -> concurrentBid(alice, start));
            var second = executor.submit(() -> concurrentBid(bob, start));
            start.await(5, TimeUnit.SECONDS);
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            List<JsonNode> a = List.of(alice.readFrame(), alice.readFrame());
            List<JsonNode> b = List.of(bob.readFrame(), bob.readFrame());
            JsonNode aResult = find(a, "BID_RESULT");
            JsonNode bResult = find(b, "BID_RESULT");
            assertNotEquals(aResult.path("accepted").asBoolean(), bResult.path("accepted").asBoolean());
            JsonNode aState = find(a, "AUCTION_UPDATE").path("snapshot");
            JsonNode bState = find(b, "AUCTION_UPDATE").path("snapshot");
            assertEquals(aState, bState);
            assertEquals(1, aState.path("version").asLong());
            assertEquals(aState, aResult.path("snapshot"));
            assertEquals(aState, bResult.path("snapshot"));
        }
    }

    @Test
    void broadcastsOnlyReachSubscribersOfTheChangedAuction() throws Exception {
        try (Peer camera = new Peer(); Peer painting = new Peer()) {
            camera.join("camera");
            String identity = painting.join("painting").path("bidderId").asText();
            camera.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1000.00\"}");
            camera.read("BID_RESULT");
            camera.read("AUCTION_UPDATE");
            painting.send("ECHO", "{}");
            painting.read("ECHO_RESPONSE");
            painting.send("PLACE_BID", "{\"auctionId\":\"painting\",\"amount\":\"10.01\"}");
            assertEquals(identity, painting.read("BID_RESULT").path("snapshot").path("highestBidderId").asText());
            assertEquals("10.52", painting.read("AUCTION_UPDATE").path("snapshot").path("minimumNextBid").asText());
            camera.send("ECHO", "{}");
            camera.read("ECHO_RESPONSE");
        }
    }

    private Void concurrentBid(Peer peer, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        peer.send("PLACE_BID", "{\"auctionId\":\"camera\",\"amount\":\"1000.00\"}");
        return null;
    }

    private JsonNode find(List<JsonNode> frames, String type) {
        return frames.stream().filter(frame -> frame.path("type").asText().equals(type))
                .findFirst().orElseThrow().path("payload");
    }

    private final class Peer implements AutoCloseable {
        private final Socket socket = new Socket("127.0.0.1", server.getPort());
        private final BufferedReader input;

        private Peer() throws IOException {
            socket.setSoTimeout(5000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        private void send(String type, String payload) throws IOException {
            String line = "{\"type\":\"" + type + "\",\"payload\":" + payload + "}\n";
            socket.getOutputStream().write(line.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }

        private JsonNode join(String auctionId) throws IOException {
            send("JOIN_AUCTION", "{\"auctionId\":\"" + auctionId + "\"}");
            return read("AUCTION_STATE");
        }

        private JsonNode readFrame() throws IOException {
            String line = input.readLine();
            assertNotNull(line, "Unexpected server EOF");
            return mapper.readTree(line);
        }

        private JsonNode read(String type) throws IOException {
            JsonNode frame = readFrame();
            assertEquals(type, frame.path("type").asText(), frame.toString());
            return frame.path("payload");
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}

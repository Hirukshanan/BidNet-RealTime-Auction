package com.bidnet.client;

import com.bidnet.auction.AuctionEngine;
import com.bidnet.server.BidNetServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleClientTest {
    private BidNetServer server;

    @BeforeEach
    void startServer() throws IOException {
        AuctionEngine engine = new AuctionEngine();
        engine.createAuction("camera", new BigDecimal("1000.00"));
        server = new BidNetServer(0, engine);
        Thread.startVirtualThread(() -> {
            try {
                server.start();
            } catch (IOException ignored) {
                // A failed start surfaces as a failed connection in the test.
            }
        });
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    void showsStateResultsAndLiveUpdatesFromTheRealServer() throws Exception {
        try (Client alice = new Client(server.getPort()); Client bob = new Client(server.getPort())) {
            alice.type("join camera");
            alice.awaitOutput("[camera] OPEN | price 1000.00 | minimum next bid 1000.00 | leader none | version 0");
            bob.type("join camera");
            bob.awaitOutput("minimum next bid 1000.00");

            alice.type("bid 999.99");
            alice.awaitOutput("Bid rejected: BID_TOO_LOW");

            alice.type("bid 1000.00");
            alice.awaitOutput("Bid accepted.");
            alice.awaitOutput("minimum next bid 1050.00 | leader you | version 1");
            // Bob typed nothing after joining: the update must arrive on its own.
            bob.awaitOutput("Update: [camera] OPEN | price 1000.00 | minimum next bid 1050.00");

            alice.type("quit");
            assertEquals(ConsoleClient.EXIT_OK, alice.awaitExit());

            bob.type("bid 1050.00");
            bob.awaitOutput("minimum next bid 1102.50 | leader you | version 2");
        }
    }

    @Test
    void reportsServerErrorsAndLocalUsageWithoutDisconnecting() throws Exception {
        try (Client client = new Client(server.getPort())) {
            client.type("bid 1000.00");
            client.awaitOutput("Join an auction first");
            client.type("bid");
            client.awaitOutput("Usage: bid <amount>");
            client.type("join");
            client.awaitOutput("Usage: join <auctionId>");
            client.type("bogus");
            client.awaitOutput("Unknown command: bogus");
            client.type("HELP");
            client.awaitOutput("quit              Disconnect and exit");

            client.type("join missing");
            client.awaitOutput("Server error: AUCTION_NOT_FOUND");
            client.type("join camera");
            client.awaitOutput("Joined auction camera");
            client.type("bid 1e3");
            client.awaitOutput("Server error: INVALID_AMOUNT");
            client.type("bid 1000.00");
            client.awaitOutput("Bid accepted.");
        }
    }

    @Test
    void failedJoinKeepsTheCurrentAuctionForBidding() throws Exception {
        try (Client client = new Client(server.getPort())) {
            client.type("join camera");
            client.awaitOutput("Joined auction camera");

            client.type("join camra");
            client.awaitOutput("Server error: AUCTION_NOT_FOUND");

            client.type("bid 1100.00");
            client.awaitOutput("Bid accepted.");
            client.awaitOutput("[camera] OPEN | price 1100.00");
            assertFalse(client.output().contains("NOT_JOINED"), client.output());
        }
    }

    @Test
    void failedFirstJoinLeavesNoAuctionToBidOn() throws Exception {
        try (Client client = new Client(server.getPort())) {
            client.type("join missing");
            client.awaitOutput("Server error: AUCTION_NOT_FOUND");

            client.type("bid 1000.00");
            client.awaitOutput("Join an auction first");
        }
    }

    @Test
    void bidsFollowTheLatestConfirmedJoin() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            client.type("join camera");
            assertEquals(joinFrame("camera"), fake.readLine());

            // An update is not a join confirmation, even for the requested auction.
            fake.sendRaw(update(snapshot("OPEN", "1000.00", "1000.00", null, 0)) + "\n");
            client.awaitOutput("Update: [camera] OPEN");
            client.type("bid 1000.00");
            client.awaitOutput("Join an auction first");

            fake.sendRaw(state("you-id", snapshot("OPEN", "1000.00", "1000.00", null, 0)) + "\n");
            client.awaitOutput("Joined auction camera");
            client.type("bid 1000.00");
            assertEquals(bidFrame("camera", "1000.00"), fake.readLine());

            // A pending or failed join does not move bids away from the confirmed auction.
            client.type("join vase");
            assertEquals(joinFrame("vase"), fake.readLine());
            client.type("bid 1050.00");
            assertEquals(bidFrame("camera", "1050.00"), fake.readLine());
            fake.sendRaw("{\"type\":\"ERROR\",\"payload\":{\"reason\":\"AUCTION_NOT_FOUND\"}}\n");
            client.awaitOutput("Server error: AUCTION_NOT_FOUND");
            client.type("bid 1102.50");
            assertEquals(bidFrame("camera", "1102.50"), fake.readLine());

            fake.sendRaw("{\"type\":\"AUCTION_STATE\",\"payload\":{\"bidderId\":\"you-id\",\"snapshot\":"
                    + "{\"auctionId\":\"vase\",\"status\":\"OPEN\",\"version\":0}}}\n");
            client.awaitOutput("Joined auction vase");
            client.type("bid 20.00");
            assertEquals(bidFrame("vase", "20.00"), fake.readLine());

            // Rejoining counts even though its snapshot version is not newer. The reader
            // handles frames in order, so seeing the echo means the join was processed.
            fake.sendRaw(state("you-id", snapshot("OPEN", "1000.00", "1000.00", null, 0)) + "\n"
                    + "{\"type\":\"ECHO_RESPONSE\",\"payload\":{\"message\":\"rejoined\"}}\n");
            client.awaitOutput("rejoined");
            client.type("bid 1000.00");
            assertEquals(bidFrame("camera", "1000.00"), fake.readLine());
        }
    }

    @Test
    void malformedJoinConfirmationDoesNotChangeTheCurrentAuction() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw("{\"type\":\"AUCTION_STATE\",\"payload\":{\"snapshot\":{\"auctionId\":\"camera\"}}}\n");
            client.awaitOutput("Ignored a malformed auction snapshot.");
            client.type("bid 1000.00");
            client.awaitOutput("Join an auction first");
        }
    }

    @Test
    void preservesUnicodeAndQuotes() throws Exception {
        String text = "வணக்கம் \"hello\" \\ goodbye";
        try (Client client = new Client(server.getPort())) {
            client.type("echo " + text);
            client.awaitOutput("வணக்கம் \\\"hello\\\" \\\\ goodbye");
        }
    }

    @Test
    void exitsCleanlyWhenKeyboardInputEnds() throws Exception {
        try (Client client = new Client(server.getPort())) {
            client.keyboard.close();
            assertEquals(ConsoleClient.EXIT_OK, client.awaitExit());
            assertFalse(client.output().contains("connection"), client.output());
        }
    }

    @Test
    void reportsServerShutdownAndExits() throws Exception {
        try (Client client = new Client(server.getPort())) {
            client.type("join camera");
            client.awaitOutput("Joined auction camera");
            server.close();
            assertEquals(ConsoleClient.EXIT_FAILURE, client.awaitExit());
            assertTrue(client.output().contains("server closed the connection")
                    || client.output().contains("Lost the connection"), client.output());
        }
    }

    @Test
    void reportsAFailedConnection() throws Exception {
        int unusedPort;
        try (ServerSocket reserved = new ServerSocket(0)) {
            unusedPort = reserved.getLocalPort();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = ConsoleClient.run("127.0.0.1", unusedPort, new java.io.StringReader(""),
                new PrintStream(bytes, true, StandardCharsets.UTF_8));
        assertEquals(ConsoleClient.EXIT_FAILURE, exit);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Could not connect"));
    }

    @Test
    void handlesFramesInAnyOrderAndIgnoresOlderSnapshots() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            // Several frames in one write, with the bid outcome behind a newer update.
            fake.sendRaw(state("you-id", snapshot("OPEN", "1000.00", "1000.00", null, 0)) + "\n"
                    + update(snapshot("OPEN", "1102.50", "1157.63", "other-id", 3)) + "\n"
                    + "{\"type\":\"BID_RESULT\",\"payload\":{\"accepted\":true,\"reason\":null,\"snapshot\":"
                    + snapshot("OPEN", "1050.00", "1102.50", "you-id", 2) + "}}\n"
                    + update(snapshot("OPEN", "1050.00", "1102.50", "you-id", 2)) + "\n"
                    + update(snapshot("OPEN", "1102.50", "1157.63", "other-id", 3)) + "\n"
                    + "{\"type\":\"ECHO_RESPONSE\",\"payload\":{\"message\":\"end\"}}\n");
            client.awaitOutput("ECHO_RESPONSE");

            String output = client.output();
            assertTrue(output.contains("Bid accepted."), output);
            assertEquals(1, count(output, "price 1102.50"), "Equal version must not repeat: " + output);
            assertEquals(1, count(output, "version 3"), output);
            assertFalse(output.contains("version 2"), "Older snapshots must be ignored: " + output);
        }
    }

    @Test
    void rejectionShowsTheNewestKnownMinimumEvenWithAStaleSnapshot() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw(update(snapshot("OPEN", "1050.00", "1102.50", "other-id", 2)) + "\n"
                    + "{\"type\":\"BID_RESULT\",\"payload\":{\"accepted\":false,\"reason\":\"BID_TOO_LOW\",\"snapshot\":"
                    + snapshot("OPEN", "1000.00", "1050.00", "other-id", 1) + "}}\n");
            client.awaitOutput("Bid rejected: BID_TOO_LOW");
            client.awaitOutput("minimum next bid 1102.50");
            assertFalse(client.output().contains("minimum next bid 1050.00"), client.output());
        }
    }

    @Test
    void survivesMalformedAndUnknownFrames() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw("{broken\nnull\n[]\n{\"type\":\"NEW_THING\",\"payload\":{}}\n"
                    + "{\"type\":\"AUCTION_UPDATE\",\"payload\":{}}\n"
                    + "{\"type\":\"BID_RESULT\",\"payload\":{\"accepted\":false,\"reason\":\"BID_TOO_LOW\"}}\n"
                    + update(snapshot("OPEN", "1000.00", "1050.00", "other-id", 1)) + "\n");
            client.awaitOutput("Ignored an unsupported server message: NEW_THING");
            client.awaitOutput("Update: [camera] OPEN");
            assertEquals(3, count(client.output(), "Ignored a malformed server message."), client.output());
        }
    }

    @Test
    void showsFinalWinnerOrNoWinnerForClosedAuctions() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw(state("you-id", snapshot("OPEN", "1000.00", "1050.00", "you-id", 1)) + "\n"
                    + update(snapshot("CLOSED", "1000.00", "1050.00", "you-id", 2)) + "\n");
            client.awaitOutput("[camera] CLOSED | final price 1000.00 | winner you | version 2");
        }
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw(update(snapshot("CLOSED", "1000.00", "1000.00", null, 1)) + "\n");
            client.awaitOutput("[camera] CLOSED | no winner, no bids accepted | version 1");
        }
    }

    @Test
    void joinWithoutABidderIdDoesNotPrintNull() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw("{\"type\":\"AUCTION_STATE\",\"payload\":{\"snapshot\":"
                    + snapshot("OPEN", "1000.00", "1000.00", null, 0) + "}}\n");
            client.awaitOutput("[camera] OPEN");
            assertTrue(client.output().contains("Joined auction camera\n")
                    || client.output().contains("Joined auction camera" + System.lineSeparator()), client.output());
            assertFalse(client.output().contains("null"), client.output());
        }
    }

    /** The expiry feature (#7) announces closure with AUCTION_ENDED instead of AUCTION_UPDATE. */
    @Test
    void showsFinalStateFromAuctionEndedAnnouncement() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            fake.sendRaw(state("you-id", snapshot("OPEN", "1000.00", "1050.00", "you-id", 1)) + "\n"
                    + ended(snapshot("CLOSED", "1000.00", "1050.00", "you-id", 2)) + "\n");
            client.awaitOutput("Update: [camera] CLOSED | final price 1000.00 | winner you | version 2");
            assertFalse(client.output().contains("unsupported"), client.output());
        }
    }

    /** The baseline client gave up after five seconds; this must outlast that on purpose. */
    @Test
    void receivesUpdatesAfterAnIdlePeriodLongerThanTheOldTimeout() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            TimeUnit.MILLISECONDS.sleep(5500);
            assertFalse(client.exit.isDone(), "An idle client must stay connected: " + client.output());
            fake.sendRaw(update(snapshot("OPEN", "1000.00", "1050.00", "other-id", 1)) + "\n");
            client.awaitOutput("Update: [camera] OPEN");
        }
    }

    @Test
    void sendsCommandsAsNewlineDelimitedJsonWithObjectPayloads() throws Exception {
        try (ScriptedServer fake = new ScriptedServer(); Client client = new Client(fake.port())) {
            fake.accept();
            client.type("join camera \"1\"");
            assertEquals("{\"type\":\"JOIN_AUCTION\",\"payload\":{\"auctionId\":\"camera \\\"1\\\"\"}}", fake.readLine());
            fake.sendRaw("{\"type\":\"AUCTION_STATE\",\"payload\":{\"bidderId\":\"you-id\",\"snapshot\":"
                    + "{\"auctionId\":\"camera \\\"1\\\"\",\"status\":\"OPEN\",\"version\":0}}}\n");
            client.awaitOutput("Joined auction camera \"1\"");
            client.type("bid 1050.00");
            assertEquals("{\"type\":\"PLACE_BID\",\"payload\":{\"auctionId\":\"camera \\\"1\\\"\",\"amount\":\"1050.00\"}}",
                    fake.readLine());
        }
    }

    private static String joinFrame(String auctionId) {
        return "{\"type\":\"JOIN_AUCTION\",\"payload\":{\"auctionId\":\"" + auctionId + "\"}}";
    }

    private static String bidFrame(String auctionId, String amount) {
        return "{\"type\":\"PLACE_BID\",\"payload\":{\"auctionId\":\"" + auctionId + "\",\"amount\":\"" + amount + "\"}}";
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    private static String snapshot(String status, String price, String minimum, String bidder, long version) {
        return "{\"auctionId\":\"camera\",\"status\":\"" + status + "\",\"startingPrice\":\"1000.00\","
                + "\"currentPrice\":\"" + price + "\",\"minimumNextBid\":\"" + minimum + "\",\"highestBidderId\":"
                + (bidder == null ? "null" : "\"" + bidder + "\"") + ",\"version\":" + version + "}";
    }

    private static String update(String snapshot) {
        return "{\"type\":\"AUCTION_UPDATE\",\"payload\":{\"snapshot\":" + snapshot + "}}";
    }

    private static String ended(String snapshot) {
        return "{\"type\":\"AUCTION_ENDED\",\"payload\":{\"snapshot\":" + snapshot + "}}";
    }

    private static String state(String bidderId, String snapshot) {
        return "{\"type\":\"AUCTION_STATE\",\"payload\":{\"bidderId\":\"" + bidderId + "\",\"snapshot\":" + snapshot + "}}";
    }

    /** A real console session on a real socket, driven through its keyboard stream. */
    private static final class Client implements AutoCloseable {
        private final PipedWriter keyboard = new PipedWriter();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<Integer> exit;

        Client(int port) throws IOException {
            PipedReader input = new PipedReader(keyboard);
            PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
            exit = CompletableFuture.supplyAsync(() -> ConsoleClient.run("127.0.0.1", port, input, out));
        }

        void type(String line) throws IOException {
            keyboard.write(line + "\n");
            keyboard.flush();
        }

        String output() {
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }

        void awaitOutput(String expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!output().contains(expected)) {
                if (System.nanoTime() > deadline) {
                    fail("Expected output containing <" + expected + "> but got:\n" + output());
                }
                TimeUnit.MILLISECONDS.sleep(10);
            }
        }

        int awaitExit() throws Exception {
            return exit.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                keyboard.close();
            } catch (IOException ignored) {
                // Already closed by the test.
            }
            exit.get(5, TimeUnit.SECONDS);
        }
    }

    /** Sends exact frames, for orderings a correct server produces only by chance. */
    private static final class ScriptedServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0);
        private Socket socket;
        private PrintWriter writer;
        private BufferedReader reader;

        ScriptedServer() throws IOException {
        }

        int port() {
            return listener.getLocalPort();
        }

        void accept() throws IOException {
            listener.setSoTimeout(5000);
            socket = listener.accept();
            socket.setSoTimeout(5000);
            writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        void sendRaw(String text) {
            writer.print(text);
            writer.flush();
        }

        String readLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void close() throws IOException {
            if (socket != null) {
                socket.close();
            }
            listener.close();
        }
    }
}

package com.bidnet.client;

import com.bidnet.auction.AuctionEngine;
import com.bidnet.server.BidNetServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import javax.swing.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class GuiClientTest {
    private final List<GuiClient> clients = new ArrayList<>();
    private BidNetServer server;
    private Thread serverThread;
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        AuctionEngine engine = new AuctionEngine();
        engine.createAuction("demo", new BigDecimal("1000.00"), Instant.now().plusSeconds(3600));
        server = new BidNetServer(0, engine);
        serverThread = Thread.startVirtualThread(() -> {
            try { server.start(); } catch (Throwable failure) { serverFailure.set(failure); }
        });
    }
    @AfterEach void stop() throws Exception {
        SwingUtilities.invokeAndWait(() -> clients.forEach(GuiClient::disconnect));
        server.close(); serverThread.join(5000);
        assertFalse(serverThread.isAlive()); assertNull(serverFailure.get());
    }
    private GuiClient client() throws Exception {
        AtomicReference<GuiClient> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GuiClient panel = new GuiClient();
            panel.port.setText(String.valueOf(server.getPort()));
            clients.add(panel); result.set(panel); panel.connect.doClick();
        });
        GuiClient panel = result.get();
        await(() -> panel.join.isEnabled());
        SwingUtilities.invokeAndWait(() -> panel.join.doClick());
        await(() -> panel.bid.isEnabled());
        return panel;
    }
    @Test void liveUpdatesMinimumShortcutRejectionAndDisconnectRecovery() throws Exception {
        GuiClient a = client(); GuiClient b = client();
        SwingUtilities.invokeAndWait(() -> { a.amount.setText("999.99"); a.bid.doClick(); });
        await(() -> a.outcome.getText().contains("BID_TOO_LOW"));
        SwingUtilities.invokeAndWait(() -> { a.minimum.doClick(); assertEquals("1000.00", a.amount.getText()); a.bid.doClick(); });
        await(() -> a.price.getText().equals("1000.00") && a.leader.getText().endsWith("You")
                && b.nextBid.getText().equals("1050.00"));
        SwingUtilities.invokeAndWait(a::disconnect);
        SwingUtilities.invokeAndWait(() -> { b.minimum.doClick(); b.bid.doClick(); });
        await(() -> b.price.getText().equals("1050.00") && b.leader.getText().endsWith("You"));
        assertOnUi(() -> !a.bid.isEnabled());
    }
    @Test void failedJoinKeepsPreviousAuctionAndServerShutdownDisablesBidding() throws Exception {
        GuiClient a = client();
        SwingUtilities.invokeAndWait(() -> { a.auction.setText("typo"); a.join.doClick(); });
        await(() -> a.outcome.getText().contains("AUCTION_NOT_FOUND"));
        SwingUtilities.invokeAndWait(() -> { a.amount.setText("1100.00"); a.bid.doClick(); });
        await(() -> a.price.getText().equals("1100.00") && a.outcome.getText().contains("accepted"));
        assertOnUi(() -> a.auctionTitle.getText().replaceAll("\\s+", " ").equals("Auction / demo"));
        server.close();
        await(() -> a.connect.isEnabled() && !a.bid.isEnabled());
    }
    @Test void finalEventsAndStaleSnapshotsKeepTheLatestAuctionState() throws Exception {
        GuiClient a = client(); ObjectMapper mapper = new ObjectMapper();
        var open = mapper.readTree("{\"type\":\"AUCTION_UPDATE\",\"payload\":{\"snapshot\":{\"auctionId\":\"demo\",\"status\":\"OPEN\",\"version\":3,\"currentPrice\":\"1102.50\",\"minimumNextBid\":\"1157.63\",\"highestBidderId\":\"other\"}}}");
        var ended = mapper.readTree("{\"type\":\"AUCTION_ENDED\",\"payload\":{\"snapshot\":{\"auctionId\":\"demo\",\"status\":\"CLOSED\",\"version\":4,\"currentPrice\":\"1102.50\",\"minimumNextBid\":\"1157.63\",\"highestBidderId\":\"other\"}}}");
        SwingUtilities.invokeAndWait(() -> {
            a.receive(open); a.receive(ended); a.receive(open);
            assertEquals("1102.50", a.price.getText()); assertTrue(a.state.getText().endsWith("CLOSED"));
            assertEquals("Ended", a.countdown.getText()); assertFalse(a.bid.isEnabled());
            assertTrue(a.outcome.getText().contains("Winner: other"));
            assertTrue(a.outcome.getText().contains("1102.50"));
        });
        var noBids = mapper.readTree("{\"type\":\"AUCTION_STATE\",\"payload\":{\"bidderId\":\"self\",\"snapshot\":{\"auctionId\":\"empty\",\"status\":\"CLOSED\",\"version\":1,\"currentPrice\":\"1000.00\",\"highestBidderId\":null}}}");
        SwingUtilities.invokeAndWait(() -> {
            a.receive(noBids); assertTrue(a.outcome.getText().contains("no winner")); assertFalse(a.bid.isEnabled());
        });
    }
    @Test void badInputAndReconnectDoNotLeakThePreviousSessionState() throws Exception {
        AtomicReference<GuiClient> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GuiClient panel = new GuiClient(); clients.add(panel); result.set(panel);
            panel.port.setText("bad"); panel.connect.doClick();
            assertTrue(panel.outcome.getText().contains("65535")); assertTrue(panel.connect.isEnabled());
        });
        GuiClient a = client();
        SwingUtilities.invokeAndWait(() -> { a.amount.setText("not-money"); a.bid.doClick(); });
        assertOnUi(() -> a.outcome.getText().contains("decimal"));
        SwingUtilities.invokeAndWait(() -> { a.disconnect(); a.connect.doClick(); });
        await(() -> a.join.isEnabled());
        assertOnUi(() -> !a.bid.isEnabled() && a.price.getText().equals("—"));
        SwingUtilities.invokeAndWait(() -> a.join.doClick());
        await(() -> a.bid.isEnabled());
    }
    @Test void refusedConnectionReportsMatchingPortAndAllowsRetry() throws Exception {
        int unavailablePort = server.getPort();
        server.close(); serverThread.join(5000);
        AtomicReference<GuiClient> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GuiClient panel = new GuiClient(); clients.add(panel); ref.set(panel);
            assertEquals("5051", panel.port.getText());
            panel.port.setText(String.valueOf(unavailablePort)); panel.connect.doClick();
        });
        GuiClient panel = ref.get();
        await(() -> panel.connect.isEnabled() && panel.outcome.getText().contains("same port"));
        assertOnUi(() -> !panel.bid.isEnabled() && !panel.disconnect.isEnabled());
    }

    @Test void selectedDiscoveredServerFillsConnectionFieldsAndConnects() throws Exception {
        AtomicReference<GuiClient> ref = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            GuiClient panel = new GuiClient(); clients.add(panel); ref.set(panel);
            panel.availableServers.addItem(new com.bidnet.network.ServerDiscovery.Server("127.0.0.1",server.getPort()));
            assertEquals("127.0.0.1",panel.host.getText());
            assertEquals(String.valueOf(server.getPort()),panel.port.getText());
            panel.connect.doClick();
        });
        GuiClient panel = ref.get();
        await(() -> panel.join.isEnabled());
        assertOnUi(() -> !panel.findServers.isEnabled());
        SwingUtilities.invokeAndWait(() -> panel.join.doClick());
        await(() -> panel.bid.isEnabled());
        SwingUtilities.invokeAndWait(panel::disconnect);
        assertOnUi(() -> panel.findServers.isEnabled());
    }

    private void assertOnUi(BooleanSupplier condition) throws Exception {
        SwingUtilities.invokeAndWait(() -> assertTrue(condition.getAsBoolean()));
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> ready.set(condition.getAsBoolean()));
            if (ready.get()) return;
            Thread.sleep(10);
        }
        fail("GUI did not reach expected state within seven seconds");
    }
}

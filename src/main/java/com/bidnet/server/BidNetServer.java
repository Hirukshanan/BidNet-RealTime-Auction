package com.bidnet.server;

import com.bidnet.auction.AuctionEngine;
import com.bidnet.network.ServerDiscovery;
import com.bidnet.auction.AuctionNotFoundException;
import com.bidnet.auction.AuctionSnapshot;
import com.bidnet.auction.AuctionStatus;
import com.bidnet.auction.BidResult;
import com.bidnet.auction.CloseResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BidNetServer implements AutoCloseable {
    private final ServerSocket listener;
    private final AuctionEngine engine;
    private final Clock clock;
    private final ScheduledExecutorService expiryScheduler;
    private final Set<Session> clients = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public BidNetServer(int port) throws IOException {
        this(port, Clock.systemUTC());
    }

    private BidNetServer(int port, Clock clock) throws IOException {
        this(port, demoEngine(clock), clock);
    }

    public BidNetServer(int port, AuctionEngine engine) throws IOException {
        this(port, engine, Clock.systemUTC());
    }

    public BidNetServer(int port, AuctionEngine engine, Clock clock) throws IOException {
        this(port, engine, clock, Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon(true).name("bidnet-expiry").unstarted(runnable)));
    }

    BidNetServer(
            int port,
            AuctionEngine engine,
            Clock clock,
            ScheduledExecutorService expiryScheduler) throws IOException {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.expiryScheduler = Objects.requireNonNull(expiryScheduler, "expiryScheduler");
        listener = new ServerSocket(port);
        for (String auctionId : engine.auctionIds()) {
            scheduleExpiry(auctionId);
        }
    }

    private static AuctionEngine demoEngine(Clock clock) {
        AuctionEngine engine = new AuctionEngine(clock);
        engine.createAuction(
                "demo",
                new BigDecimal("1000.00"),
                clock.instant().plus(Duration.ofMinutes(10)));
        return engine;
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    public void start() throws IOException {
        while (!listener.isClosed()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (SocketException failure) {
                if (listener.isClosed()) {
                    return;
                }
                throw failure;
            }
            Session session = new Session(socket);
            clients.add(session);
            if (listener.isClosed()) {
                session.close();
                return;
            }
            Thread.startVirtualThread(() -> handleClient(session));
        }
    }

    private void handleClient(Session session) {
        Thread writer = Thread.startVirtualThread(session::writeMessages);
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                session.socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!session.closed.get() && (line = input.readLine()) != null) {
                handleMessage(session, line);
            }
        } catch (IOException failure) {
            // EOF, reset and failed writes affect only this connection.
        } finally {
            session.close();
            writer.interrupt();
        }
    }

    private void handleMessage(Session session, String line) {
        try {
            JsonNode request = mapper.readTree(line);
            if (request == null || !request.isObject() || !request.path("type").isTextual()
                    || !request.path("payload").isObject()) {
                session.send(error("INVALID_MESSAGE"));
                return;
            }
            JsonNode payload = request.get("payload");
            String type = request.get("type").textValue();
            if ("ECHO".equals(type)) {
                ObjectNode response = message("ECHO_RESPONSE");
                response.set("payload", payload);
                session.send(response);
                return;
            }
            if (!"JOIN_AUCTION".equals(type) && !"PLACE_BID".equals(type)) {
                session.send(error("UNSUPPORTED_TYPE"));
                return;
            }
            JsonNode id = payload.path("auctionId");
            if (!id.isTextual() || id.textValue().isBlank()) {
                session.send(error("INVALID_AUCTION_ID"));
                return;
            }
            String auctionId = id.textValue();
            if ("JOIN_AUCTION".equals(type)) {
                // Subscribe before reading state: concurrent updates may precede this reply.
                engine.snapshot(auctionId); // Validate before adding a subscription.
                session.subscriptions.add(auctionId);
                ObjectNode response = message("AUCTION_STATE");
                ObjectNode body = response.putObject("payload");
                body.put("bidderId", session.bidderId);
                body.set("snapshot", snapshot(engine.snapshot(auctionId)));
                session.send(response);
                return;
            }
            if (!session.subscriptions.contains(auctionId)) {
                session.send(error("NOT_JOINED"));
                return;
            }
            JsonNode amount = payload.path("amount");
            if (!amount.isTextual() || amount.textValue().length() > 64
                    || !amount.textValue().matches("-?[0-9]+(\\.[0-9]+)?")) {
                session.send(error("INVALID_AMOUNT"));
                return;
            }
            BidResult result = engine.placeBid(auctionId, session.bidderId,
                    new BigDecimal(amount.textValue()));
            ObjectNode response = message("BID_RESULT");
            ObjectNode body = response.putObject("payload");
            body.put("accepted", result.accepted());
            if (result.rejectionReason() == null) {
                body.putNull("reason");
            } else {
                body.put("reason", result.rejectionReason().name());
            }
            body.set("snapshot", snapshot(result.snapshot()));
            session.send(response);
            if (result.accepted()) {
                broadcast("AUCTION_UPDATE", result.snapshot());
            } else if (result.closedNow()) {
                broadcast("AUCTION_ENDED", result.snapshot());
            }
        } catch (JsonProcessingException failure) {
            session.send(error("INVALID_JSON"));
        } catch (AuctionNotFoundException failure) {
            session.send(error("AUCTION_NOT_FOUND"));
        }
    }

    private ObjectNode snapshot(AuctionSnapshot state) {
        ObjectNode json = mapper.createObjectNode();
        json.put("auctionId", state.auctionId());
        json.put("status", state.status().name());
        json.put("startingPrice", state.startingPrice().toPlainString());
        json.put("currentPrice", state.currentPrice().toPlainString());
        json.put("minimumNextBid", state.minimumNextBid().toPlainString());
        json.put("highestBidderId", state.highestBidderId());
        json.put("deadline", state.deadline().toString());
        json.put("version", state.version());
        return json;
    }

    private void broadcast(String type, AuctionSnapshot state) {
        ObjectNode event = message(type);
        event.putObject("payload").set("snapshot", snapshot(state));
        for (Session subscriber : clients) {
            if (subscriber.subscriptions.contains(state.auctionId())) {
                subscriber.send(event);
            }
        }
    }

    private void scheduleExpiry(String auctionId) {
        if (expiryScheduler.isShutdown()) {
            return;
        }
        AuctionSnapshot state = engine.snapshot(auctionId);
        if (state.status() != AuctionStatus.OPEN) {
            return;
        }
        Duration remaining = Duration.between(clock.instant(), state.deadline());
        long delayNanos;
        if (remaining.isNegative() || remaining.isZero()) {
            delayNanos = 0;
        } else {
            try {
                delayNanos = remaining.toNanos();
            } catch (ArithmeticException overflow) {
                delayNanos = Long.MAX_VALUE;
            }
        }
        try {
            expiryScheduler.schedule(() -> expireAuction(auctionId), delayNanos, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException ignored) {
            // Server shutdown won the race with scheduling.
        }
    }

    void expireAuction(String auctionId) {
        if (expiryScheduler.isShutdown()) {
            return;
        }
        CloseResult result = engine.closeIfExpired(auctionId);
        if (result.closedNow()) {
            broadcast("AUCTION_ENDED", result.snapshot());
        } else if (result.snapshot().status() == AuctionStatus.OPEN) {
            // A wall-clock adjustment or an early callback must not close the auction early.
            scheduleExpiry(auctionId);
        }
    }

    boolean expirySchedulerIsShutdown() {
        return expiryScheduler.isShutdown();
    }

    private ObjectNode message(String type) {
        return mapper.createObjectNode().put("type", type);
    }

    private ObjectNode error(String reason) {
        ObjectNode response = message("ERROR");
        response.putObject("payload").put("reason", reason);
        return response;
    }

    private final class Session {
        private final Socket socket;
        private final String bidderId = UUID.randomUUID().toString();
        private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        // Bound memory per slow client; disconnect it instead of blocking other bidders.
        private final ArrayBlockingQueue<String> outbound = new ArrayBlockingQueue<>(256);
        private final AtomicBoolean closed = new AtomicBoolean();

        private Session(Socket socket) {
            this.socket = socket;
        }

        private void send(JsonNode message) {
            if (!closed.get() && !outbound.offer(message.toString())) {
                close();
            }
        }

        private void writeMessages() {
            try (BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8))) {
                while (!closed.get()) {
                    String line = outbound.take();
                    output.write(line);
                    output.newLine();
                    output.flush();
                }
            } catch (IOException failure) {
                // Closing this socket also releases its reader.
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                clients.remove(this);
                subscriptions.clear();
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Continue cleanup of the other clients.
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        expiryScheduler.shutdownNow();
        try {
            listener.close();
        } finally {
            for (Session client : clients) {
                client.close();
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5051;
        try (BidNetServer server = new BidNetServer(port)) {
            System.out.println("BidNet server listening on port " + server.getPort());
            System.out.println("Demo auction: demo (starting price 1000.00)");
            ServerDiscovery discovery = null;
            try {
                discovery = new ServerDiscovery(server.getPort());
                System.out.println("LAN discovery listening on UDP port " + ServerDiscovery.PORT);
            } catch (SocketException unavailable) {
                System.err.println("LAN discovery unavailable. Clients can still enter the server host and TCP port manually.");
            }
            try {
                server.start();
            } finally {
                if (discovery != null) discovery.close();
            }
        }
    }
}

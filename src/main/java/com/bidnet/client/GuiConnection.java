package com.bidnet.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One reader and an ordered background writer; no socket work on the Swing thread. */
final class GuiConnection implements AutoCloseable {
    private final Socket socket = new Socket();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("bidnet-gui-writer").factory());
    private final Consumer<JsonNode> frames;
    private final Consumer<String> disconnected;
    private BufferedWriter output;

    GuiConnection(Consumer<JsonNode> frames, Consumer<String> disconnected) {
        this.frames = frames;
        this.disconnected = disconnected;
    }

    void connect(String host, int port, Runnable connected) {
        Thread.startVirtualThread(() -> {
            String reason = "Server closed the connection.";
            try {
                socket.connect(new InetSocketAddress(host, port), 3000);
                output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                if (closed.get()) return;
                connected.run();
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (!closed.get() && (line = input.readLine()) != null) {
                    frames.accept(mapper.readTree(line));
                }
            } catch (ConnectException failure) {
                reason = "Cannot connect to " + host + ":" + port
                        + ". Start the server and use the same port in both applications.";
            } catch (UnknownHostException failure) {
                reason = "Unknown server host: " + host + ". Check the server address.";
            } catch (SocketTimeoutException failure) {
                reason = "Connection timed out to " + host + ":" + port
                        + ". Check the address, matching port and network access.";
            } catch (IOException | RuntimeException failure) {
                reason = "Connection ended: " + failure.getMessage();
            } finally {
                boolean report = !closed.get();
                close();
                if (report) disconnected.accept(reason);
            }
        });
    }

    void send(String type, String auctionId, String amount) {
        if (closed.get()) return;
        var frame = mapper.createObjectNode().put("type", type);
        var payload = frame.putObject("payload").put("auctionId", auctionId);
        if (amount != null) payload.put("amount", amount);
        try {
            writer.submit(() -> {
                try {
                    output.write(frame.toString());
                    output.newLine();
                    output.flush();
                } catch (IOException failure) {
                    if (!closed.get()) disconnected.accept("Could not send: " + failure.getMessage());
                    close();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Disconnect won the race with a click.
        }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            writer.shutdownNow();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}

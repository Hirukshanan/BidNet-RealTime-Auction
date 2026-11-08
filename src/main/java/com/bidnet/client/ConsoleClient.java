package com.bidnet.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Live auction console. One background thread owns all socket reads, so server
 * updates appear while the keyboard is idle; the socket has no read timeout.
 */
public final class ConsoleClient {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    private static final int EXIT_USAGE = 2;
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream out;
    private final CompletableFuture<Integer> finished = new CompletableFuture<>();
    private Writer server;
    /** Written by the socket reader once the server confirms a join; read by the keyboard thread. */
    private volatile String currentAuction;

    private ConsoleClient(PrintStream out) {
        this.out = out;
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port;
        try {
            port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        } catch (NumberFormatException invalid) {
            System.err.println("Usage: ConsoleClient [host] [port]");
            System.exit(EXIT_USAGE);
            return;
        }
        // Follow the terminal's encoding; piped input and output use UTF-8.
        Charset charset = System.console() != null ? System.console().charset() : StandardCharsets.UTF_8;
        Reader keyboard = new InputStreamReader(new FileInputStream(FileDescriptor.in), charset);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, charset);
        // Exit explicitly: a read blocked on the keyboard cannot be interrupted portably.
        System.exit(run(host, port, keyboard, out));
    }

    /** Runs a session until quit, keyboard end-of-input or connection loss; returns the exit code. */
    static int run(String host, int port, Reader keyboard, PrintStream out) {
        return new ConsoleClient(out).session(host, port, keyboard);
    }

    private int session(String host, int port, Reader keyboard) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setKeepAlive(true);
            server = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader frames = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.println("Connected to " + host + ":" + port);
            out.println("Type help for commands.");
            startDaemon("bidnet-socket-reader", () -> receive(frames));
            startDaemon("bidnet-keyboard", () -> readCommands(keyboard));
            return finished.get();
        } catch (IOException failure) {
            out.println("Could not connect to " + host + ":" + port + ": " + failure.getMessage());
            return EXIT_FAILURE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return EXIT_FAILURE;
        } catch (ExecutionException unexpected) {
            out.println("Client failed: " + unexpected.getCause());
            return EXIT_FAILURE;
        }
    }

    private static void startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private void receive(BufferedReader frames) {
        AuctionDisplay display = new AuctionDisplay(out, auctionId -> currentAuction = auctionId);
        try {
            String line;
            while ((line = frames.readLine()) != null) {
                display.handle(line);
            }
            if (!finished.isDone()) {
                out.println("The server closed the connection.");
            }
        } catch (IOException failure) {
            if (!finished.isDone()) {
                out.println("Lost the connection to the server: " + failure.getMessage());
            }
        } catch (RuntimeException unexpected) {
            finished.completeExceptionally(unexpected);
        }
        finished.complete(EXIT_FAILURE);
    }

    private void readCommands(Reader keyboard) {
        try {
            BufferedReader lines = new BufferedReader(keyboard);
            String line;
            while (!finished.isDone() && (line = lines.readLine()) != null) {
                if (!execute(line)) {
                    break;
                }
            }
            finished.complete(EXIT_OK);
        } catch (IOException failure) {
            if (!finished.isDone()) {
                out.println("Could not send the command: " + failure.getMessage());
            }
            finished.complete(EXIT_FAILURE);
        } catch (RuntimeException unexpected) {
            finished.completeExceptionally(unexpected);
        }
    }

    /** Returns false when the user asked to quit. */
    private boolean execute(String line) throws IOException {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return true;
        }
        String[] parts = trimmed.split("\\s+", 2);
        String argument = parts.length > 1 ? parts[1] : "";
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "help" -> printHelp();
            case "quit" -> {
                return false;
            }
            case "join" -> join(argument);
            case "bid" -> bid(argument);
            case "echo" -> echo(argument);
            default -> out.println("Unknown command: " + parts[0] + ". Type help for commands.");
        }
        return true;
    }

    private void printHelp() {
        out.println("Commands:");
        out.println("  join <auctionId>  Join an auction and show its current state");
        out.println("  bid <amount>      Bid on the auction joined most recently, for example bid 1050.00");
        out.println("  echo <text>       Test the connection");
        out.println("  help              Show this list");
        out.println("  quit              Disconnect and exit");
    }

    private void join(String auctionId) throws IOException {
        if (auctionId.isEmpty()) {
            out.println("Usage: join <auctionId>");
            return;
        }
        ObjectNode request = frame("JOIN_AUCTION");
        request.with("payload").put("auctionId", auctionId);
        send(request);
    }

    private void bid(String amount) throws IOException {
        String auctionId = currentAuction;
        if (amount.isEmpty() || amount.chars().anyMatch(Character::isWhitespace)) {
            out.println("Usage: bid <amount>");
        } else if (auctionId == null) {
            out.println("Join an auction first: join <auctionId>");
        } else {
            // The amount stays a string: the server alone validates money.
            ObjectNode request = frame("PLACE_BID");
            request.with("payload").put("auctionId", auctionId).put("amount", amount);
            send(request);
        }
    }

    private void echo(String text) throws IOException {
        if (text.isEmpty()) {
            out.println("Usage: echo <text>");
            return;
        }
        ObjectNode request = frame("ECHO");
        request.with("payload").put("message", text);
        send(request);
    }

    private ObjectNode frame(String type) {
        ObjectNode request = mapper.createObjectNode().put("type", type);
        request.putObject("payload");
        return request;
    }

    private void send(ObjectNode request) throws IOException {
        server.write(request.toString());
        server.write('\n');
        server.flush();
    }
}

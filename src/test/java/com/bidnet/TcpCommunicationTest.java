package com.bidnet;

import com.bidnet.server.BidNetServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TcpCommunicationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private BidNetServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        server = new BidNetServer(0);
        serverThread = Thread.startVirtualThread(() -> {
            try {
                server.start();
            } catch (IOException failure) {
                serverFailure.set(failure);
            }
        });
    }

    @AfterEach
    void stopServer() throws Exception {
        server.close();
        serverThread.join(5000);
        assertFalse(serverThread.isAlive(), "The accept loop must stop when the server closes");
        assertNull(serverFailure.get(), "The server must not fail during shutdown");
    }

    @Test
    void twoClientsExchangeIndependentMessagesWhileBothStayConnected() throws Exception {
        try (Socket first = connect(); Socket second = connect();
             BufferedReader firstInput = input(first); BufferedReader secondInput = input(second)) {
            sendEcho(first, "First client says வணக்கம்");
            sendEcho(second, "Second client says \"hello\" \\ goodbye");
            assertEcho(firstInput, "First client says வணக்கம்");
            assertEcho(secondInput, "Second client says \"hello\" \\ goodbye");
            sendEcho(first, "Another message on the same connection");
            assertEcho(firstInput, "Another message on the same connection");
        }
    }

    @Test
    void disconnectingOneClientDoesNotStopOtherOrNewClients() throws Exception {
        try (Socket departing = connect(); Socket remaining = connect();
             BufferedReader remainingInput = input(remaining)) {
            departing.close();
            sendEcho(remaining, "Still connected");
            assertEcho(remainingInput, "Still connected");
            try (Socket newcomer = connect(); BufferedReader newcomerInput = input(newcomer)) {
                sendEcho(newcomer, "New connection");
                assertEcho(newcomerInput, "New connection");
            }
        }
    }

    @Test
    void badMessagesReturnErrorsAndTheConnectionRemainsUsable() throws Exception {
        String[][] cases = {
                {"{broken", "INVALID_JSON"},
                {"null", "INVALID_MESSAGE"},
                {"[]", "INVALID_MESSAGE"},
                {"{}", "INVALID_MESSAGE"},
                {"{\"type\":\"ECHO\",\"payload\":\"text\"}", "INVALID_MESSAGE"},
                {"{\"type\":42,\"payload\":{}}", "INVALID_MESSAGE"},
                {"{\"type\":\"UNKNOWN\",\"payload\":{}}", "UNSUPPORTED_TYPE"},
                {"{\"type\":\"ECHO\",\"payload\":{}} {}", "INVALID_JSON"}
        };
        try (Socket client = connect(); BufferedReader reader = input(client)) {
            for (String[] example : cases) {
                send(client, example[0]);
                JsonNode response = read(reader);
                assertEquals("ERROR", response.path("type").asText());
                assertEquals(example[1], response.path("payload").path("reason").asText());
            }
            sendEcho(client, "Valid after errors");
            assertEcho(reader, "Valid after errors");
        }
    }

    @Test
    void closingTheServerDisconnectsIdleClients() throws Exception {
        try (Socket client = connect(); BufferedReader reader = input(client)) {
            sendEcho(client, "Connected before shutdown");
            assertEcho(reader, "Connected before shutdown");
            server.close();
            assertNull(reader.readLine(), "Server shutdown must close idle connections");
        }
    }

    @Test
    void consoleClientSendsInputAndExitsOnQuit() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process client = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "com.bidnet.client.ConsoleClient", "127.0.0.1", String.valueOf(server.getPort()))
                .redirectErrorStream(true).start();
        try {
            // Stops the blocking reads below if the client never responds.
            CompletableFuture.delayedExecutor(15, TimeUnit.SECONDS).execute(client::destroyForcibly);
            BufferedWriter keyboard = new BufferedWriter(
                    new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader output = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            keyboard.write("echo Hello from the console\n");
            keyboard.flush();
            // Replies arrive independently, so wait for this one before quitting.
            String line;
            while ((line = output.readLine()) != null && !line.startsWith("Server: ")) {
                // Skip the connection banner.
            }
            assertNotNull(line, "Console did not display a server response");
            JsonNode message = mapper.readTree(line.substring("Server: ".length()));
            assertEquals("ECHO_RESPONSE", message.path("type").asText());
            assertEquals("Hello from the console", message.path("payload").path("message").asText());
            keyboard.write("quit\n");
            keyboard.flush();
            assertTrue(client.waitFor(10, TimeUnit.SECONDS), "Console client must exit after quit");
            assertEquals(0, client.exitValue());
        } finally {
            client.destroyForcibly();
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 5000);
        socket.setSoTimeout(5000);
        return socket;
    }

    private BufferedReader input(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private void send(Socket socket, String json) throws IOException {
        socket.getOutputStream().write((json + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private void sendEcho(Socket socket, String message) throws IOException {
        var request = mapper.createObjectNode().put("type", "ECHO");
        request.putObject("payload").put("message", message);
        send(socket, request.toString());
    }

    private JsonNode read(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        assertNotNull(line, "Server closed the connection without responding");
        return mapper.readTree(line);
    }

    private void assertEcho(BufferedReader reader, String expectedMessage) throws IOException {
        JsonNode response = read(reader);
        assertEquals("ECHO_RESPONSE", response.path("type").asText());
        assertEquals(expectedMessage, response.path("payload").path("message").asText());
    }
}

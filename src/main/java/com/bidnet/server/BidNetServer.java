package com.bidnet.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BidNetServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public BidNetServer(int port) throws IOException {
        listener = new ServerSocket(port);
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    public void start() throws IOException {
        while (!listener.isClosed()) {
            Socket client;
            try {
                client = listener.accept();
            } catch (SocketException failure) {
                if (listener.isClosed()) {
                    return;
                }
                throw failure;
            }
            clients.add(client);
            if (listener.isClosed()) {
                clients.remove(client);
                client.close();
                return;
            }
            Thread.startVirtualThread(() -> handleClient(client));
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     client.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = input.readLine()) != null) {
                output.println(reply(line));
                if (output.checkError()) {
                    break;
                }
            }
        } catch (IOException failure) {
            if (!listener.isClosed()) {
                System.err.println("A client connection ended unexpectedly.");
            }
        } finally {
            clients.remove(client);
        }
    }

    private JsonNode reply(String line) {
        try {
            JsonNode request = mapper.readTree(line);
            if (request == null || !request.isObject() || !request.path("type").isTextual()
                    || !request.path("payload").isObject()) {
                return error("INVALID_MESSAGE");
            }
            if (!"ECHO".equals(request.get("type").textValue())) {
                return error("UNSUPPORTED_TYPE");
            }
            ObjectNode response = mapper.createObjectNode().put("type", "ECHO_RESPONSE");
            response.set("payload", request.get("payload"));
            return response;
        } catch (JsonProcessingException failure) {
            return error("INVALID_JSON");
        }
    }

    private ObjectNode error(String reason) {
        ObjectNode response = mapper.createObjectNode().put("type", "ERROR");
        response.putObject("payload").put("reason", reason);
        return response;
    }

    @Override
    public void close() throws IOException {
        try {
            listener.close();
        } finally {
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (IOException ignored) {
                    // Continue closing the other clients.
                }
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        try (BidNetServer server = new BidNetServer(port)) {
            System.out.println("BidNet server listening on port " + server.getPort());
            server.start();
        }
    }
}

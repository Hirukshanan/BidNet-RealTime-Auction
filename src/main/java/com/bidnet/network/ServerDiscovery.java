package com.bidnet.network;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Small IPv4 LAN discovery protocol. Auction data still uses the existing TCP connection. */
public final class ServerDiscovery implements AutoCloseable {
    public static final int PORT = 5052;
    private static final String SERVICE = "BIDNET_DISCOVERY_V1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatagramSocket socket;
    private final Thread responder;

    public record Server(String host, int port) {
        @Override public String toString() { return "BidNet server — " + host + ":" + port; }
    }

    public ServerDiscovery(int tcpPort) throws SocketException {
        this(tcpPort, PORT);
    }

    // Package access allows isolated tests on OS-selected UDP ports.
    ServerDiscovery(int tcpPort, int discoveryPort) throws SocketException {
        if (tcpPort < 1 || tcpPort > 65535) throw new IllegalArgumentException("Invalid TCP port");
        socket = new DatagramSocket(discoveryPort);
        responder = Thread.startVirtualThread(() -> respond(tcpPort));
    }

    int port() { return socket.getLocalPort(); }

    private void respond(int tcpPort) {
        while (!socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
            try {
                socket.receive(packet);
                JsonNode request;
                try { request = JSON.readTree(packet.getData(), 0, packet.getLength()); }
                catch (IOException invalid) { continue; }
                if (request == null || !SERVICE.equals(request.path("service").asText())
                        || !"FIND".equals(request.path("type").asText())
                        || !request.path("nonce").isTextual()
                        || request.path("nonce").asText().length() > 64) continue;
                byte[] reply = JSON.createObjectNode().put("service", SERVICE).put("type", "SERVER")
                        .put("nonce", request.path("nonce").asText()).put("port", tcpPort)
                        .toString().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
            } catch (IOException failure) {
                if (!socket.isClosed()) System.err.println("LAN discovery request failed; TCP remains available.");
            }
        }
    }

    public static List<Server> find(Duration timeout) throws IOException {
        Set<InetAddress> targets = new LinkedHashSet<>();
        targets.add(InetAddress.getByName("127.0.0.1"));
        targets.add(InetAddress.getByName("255.255.255.255"));
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) targets.add(address.getBroadcast());
            }
        }
        return find(timeout, targets, PORT);
    }

    static List<Server> find(Duration timeout, Collection<InetAddress> targets, int discoveryPort) throws IOException {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(10)) > 0)
            throw new IllegalArgumentException("Discovery timeout must be between zero and ten seconds");
        String nonce = UUID.randomUUID().toString();
        byte[] request = JSON.createObjectNode().put("service", SERVICE).put("type", "FIND")
                .put("nonce", nonce).toString().getBytes(StandardCharsets.UTF_8);
        Map<String, Server> found = new LinkedHashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (DatagramSocket client = new DatagramSocket()) {
            client.setBroadcast(true);
            for (InetAddress target : targets) {
                try { client.send(new DatagramPacket(request, request.length, target, discoveryPort)); }
                catch (IOException unavailable) { /* Other network interfaces may still work. */ }
            }
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                client.setSoTimeout((int)Math.max(1, Math.min(remaining / 1_000_000, 250)));
                DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
                try {
                    client.receive(packet);
                    JsonNode reply;
                    try { reply = JSON.readTree(packet.getData(), 0, packet.getLength()); }
                    catch (IOException invalid) { continue; }
                    if (reply == null || !SERVICE.equals(reply.path("service").asText())
                            || !"SERVER".equals(reply.path("type").asText())
                            || !nonce.equals(reply.path("nonce").asText())
                            || !reply.path("port").isInt()) continue;
                    int tcpPort = reply.path("port").intValue();
                    if (tcpPort < 1 || tcpPort > 65535) continue;
                    Server server = new Server(packet.getAddress().getHostAddress(), tcpPort);
                    found.put(server.host() + ":" + tcpPort, server);
                } catch (SocketTimeoutException noReplyYet) { /* Bounded discovery window. */ }
            }
        }
        return List.copyOf(found.values());
    }

    @Override public void close() { socket.close(); }
}

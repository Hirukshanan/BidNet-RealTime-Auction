package com.bidnet.network;

import com.bidnet.server.BidNetServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerDiscoveryTest {
    private final InetAddress loopback = InetAddress.getLoopbackAddress();

    @Test void discoversCustomTcpPortAndJoinsTheRealServer() throws Exception {
        try (BidNetServer server = new BidNetServer(0);
             ServerDiscovery beacon = new ServerDiscovery(server.getPort(), 0)) {
            Thread accept = Thread.startVirtualThread(() -> {
                try { server.start(); } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            try {
                List<ServerDiscovery.Server> found = ServerDiscovery.find(Duration.ofMillis(200), List.of(loopback), beacon.port());
                assertEquals(1, found.size());
                assertEquals(server.getPort(), found.getFirst().port());
                try (Socket client = new Socket(found.getFirst().host(), found.getFirst().port())) {
                    client.setSoTimeout(3000);
                    PrintWriter output = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);
                    BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    output.println("{\"type\":\"JOIN_AUCTION\",\"payload\":{\"auctionId\":\"demo\"}}");
                    var reply = new ObjectMapper().readTree(input.readLine());
                    assertEquals("AUCTION_STATE", reply.path("type").asText());
                    assertEquals("demo", reply.path("payload").path("snapshot").path("auctionId").asText());
                }
            } finally { server.close(); accept.join(3000); assertFalse(accept.isAlive()); }
        }
    }

    @Test void ignoresMalformedUnrelatedAndInvalidRepliesAndDeduplicatesServers() throws Exception {
        try (DatagramSocket fake = new DatagramSocket(0)) {
            Thread responder = Thread.startVirtualThread(() -> {
                try {
                    DatagramPacket query = new DatagramPacket(new byte[1024], 1024);
                    fake.receive(query);
                    String nonce = new ObjectMapper().readTree(query.getData(),0,query.getLength()).path("nonce").asText();
                    for (String reply : List.of("{broken", "null",
                            "{\"service\":\"BIDNET_DISCOVERY_V1\",\"type\":\"SERVER\",\"nonce\":\"wrong\",\"port\":5051}",
                            "{\"service\":\"BIDNET_DISCOVERY_V1\",\"type\":\"SERVER\",\"nonce\":\"" + nonce + "\",\"port\":0}",
                            "{\"service\":\"BIDNET_DISCOVERY_V1\",\"type\":\"SERVER\",\"nonce\":\"" + nonce + "\",\"port\":5051}",
                            "{\"service\":\"BIDNET_DISCOVERY_V1\",\"type\":\"SERVER\",\"nonce\":\"" + nonce + "\",\"port\":5051}")) {
                        byte[] data = reply.getBytes(StandardCharsets.UTF_8);
                        fake.send(new DatagramPacket(data,data.length,query.getAddress(),query.getPort()));
                    }
                } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            var found = ServerDiscovery.find(Duration.ofMillis(300),List.of(loopback),fake.getLocalPort());
            responder.join(3000);
            assertFalse(responder.isAlive());
            assertEquals(1,found.size());
            assertEquals(5051,found.getFirst().port());
        }
    }

    @Test void listsOneServerOnceWhenItAnswersOnSeveralAddresses() throws Exception {
        List<InetAddress> targets = new java.util.ArrayList<>();
        // Non-loopback first: loopback must still win whatever the reply order.
        for (NetworkInterface network : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress address : network.getInterfaceAddresses()) {
                if (address.getAddress() instanceof Inet4Address) targets.add(address.getAddress());
            }
        }
        targets.add(loopback);
        try (ServerDiscovery beacon = new ServerDiscovery(5051, 0)) {
            var found = ServerDiscovery.find(Duration.ofMillis(300), targets, beacon.port());
            assertEquals(1, found.size(), found.toString());
            assertEquals(loopback.getHostAddress(), found.getFirst().host());
        }
    }

    @Test void keepsDistinctServersThatShareAHostAndReportDifferentIds() throws Exception {
        try (ServerDiscovery first = new ServerDiscovery(5051, 0);
             DatagramSocket relay = new DatagramSocket(0)) {
            // Stand-in for a second server: same source address, different identity and TCP port.
            Thread responder = Thread.startVirtualThread(() -> {
                try {
                    DatagramPacket query = new DatagramPacket(new byte[1024], 1024);
                    relay.receive(query);
                    String nonce = new ObjectMapper().readTree(query.getData(),0,query.getLength()).path("nonce").asText();
                    for (String id : List.of("a", "b", "a")) {
                        byte[] data = ("{\"service\":\"BIDNET_DISCOVERY_V1\",\"type\":\"SERVER\",\"nonce\":\""
                                + nonce + "\",\"port\":6001,\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
                        relay.send(new DatagramPacket(data,data.length,query.getAddress(),query.getPort()));
                    }
                } catch (IOException failure) { throw new RuntimeException(failure); }
            });
            var found = ServerDiscovery.find(Duration.ofMillis(300),List.of(loopback),relay.getLocalPort());
            responder.join(3000);
            assertEquals(2, found.size(), found.toString());
            assertEquals(1, ServerDiscovery.find(Duration.ofMillis(200),List.of(loopback),first.port()).size());
        }
    }

    @Test void closingResponderReleasesItsPortAndDoesNotLeaveStaleResults() throws Exception {
        ServerDiscovery beacon = new ServerDiscovery(5051,0);
        int udpPort = beacon.port();
        beacon.close();
        try (DatagramSocket reserved = new DatagramSocket(udpPort)) {
            assertTrue(ServerDiscovery.find(Duration.ofMillis(150),List.of(loopback),reserved.getLocalPort()).isEmpty());
        }
        assertThrows(IllegalArgumentException.class,
                () -> ServerDiscovery.find(Duration.ZERO,List.of(loopback),udpPort));
    }
}

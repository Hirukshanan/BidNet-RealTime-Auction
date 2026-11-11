package com.bidnet.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class ConsoleClient {
    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 5000;
        ObjectMapper mapper = new ObjectMapper();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            try (BufferedReader input = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter output = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                System.out.println("Connected to " + host + ":" + port);
                System.out.println("Enter a message, or type quit to disconnect.");
                String text;
                while ((text = console.readLine()) != null && !"quit".equalsIgnoreCase(text.trim())) {
                    ObjectNode request = mapper.createObjectNode().put("type", "ECHO");
                    request.putObject("payload").put("message", text);
                    output.println(request);
                    if (output.checkError()) {
                        throw new IOException("Could not send the message to the server.");
                    }
                    String response = input.readLine();
                    if (response == null) {
                        throw new IOException("The server closed the connection.");
                    }
                    System.out.println("Server: " + response);
                }
            }
        }
    }
}

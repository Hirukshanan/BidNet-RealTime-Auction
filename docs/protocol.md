# Initial TCP communication protocol

This stage provides multiple-client request/response communication. Auction commands and live broadcasts belong to later issues.

## Framing and fields

- Transport: TCP. The server accepts connections on all local network interfaces.
- Encoding: UTF-8, one compact JSON object per line, terminated with a newline.
- Each connection can send multiple requests. Each request receives one response.
- `type` is a case-sensitive string. `payload` is a JSON object, not a JSON-encoded string.
- The initial console client sends one request and waits for its response before accepting another message.

## Echo request

```json
{"type":"ECHO","payload":{"message":"Hello BidNet"}}
```

The server returns the same payload:

```json
{"type":"ECHO_RESPONSE","payload":{"message":"Hello BidNet"}}
```

Jackson constructs and reads the JSON so quotes, backslashes, and Unicode text are preserved. The server accepts any object payload for ECHO; the console client supplies a message field.

## Error response

```json
{"type":"ERROR","payload":{"reason":"INVALID_MESSAGE"}}
```

Reasons:

- `INVALID_JSON`: the line is not a single valid JSON value, including trailing JSON values.
- `INVALID_MESSAGE`: the root is not an object, type is missing/non-textual, or payload is not an object.
- `UNSUPPORTED_TYPE`: the type is not ECHO after the envelope has been validated.

An invalid request does not close the connection. A client disconnect affects only that client. Closing the server releases the listening port and connected sockets.

## Run locally on Windows or macOS

Install JDK 21 and Maven, then run from the repository root.

Terminal 1:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.server.BidNetServer" "-Dexec.args=5000"
```

Terminals 2 and 3 (run the same command in each):

```text
mvn exec:java "-Dexec.mainClass=com.bidnet.client.ConsoleClient" "-Dexec.args=127.0.0.1 5000"
```

Enter ordinary text in each client; it will be encoded as an ECHO request. Type quit to disconnect. Stop the server with Ctrl+C. The console connection timeout is 3 seconds and response timeout is 5 seconds.

If port 5000 is occupied, choose another port such as 5050 and pass that same port to both clients. For a multi-computer demo, replace 127.0.0.1 with the server computer's reachable LAN address; the server port must be allowed by its firewall. GitHub hosting does not run the TCP server.

## Automated verification

```text
mvn --batch-mode --no-transfer-progress verify
```

TcpCommunicationTest starts a server on an OS-selected port and verifies two simultaneously open clients, repeated messages, Unicode/escaping, disconnect recovery, malformed-message recovery, server shutdown, and the console entry point. Windows and macOS execution are checked by the repository's GitHub Actions matrix after the branch is pushed.

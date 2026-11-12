# BidNet - Real-Time Distributed Auction System

BidNet is a university Network Programming project connecting multiple Java
clients to a central TCP auction server. It includes a graphical Swing dashboard
and a console client.

## Features

- Join auctions and place server-validated bids.
- Receive live price, minimum-bid and leader updates without refreshing.
- Accept the first bid at or above the starting price; require at least a 5%
  increase for later bids, rounded upward to two decimal places.
- Validate and update bids atomically under a per-auction lock.
- Close auctions at the server deadline and announce a final winner or no bids.
- Handle disconnected clients while other bidders continue.

The built-in auction is `demo`, starting at `1000.00` and closing ten minutes
from server startup. State is held in memory and resets when the server restarts.

## Requirements

- JDK 21 and Maven available in the terminal.
- A desktop session for the GUI.
- Run the following commands from the repository root on Windows or macOS.

Check the installed versions:

```text
java -version
mvn -version
```

## Build and test

```text
mvn --batch-mode --no-transfer-progress verify
```

This runs the tests and creates the runnable server and client bundle at
`target/bidnet.jar`. Rebuild after source changes and relaunch old application
windows to load the updated code. Do not commit the `target` directory.

## Run the application

### 1. Start the server

In terminal 1:

```text
java -jar target/bidnet.jar 5051
```

Wait for `BidNet server listening on port 5051`. Leave this terminal running.
Starting a client does not start a server.

### 2. Open the graphical client

In terminal 2:

```text
java -cp target/bidnet.jar com.bidnet.client.GuiClient 127.0.0.1 5051
```

Repeat this command in more terminals to open independent bidder windows.

1. Confirm the host is `127.0.0.1` and port is `5051` for a same-computer demo.
2. Click **Connect**, then **Join Auction** with auction ID `demo`.
3. Wait for the join confirmation.
4. Enter a bid or click **Min Bid** to fill the current minimum.
5. Click **Place Bid**. Watch live updates and accepted/rejected feedback.
6. At expiry, check the final winner or no-bid result. Bidding is disabled.
7. Click **Disconnect** or close the window when finished.

The countdown is a local estimate; the server alone decides expiry. Reconnecting
creates a new bidder identity and requires joining again.

### 3. Optional console client

```text
java -cp target/bidnet.jar com.bidnet.client.ConsoleClient 127.0.0.1 5051
```

Commands: `join demo`, `bid 1000.00`, `help`, `echo hello`, `quit`.
Wait for join confirmation before bidding.

### Run directly with Maven instead

Server:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.server.BidNetServer" "-Dexec.args=5051"
```

GUI, in a separate terminal:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.client.GuiClient" "-Dexec.args=127.0.0.1 5051"
```

## Ports and multiple computers

Server, GUI and console client default to **5051**. If another program occupies
that port, choose a free port and pass the same number to the server and clients.

On another computer, replace `127.0.0.1` with the server computer's reachable LAN
address. Connect the computers to a shared reachable network and allow the server
port through the firewall. GitHub hosts the code, not the running TCP server.

If connection fails:

- Confirm the server is still running and printed its listening port.
- Check that the client host and port match the running server.
- Use `127.0.0.1` only when the server runs on the same computer as that client.
- For LAN connections, check network reachability and firewall permissions.
- If the JAR is missing or outdated, rerun the build command above.

Stop the server with Ctrl+C; connected clients will report the disconnect.

## Limitations

This is an in-memory demonstration with plaintext TCP, connection-bound identities,
no account authentication, no persistence and no automatic reconnect. A computer must remain running as the server throughout the auction.

## Documentation

- [GUI behavior and verification](docs/gui-client.md)
- [TCP message protocol](docs/protocol.md)
- [Auction engine rules](docs/auction-engine.md)
- [Expiry and closure](docs/auction-expiry.md)
- [Three-computer LAN runbook and recorded evidence](docs/verification/three-computer-demo.md)

## Technologies

Java 21, Swing, Maven, TCP sockets, virtual threads, Jackson, JUnit 5 and GitHub Actions.

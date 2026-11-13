# Graphical auction client

The Swing desktop client runs on JDK 21 on Windows and macOS. It uses the same
TCP server and protocol as the console client. No extra GUI library is required.

## Start

From the repository root, start the server in one terminal:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.server.BidNetServer" "-Dexec.args=5051"
```

In another terminal, open the GUI:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.client.GuiClient"
```

Repeat that GUI command in separate terminals to open more bidder windows.
You may also supply the default host and port as arguments:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.client.GuiClient" "-Dexec.args=127.0.0.1 5051"
```

## Use

1. Enter the server host and port, then click **Connect**.
2. Leave the auction ID as `demo`, then click **Join Auction**.
3. After confirmation, enter an amount or click **Min Bid**.
4. Click **Place Bid**, or press Enter in the bid field.
5. Watch the price, minimum, leader, result feedback and activity feed update
   without further clicks. Each window is a separate server-issued bidder identity.
6. On expiry, the GUI shows the final price and winner or no-bid outcome, and
   disables bidding. The local countdown is only an estimate; the server decides
   whether the deadline has been reached.
7. Click **Disconnect** or close the window to release the socket. Reconnecting
   creates a new bidder identity and requires joining again.

A failed join keeps the last confirmed auction selected. Older snapshots cannot
replace a newer displayed state. A server disconnect disables bidding and lets
users reconnect manually. Rejected bids show the server's reason.

For a LAN demo, replace `127.0.0.1` with the server computer's reachable address.
A desktop session is needed to open windows; automated GUI-panel tests can run
without a display. Cross-computer firewall/reachability checks remain necessary.

## Verification

```text
mvn --batch-mode --no-transfer-progress verify
mvn "-Dtest=GuiClientTest" test
```

GuiClientTest drives real Swing button actions on the event thread and uses real
TCP connections for live bids, failed joins, shutdown and reconnect. Synthetic
final/stale frames additionally check rendering of closed auctions and no-winner
outcomes. It does not replace a physical macOS/Windows GUI and LAN demo.

## Packaged launch (after Maven verify or package)

Both server and clients default to port 5051. Use the same port everywhere.
Start the server first and leave its terminal running:

```text
java -jar target/bidnet.jar 5051
```

In another terminal, start each GUI window:

```text
java -cp target/bidnet.jar com.bidnet.client.GuiClient 127.0.0.1 5051
```

If a connection is refused, confirm the server printed its listening port and
that the GUI host/port match it. A GUI window alone does not start the server.
After rebuilding, close old GUI windows and relaunch to load the new code.

## Same-network server discovery

Use the updated server and GUI on both computers. After starting the server,
click **Find servers** in the client, wait approximately three seconds, select
one result, then **Connect** and **Join Auction**. Manual host/port entry is
still available. Selecting a result fills the server's advertised TCP port.

Discovery uses IPv4 UDP broadcasts on port 5052 plus a loopback request for
same-computer use. Replies are matched to the current search request and deduplicated
by endpoint. The service does not query an external directory or upload auction data.

Server firewall rules must allow UDP 5052 and the auction TCP port (normally
5051). Isolated guest/campus Wi-Fi may block discovery. The same Wi-Fi name alone
does not guarantee reachability. One server per computer can bind the discovery
port; other TCP servers can still be connected to manually. Actual Windows/Mac
cross-computer discovery must be verified on the team's network.

The server selector stays hidden until a search finds more than one server. A
single result fills the host/port automatically; click Connect to continue.
The selector is hidden while searching and while connected.

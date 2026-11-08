# BidNet - Real-Time Distributed Auction System

BidNet is a university Network Programming project that connects multiple clients
to a central Java server over TCP.

## Current development stage

The server supports in-memory auctions, connection-bound bidder identities,
JOIN_AUCTION and PLACE_BID messages, atomic bid validation, and versioned live
updates to subscribers. The standard server creates auction `demo` at `1000.00`.

The console client currently supports ECHO diagnostics only. Live console auction
commands are tracked in issue #5; auction expiry and final closure are tracked in
issue #7. Restarting the server resets in-memory auction state.

See [protocol and run instructions](docs/protocol.md) and
[auction engine rules](docs/auction-engine.md).

## Technologies

- Java 21 and Maven
- TCP sockets and virtual threads
- Per-auction locking and immutable snapshots
- Jackson JSON
- JUnit 5
- GitHub Actions on Windows and macOS

## Verify

```text
mvn --batch-mode --no-transfer-progress verify
```

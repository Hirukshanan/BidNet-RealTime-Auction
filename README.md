# BidNet - Real-Time Distributed Auction System

BidNet is a university Network Programming project that connects multiple clients
to a central Java server over TCP.

## Current development stage

The server supports in-memory auctions, connection-bound bidder identities,
JOIN_AUCTION and PLACE_BID messages, atomic bid validation, and versioned live
updates to subscribers. Auctions close at an authoritative UTC deadline and emit
one final `AUCTION_ENDED` snapshot. The standard server creates auction `demo` at
`1000.00` with a ten-minute deadline.

The console client joins auctions, places bids and shows live updates while the
keyboard is idle. Restarting the server resets in-memory auction
state.

See [protocol and run instructions](docs/protocol.md) and
[auction engine rules](docs/auction-engine.md).
See also the [auction expiry contract](docs/auction-expiry.md).

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

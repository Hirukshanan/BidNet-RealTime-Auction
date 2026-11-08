# Auction expiry contract

Every auction has one authoritative deadline represented by a UTC `Instant`.
The server supplies that deadline when it creates the auction. The engine uses an
injected `Clock`, so production uses the server clock while tests can move time
without sleeping.

The deadline is exclusive: bids are valid only while `now < deadline`. At the
deadline or later, a bid cannot be accepted even if the scheduled callback is
late. A late bid observes and performs the close transition itself while holding
the same per-auction lock used for bid validation.

Closing changes `OPEN` to `CLOSED` and increments the snapshot version exactly
once. Later close calls are idempotent and return the same winner, price and
version. If no bid was accepted, `winnerId()` is empty and `hasAcceptedBid()` is
false; the displayed current price remains the starting price.

## Server integration

`closeIfExpired` returns `CloseResult.closedNow()`. Only a caller receiving
`true` owns the final `AUCTION_ENDED` announcement. A bid that discovers expiry
returns `BidResult.closedNow()`/`stateChanged()` for the same purpose. Because
both paths use the auction lock, exactly one of competing bid and scheduler
callers can own that transition. Serialize and broadcast the returned immutable
snapshot after the engine method returns, never while the auction lock is held.

The server scheduler should keep one cancellable task per auction. Server
shutdown must cancel those tasks and close its scheduler/executor. That lifecycle
wiring belongs to the server integration layer; the domain engine neither owns
threads nor performs network I/O.

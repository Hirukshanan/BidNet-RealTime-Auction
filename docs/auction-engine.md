# Auction engine rules

The auction engine is the authoritative in-memory owner of bid state. It has no
socket, user-interface or database dependency. Callers may perform networking or
persistence after an engine operation returns an immutable `AuctionSnapshot`.

## Money and bid boundaries

- All prices use `BigDecimal` and allow at most two decimal places.
- The engine normalizes accepted prices to exactly two decimal places.
- The first bid must equal or exceed the starting price.
- Every later bid must equal or exceed the current price multiplied by `1.05`.
  The required amount is rounded upward to two decimal places.
- Invalid, non-positive, over-precision and low bids return a rejection reason
  and the unchanged snapshot.

For a starting price of `1000.00`, a first bid of `1000.00` makes the next
minimum `1050.00`. Accepting `1050.00` makes the next minimum `1102.50`.
The rule uses the accepted offered price, so a bidder who offers more than the
minimum also raises the base used for the following five-percent calculation.

## Concurrency contract

Each auction owns one lock. Bid validation, price/winner mutation, version
increment and snapshot creation occur while that lock is held. Separate auctions
do not share a lock. Returned records are immutable, and callers must keep
network and disk operations outside the engine call.

An auction starts at version `0`; every accepted bid increments the version once.
Clients can use the version to ignore an older snapshot received after a newer
one.

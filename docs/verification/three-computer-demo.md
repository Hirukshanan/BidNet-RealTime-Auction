# Three-computer demo verification (issue #8)

**Status on 12 November 2026: not complete.** PRs #11, #12 and #13 are merged
into `main`, including both integration fixes below. The current PR head
(`108e392`) passes `mvn clean verify` with 42 tests. All 11 demo scenarios pass
on one Mac with a real server and three real console clients. No check has run
between two computers yet. Keep issue #8 open until the LAN run below is recorded.

## What each kind of evidence proves

| Level | Meaning | What it cannot prove |
| --- | --- | --- |
| Hosted CI | GitHub runners pass `mvn verify` on Windows and macOS | Anything about team computers or their network |
| Same-machine TCP | Real sockets reach a real running server on one Mac | Firewall, Wi-Fi or cross-OS behaviour |
| Real LAN | Team computers connect across their actual network | This is the level issue #8 requires |

## Acceptance criteria

| Criterion | Status | Evidence and what remains |
| --- | --- | --- |
| Fresh clone builds on JDK 21, Windows and macOS | **Partial** | macOS: `mvn clean verify` passed on PR head `108e392` (42 tests, JDK 21.0.13). Windows has hosted CI only. Repeat on Ilmam's computer and on the final commit. |
| One reachable server and three clients across computers | **Ready to run** | Same-machine run passed with the #5 client. Not run across computers. |
| Join/current state, valid bid, low-bid rejection, automatic updates | **Same-machine only** | Probe and #5 client passed (scenarios 1 to 5). Repeat over LAN. |
| Simultaneous bids against the percentage rules | **Same-machine only** | 20 probe rounds and 5 client rounds passed. Repeat over LAN. |
| Disconnect one client; others still work | **Same-machine only** | Clean quit and SIGKILL of a client both passed. Dropped Wi-Fi not tested. |
| Closure, final winner/no-bid, late-bid rejection | **Same-machine only** | Passed with #7 and the `AUCTION_ENDED` client fix (scenarios 9 to 11). Repeat over LAN. |
| Record commands, OS, results, limitations | **In progress** | This file. Fill in the LAN tables below. |
| Retest blockers; separate LAN from CI | **In progress** | Both integration bugs (below) are fixed in `main` and retested at `108e392`. LAN retest pending. |
| Synthetic identities; redact network details | **Met so far** | Evidence files were scanned for addresses, hostname and username. Repeat for each new file. |

## Blockers

| Blocker | Owner | Status |
| --- | --- | --- |
| PR #11 (#6 server integration) | Hirukshanan | **Merged** |
| PR #12 (#5 client), with integration fix 2 | Risapan | **Merged** |
| PR #13 (#7 expiry), with integration fix 1 | TheGuy24084 | **Merged** |
| Real three-computer LAN run | Team | **Pending**: blocks closing #8 |
| Unexplained first-run failure below | Risapan | **Open**: cause unknown; watch for it in the LAN run |

PR #11 used the template text `Closes #<issue-number>`, so its merge did not
close #6 automatically. Check #6 and close it by hand if it is still open.

## Integration bugs found on 11 November

Each PR passes on its own, but merging #11, #12 and #13 together exposed two bugs:

1. **The build breaks.** #13 changes `AuctionEngine.createAuction` to require a
   deadline, but #12's `ConsoleClientTest` still calls the old two-argument form.
   Fix: pass `Instant.now().plus(Duration.ofMinutes(10))` in that test.
2. **The client ignores auction closure.** #13 broadcasts `AUCTION_ENDED`, which
   #12's `AuctionDisplay` prints as "Ignored an unsupported server message". Fix:
   `case "AUCTION_UPDATE", "AUCTION_ENDED" -> showUpdate(payload);`

With both fixes, `mvn clean verify` passed (40 tests at the time) and scenarios
1 to 11 passed. Both fixes are now in `main`. The current PR head adds the
merged #7 tests and passes 42.

## Observed results: same-machine live run (11 November)

macOS 27.2 (arm64), OpenJDK 21.0.13, Maven 3.9.16. Code: origin/main + PRs #11,
#12, #13 + the two fixes above. One real server process and three real
`ConsoleClient` processes over TCP to 127.0.0.1. The server was started with
`DemoLauncher` so the deadline is seconds, not ten minutes. **Not a LAN check.**
Full transcript: [log](evidence/2026-11-11-same-machine-live-scenarios.txt).

| # | Expected | Observed |
| --- | --- | --- |
| 1 | All join at 1000.00, minimum 1000.00, no leader | Pass, version 0 on all three |
| 2 | 999.99 rejected; others see nothing | Pass, `BID_TOO_LOW`; no line on B or C |
| 3 | 1000.00 accepted; all update, minimum 1050.00 | Pass, B and C updated within 1 ms |
| 4 | 1049.99 rejected | Pass, `BID_TOO_LOW`, state unchanged |
| 5 | 1050.00 accepted; minimum 1102.50 | Pass |
| 6 | 5 rounds of three equal bids; one winner each | Pass; minimums 1157.63, 1215.52, 1276.30, 1340.12, 1407.13; all clients agree on price and version |
| 7 | C quits; A and B keep bidding | Pass, C exit code 0 |
| 8 | C killed abruptly; others keep bidding | Pass, server stayed up |
| 9 | Expires after bids; CLOSED once with stable winner | Pass, one `CLOSED` update at version 10 on both clients |
| 10 | Bid after closure rejected | Pass, `AUCTION_CLOSED`, state unchanged |
| 11 | Expires with no bids; no winner | Pass, "no winner, no bids accepted" |

### Short deadlines for scenarios 9 to 11

The standard server's `demo` auction lasts ten minutes. To avoid waiting, compile
the project and start the server with a short deadline instead (macOS shown; on
Windows use `;` instead of `:` in the classpath):

```text
mvn -q compile dependency:build-classpath "-Dmdep.outputFile=cp.txt"
javac -cp "target/classes:$(cat cp.txt)" -d target/verify docs/verification/DemoLauncher.java
java -cp "target/verify:target/classes:$(cat cp.txt)" DemoLauncher 5051 60
```

## Observed results: same-machine TCP

Tested commit `e5eeaf0` (`feature/6-auction-integration`) on macOS 27.2 (arm64)
with JDK 21.0.12.1 and Maven 3.9.16.

| Check | Expected | Observed | Evidence |
| --- | --- | --- | --- |
| Fresh clone `mvn verify` | Build succeeds | 16 tests, 0 failures, 0 errors, BUILD SUCCESS | [log](evidence/2026-11-08-macos-fresh-verify.txt) |
| Hosted CI, Windows and macOS | Both jobs pass | Both passed; **hosted runners, not team computers** | [jobs](evidence/2026-11-08-hosted-ci-jobs.txt) |
| Probe via 127.0.0.1 | All checks pass | 54/54 in all 5 runs | [log](evidence/2026-11-08-probe-loopback.txt) |
| Probe via this Mac's own LAN address | All checks pass | **6 of 7 runs passed 54/54; the first run failed** | [pass](evidence/2026-11-08-probe-own-address.txt), [fail](evidence/2026-11-08-probe-own-address-FAILED-first-run.txt) |
| ECHO console client, documented command | Echo reply | `ECHO_RESPONSE` received | Log not retained; repeat in the LAN run |

Probe coverage: distinct server-issued identities; 999.99 rejected; 1000.00
accepted; update received by all three sockets without a command; 1049.99
rejected and 1050.00 accepted, moving the minimum to 1102.50; 20 rounds of three
equal simultaneous bids, each with one winner and two `BID_TOO_LOW`, converging
on the same price and version; bidding continues after one socket closes.

Traffic to a Mac's own LAN address stays inside that Mac. Those runs do not count
as LAN checks.

### Open item: first-run failure

The first run against the Mac's own LAN address connected, but all three sockets
received no `AUCTION_STATE` within 3 seconds. The probe then stopped with a
`NullPointerException` in its own code, which also skipped its later checks.
The probe now checks for missing identities first. If any client has no
server-issued `bidderId`, it reports a failed precondition and exits with
code 1. It also no longer crashes when a later re-join returns no state.

- **Ruled out:** a restart race in the test script. The old listener was gone
  within a second, and three exact replays of the sequence passed.
- **Not established:** the cause. A first-run macOS firewall hold on a new Java
  binary is possible, because this JDK is ad-hoc signed, but it is unconfirmed.
- **At the demo:** watch for a firewall prompt on the first server start, and run
  the reachability and probe steps below before relying on the setup.

## Known limitations

- Probe sockets all run on one machine, and a latch does not guarantee the server
  sees bids at the same instant. This shows correct outcomes, not fairness.
- The probe is not the #5 client and does not exercise its display or commands.
- The server uses plaintext TCP with connection-bound identities and no
  authentication. Use it only on a network the team trusts.
- State is in memory. Restart the server for a clean `demo` auction at 1000.00.
- Reconnecting creates a new bidder identity, so a returning client cannot resume
  as the same bidder.
- From reading the code, not tested: incoming lines have no length limit.
- `exec-maven-plugin` is not pinned in `pom.xml` (this run resolved 3.1.0).
- The JDK is not enforced. A machine's default may not be 21 (this Mac defaults to
  27), so check `java -version` and `mvn -version` first.

## Runbook for the real LAN run

PRs #11, #12 and #13 and both integration fixes are in `main`, so this can run
now. Record the exact commit. Label any steps 1 to 4 results from the probe as a
probe run, not as the demo.

### 1. Build a fresh clone on every computer

```text
git clone https://github.com/Hirukshanan/BidNet-RealTime-Auction.git
cd BidNet-RealTime-Auction
git switch <branch-under-test>
git rev-parse HEAD
java -version
mvn -version
mvn --batch-mode --no-transfer-progress verify
```

On macOS with Homebrew's keg-only JDK 21, first run:

```text
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Windows and macOS use the same Maven commands, but quote `-D` arguments as shown
in step 3 so that PowerShell parses them correctly.

### 2. Allow only the demo port

Do not turn firewalls off. Open one port on the server computer and remove the
rule afterwards.

**Windows server** (administrator PowerShell):

```text
New-NetFirewallRule -DisplayName "BidNet demo 5051" -Direction Inbound -Protocol TCP -LocalPort 5051 -Profile Private -RemoteAddress LocalSubnet -Action Allow
Remove-NetFirewallRule -DisplayName "BidNet demo 5051"
```

The Wi-Fi network must use the Private profile for this rule to apply.

**macOS server:** choose Allow for `java` when prompted. If there is no prompt,
check System Settings > Network > Firewall > Options.

If the network isolates its clients, as campus Wi-Fi often does, use a phone
hotspot or another network the team controls. Record that choice.

### 3. Start the server and check reachability

On the server computer:

```text
mvn compile exec:java "-Dexec.mainClass=com.bidnet.server.BidNetServer" "-Dexec.args=5051"
```

From each client computer:

```text
macOS:    nc -vz <SERVER-ADDRESS> 5051
Windows:  Test-NetConnection <SERVER-ADDRESS> -Port 5051
```

### 4. Optional probe from another computer

Run this before the demo so that any network fault appears early. Use a freshly
started server, then restart it again afterwards.

```text
java docs/verification/SocketProbe.java <SERVER-ADDRESS> 5051
```

### 5. Run the demo with the #5 client on three computers

Use the documented #5 client command (see `docs/protocol.md`) and only synthetic bidder names.
Restart the server between scenarios where the starting state matters.

| # | Action | Expected |
| --- | --- | --- |
| 1 | All three clients join `demo` | Each shows 1000.00, minimum 1000.00, no leader |
| 2 | Client A bids 999.99 | A sees `BID_TOO_LOW`; B and C see nothing |
| 3 | Client A bids 1000.00 | A accepted; all three update, with minimum 1050.00 and no further command |
| 4 | Client B bids 1049.99 | B sees `BID_TOO_LOW`; state unchanged |
| 5 | Client B bids 1050.00 | Accepted; all three update, with minimum 1102.50 |
| 6 | All three bid 1102.50 on a countdown, at least 5 rounds using each new minimum | One winner per round, the others `BID_TOO_LOW`; all show the same price and version |
| 7 | Client C quits cleanly; A and B bid | A and B keep working |
| 8 | Restart; rejoin; close a client abruptly (end the process or drop its Wi-Fi); others bid | Others keep working and the server stays up |
| 9 | Let the auction expire after accepted bids | All show `CLOSED` once, with a stable winner and price (exact behaviour per #7) |
| 10 | Bid after closure | `AUCTION_CLOSED`; state unchanged |
| 11 | Restart; let the auction expire with no bids | Reported as no winner |

### 6. Record the results

Keep raw output as `.txt` files in `evidence/`, because `.gitignore` excludes
`*.log`. Before committing, replace addresses, hostnames and usernames with
placeholders such as `<SERVER-ADDRESS>`, and search the files for what you
replaced.

**Environment** (date: ____, commit: ____, network type: ____)

| Role | Tester | OS and version | `java -version` | `mvn verify` result |
| --- | --- | --- | --- | --- |
| Server | | | | |
| Client A | | | | |
| Client B | | | | |
| Client C | | | | |

**Results**

| # | Result (pass/fail/not run) | Observed | Evidence file | Issue if failed; retest commit |
| --- | --- | --- | --- | --- |
| 1 | not run | | | |
| 2 | not run | | | |
| 3 | not run | | | |
| 4 | not run | | | |
| 5 | not run | | | |
| 6 | not run | | | |
| 7 | not run | | | |
| 8 | not run | | | |
| 9 | not run | | | |
| 10 | not run | | | |
| 11 | not run | | | |

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Black-box checks against a RUNNING BidNet server over real TCP sockets.
 * No BidNet classes and no third-party libraries, so it runs on any JDK 21:
 *
 *   java SocketProbe.java HOST PORT
 *
 * This is a protocol probe, NOT the console client from issue #5. A pass does
 * not satisfy the three-computer demo; it shows only what the server did for
 * these exact frames. All sockets run on the machine executing this file.
 * Use a freshly started server: the checks expect auction "demo" at 1000.00.
 */
public final class SocketProbe {
    private static final long WAIT_MS = 3000;
    private static int failures;

    private static final class Conn implements AutoCloseable {
        final String name;
        final Socket socket = new Socket();
        final PrintWriter out;
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        String bidderId;

        Conn(String name, String host, int port) throws IOException {
            this.name = name;
            socket.connect(new InetSocketAddress(host, port), 3000);
            out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        frames.add(line);
                    }
                    System.out.println("  " + name + " << (EOF: server closed the connection)");
                } catch (IOException closed) {
                    if (!socket.isClosed()) {
                        System.out.println("  " + name + " << (connection error: " + closed.getMessage() + ")");
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        void send(String frame) {
            System.out.println("  " + name + " >> " + frame);
            out.println(frame);
        }

        void join() {
            send("{\"type\":\"JOIN_AUCTION\",\"payload\":{\"auctionId\":\"demo\"}}");
        }

        void bid(String amount) {
            send("{\"type\":\"PLACE_BID\",\"payload\":{\"auctionId\":\"demo\",\"amount\":\"" + amount + "\"}}");
        }

        /** Next frame of a type, skipping (but printing) any other frame. Null on timeout. */
        String await(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            while (true) {
                long left = deadline - System.nanoTime();
                String frame = left <= 0 ? null : frames.poll(left, TimeUnit.NANOSECONDS);
                if (frame == null) {
                    System.out.println("  " + name + " << (no " + type + " within " + WAIT_MS + " ms)");
                    return null;
                }
                System.out.println("  " + name + " << " + frame);
                if (frame.contains("\"type\":\"" + type + "\"")) {
                    return frame;
                }
            }
        }

        /** Every frame arriving within the quiet window. */
        List<String> drain(long quietMs) throws InterruptedException {
            List<String> all = new ArrayList<>();
            String frame;
            while ((frame = frames.poll(quietMs, TimeUnit.MILLISECONDS)) != null) {
                System.out.println("  " + name + " << " + frame);
                all.add(frame);
            }
            return all;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static String field(String frame, String key) {
        if (frame == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\":(\"[^\"]*\"|[^,}]+)").matcher(frame);
        return m.find() ? m.group(1).replace("\"", "") : null;
    }

    private static void check(String label, boolean ok, String observed) {
        System.out.println((ok ? "PASS " : "FAIL ") + label + " | observed: " + observed);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java SocketProbe.java HOST PORT");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);

        try (Conn a = new Conn("client-A", host, port);
             Conn b = new Conn("client-B", host, port);
             Conn c = new Conn("client-C", host, port)) {

            System.out.println("== C1 join and current state");
            for (Conn conn : List.of(a, b, c)) {
                conn.join();
                String state = conn.await("AUCTION_STATE");
                conn.bidderId = field(state, "bidderId");
                check(conn.name + " joined at 1000.00, version 0",
                        "1000.00".equals(field(state, "currentPrice"))
                                && "0".equals(field(state, "version")) && conn.bidderId != null,
                        "currentPrice=" + field(state, "currentPrice") + " version=" + field(state, "version"));
            }
            // Identities are null when AUCTION_STATE never arrived. Every later check
            // depends on them, so stop with a clear precondition failure instead.
            List<String> ids = Arrays.asList(a.bidderId, b.bidderId, c.bidderId);
            if (ids.contains(null)) {
                check("all three clients received a server-issued identity", false, "bidderIds=" + ids);
                System.out.println("RESULT: PRECONDITION FAILED - no AUCTION_STATE/bidderId for every client;"
                        + " later checks not run. Check the server is freshly started and reachable"
                        + " (firewall prompt?), then rerun.");
                System.exit(1);
            }
            check("three distinct server-issued identities",
                    new HashSet<>(ids).size() == 3, "bidderIds=" + ids);

            System.out.println("== C2 low bid rejected, no update to others");
            a.bid("999.99");
            String low = a.await("BID_RESULT");
            check("999.99 below start rejected BID_TOO_LOW",
                    "false".equals(field(low, "accepted")) && "BID_TOO_LOW".equals(field(low, "reason")),
                    "accepted=" + field(low, "accepted") + " reason=" + field(low, "reason"));
            List<String> quiet = b.drain(300);
            check("client-B got no frame from a rejected bid", quiet.isEmpty(), quiet.size() + " frames");

            System.out.println("== C3 valid bid and automatic update");
            a.bid("1000.00");
            String accepted = a.await("BID_RESULT");
            check("1000.00 first bid accepted, next minimum 1050.00",
                    "true".equals(field(accepted, "accepted"))
                            && "1050.00".equals(field(accepted, "minimumNextBid")),
                    "accepted=" + field(accepted, "accepted") + " min=" + field(accepted, "minimumNextBid"));
            for (Conn conn : List.of(a, b, c)) {
                String update = conn.await("AUCTION_UPDATE");
                check(conn.name + " received AUCTION_UPDATE v1 without sending a command",
                        "1".equals(field(update, "version"))
                                && a.bidderId.equals(field(update, "highestBidderId")),
                        "version=" + field(update, "version")
                                + " highestBidderId=" + field(update, "highestBidderId"));
            }

            System.out.println("== C4 boundary of the 5 percent rule");
            b.bid("1049.99");
            String under = b.await("BID_RESULT");
            check("1049.99 rejected (one cent under 1050.00)",
                    "BID_TOO_LOW".equals(field(under, "reason")), "reason=" + field(under, "reason"));
            b.bid("1050.00");
            String exact = b.await("BID_RESULT");
            check("1050.00 accepted, next minimum 1102.50",
                    "true".equals(field(exact, "accepted"))
                            && "1102.50".equals(field(exact, "minimumNextBid")),
                    "accepted=" + field(exact, "accepted") + " min=" + field(exact, "minimumNextBid"));
            a.drain(200);
            b.drain(200);
            c.drain(200);

            System.out.println("== C5 simultaneous bids, 20 rounds");
            simultaneous(List.of(a, b, c));

            System.out.println("== C6 disconnect one client; others keep working");
            String before = latestMinimum(b);
            c.close();
            Thread.sleep(300);
            if (before == null) {
                check("client-B read the current minimum before the drop", false, "minimumNextBid=null");
            } else {
                a.bid(before);
                String afterDrop = a.await("BID_RESULT");
                check("client-A bid accepted after client-C dropped",
                        "true".equals(field(afterDrop, "accepted")), "accepted=" + field(afterDrop, "accepted"));
                String seen = b.await("AUCTION_UPDATE");
                check("client-B still receives updates", seen != null, "version=" + field(seen, "version"));
                a.drain(200);
            }
        }

        System.out.println(failures == 0 ? "RESULT: ALL CHECKS PASSED" : "RESULT: " + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Asks the server for the current minimum by re-joining: repeated joins are allowed. */
    private static String latestMinimum(Conn conn) throws InterruptedException {
        conn.join();
        return field(conn.await("AUCTION_STATE"), "minimumNextBid");
    }

    /**
     * Each round: all three clients release a bid for the same amount (the exact
     * minimum) from a latch. The rule allows exactly one to win; the rest must get
     * BID_TOO_LOW, and every client must converge on the same price and version.
     */
    private static void simultaneous(List<Conn> conns) throws Exception {
        for (int round = 1; round <= 20; round++) {
            String min = latestMinimum(conns.get(0));
            if (min == null) {
                check("round " + round + ": server reported the current minimum", false,
                        "minimumNextBid=null; remaining rounds skipped");
                return;
            }
            BigDecimal expectedNext = new BigDecimal(min).multiply(new BigDecimal("1.05"))
                    .setScale(2, RoundingMode.CEILING);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> senders = new ArrayList<>();
            for (Conn conn : conns) {
                Thread t = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    conn.bid(min);
                });
                t.start();
                senders.add(t);
            }
            go.countDown();
            for (Thread t : senders) {
                t.join();
            }

            int wins = 0;
            int tooLow = 0;
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            for (Conn conn : conns) {
                String r = conn.await("BID_RESULT");
                results.add(r);
                if ("true".equals(field(r, "accepted"))) {
                    wins++;
                } else if ("BID_TOO_LOW".equals(field(r, "reason"))) {
                    tooLow++;
                }
            }
            check("round " + round + " at " + min + ": exactly one winner, two BID_TOO_LOW",
                    wins == 1 && tooLow == 2, "wins=" + wins + " tooLow=" + tooLow);

            List<String> versions = new ArrayList<>();
            for (Conn conn : conns) {
                conn.drain(100);
                conn.join();
                String s = conn.await("AUCTION_STATE");
                versions.add(field(s, "version") + "@" + field(s, "currentPrice")
                        + "/" + field(s, "minimumNextBid"));
            }
            boolean converged = versions.get(0).equals(versions.get(1)) && versions.get(1).equals(versions.get(2));
            check("round " + round + " clients converge, next minimum " + expectedNext,
                    converged && versions.get(0).endsWith("/" + expectedNext), versions.toString());
        }
    }
}

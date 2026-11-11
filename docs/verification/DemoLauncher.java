import com.bidnet.auction.AuctionEngine;
import com.bidnet.server.BidNetServer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/** Starts the real BidNetServer with a "demo" auction whose deadline is N seconds away. */
public class DemoLauncher {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long seconds = Long.parseLong(args[1]);
        AuctionEngine engine = new AuctionEngine();
        engine.createAuction("demo", new BigDecimal("1000.00"), Instant.now().plus(Duration.ofSeconds(seconds)));
        BidNetServer server = new BidNetServer(port, engine);
        System.out.println("READY port=" + port + " deadlineIn=" + seconds + "s");
        server.start();
    }
}

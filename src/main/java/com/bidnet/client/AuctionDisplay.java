package com.bidnet.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns server frames into console output, dispatching only on the frame type.
 * Used by the single socket reader thread, so its state needs no locking.
 */
final class AuctionDisplay {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> latest = new HashMap<>();
    private final PrintStream out;
    private String bidderId;

    AuctionDisplay(PrintStream out) {
        this.out = out;
    }

    void handle(String line) {
        JsonNode frame;
        try {
            frame = mapper.readTree(line);
        } catch (JsonProcessingException malformed) {
            out.println("Ignored a malformed server message.");
            return;
        }
        if (frame == null || !frame.path("type").isTextual() || !frame.path("payload").isObject()) {
            out.println("Ignored a malformed server message.");
            return;
        }
        JsonNode payload = frame.get("payload");
        switch (frame.get("type").textValue()) {
            case "AUCTION_STATE" -> showJoined(payload);
            case "AUCTION_UPDATE" -> showUpdate(payload);
            case "BID_RESULT" -> showBidResult(payload);
            case "ECHO_RESPONSE" -> out.println("Server: " + line);
            case "ERROR" -> out.println("Server error: " + payload.path("reason").asText("UNKNOWN"));
            default -> out.println("Ignored an unsupported server message: " + frame.get("type").textValue());
        }
    }

    private void showJoined(JsonNode payload) {
        if (payload.path("bidderId").isTextual()) {
            bidderId = payload.get("bidderId").textValue();
        }
        JsonNode snapshot = payload.path("snapshot");
        if (!isValid(snapshot)) {
            out.println("Ignored a malformed auction snapshot.");
            return;
        }
        remember(snapshot);
        out.println("Joined auction " + snapshot.get("auctionId").textValue() + " as " + bidderId);
        out.println(describe(latest.get(snapshot.get("auctionId").textValue())));
    }

    private void showUpdate(JsonNode payload) {
        JsonNode snapshot = payload.path("snapshot");
        if (!isValid(snapshot)) {
            out.println("Ignored a malformed auction snapshot.");
        } else if (remember(snapshot)) {
            out.println("Update: " + describe(snapshot));
        }
    }

    private void showBidResult(JsonNode payload) {
        // The outcome always counts, even when its snapshot is older than one already seen.
        boolean accepted = payload.path("accepted").asBoolean();
        out.println(accepted ? "Bid accepted."
                : "Bid rejected: " + payload.path("reason").asText("UNKNOWN"));
        JsonNode snapshot = payload.path("snapshot");
        if (!isValid(snapshot)) {
            return;
        }
        boolean newer = remember(snapshot);
        if (newer || !accepted) {
            out.println(describe(latest.get(snapshot.get("auctionId").textValue())));
        }
    }

    /** Records a snapshot unless a newer one is known; returns whether it advanced the state. */
    private boolean remember(JsonNode snapshot) {
        String auctionId = snapshot.get("auctionId").textValue();
        JsonNode known = latest.get(auctionId);
        if (known != null && snapshot.get("version").asLong() <= known.get("version").asLong()) {
            return false;
        }
        latest.put(auctionId, snapshot);
        return true;
    }

    private static boolean isValid(JsonNode snapshot) {
        return snapshot.path("auctionId").isTextual() && snapshot.path("version").isIntegralNumber();
    }

    private String describe(JsonNode snapshot) {
        String prefix = "[" + snapshot.path("auctionId").asText() + "] " + snapshot.path("status").asText();
        String suffix = " | version " + snapshot.path("version").asLong();
        JsonNode bidder = snapshot.path("highestBidderId");
        if ("CLOSED".equals(snapshot.path("status").asText())) {
            return prefix + (bidder.isTextual()
                    ? " | final price " + snapshot.path("currentPrice").asText() + " | winner " + name(bidder)
                    : " | no winner, no bids accepted") + suffix;
        }
        return prefix + " | price " + snapshot.path("currentPrice").asText()
                + " | minimum next bid " + snapshot.path("minimumNextBid").asText()
                + " | leader " + (bidder.isTextual() ? name(bidder) : "none") + suffix;
    }

    private String name(JsonNode bidder) {
        return bidder.textValue().equals(bidderId) ? "you" : bidder.textValue();
    }
}

package com.bidnet.client;

import com.fasterxml.jackson.databind.JsonNode;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** Interactive TCP auction dashboard — vibrant, animated, modern Swing UI. */
public final class GuiClient extends JPanel {

    // ── Colour palette ────────────────────────────────────────────────────────
    private static final Color BG_DARK        = new Color(10,  14,  26);
    private static final Color BG_CARD        = new Color(18,  24,  43);
    private static final Color BG_CARD2       = new Color(22,  30,  54);
    private static final Color ACCENT_CYAN    = new Color(0,   220, 200);
    private static final Color ACCENT_PURPLE  = new Color(140,  80, 240);
    private static final Color ACCENT_GOLD    = new Color(255, 195,   0);
    private static final Color ACCENT_GREEN   = new Color( 50, 220, 120);
    private static final Color ACCENT_RED     = new Color(255,  70,  90);
    private static final Color ACCENT_BLUE    = new Color( 60, 140, 255);
    private static final Color TEXT_PRIMARY   = new Color(230, 240, 255);
    private static final Color TEXT_MUTED     = new Color(110, 130, 170);
    private static final Color BORDER_GLOW    = new Color(0,   180, 160,  80);

    // ── Fonts ─────────────────────────────────────────────────────────────────
    private static final Font FONT_TITLE   = new Font(Font.SANS_SERIF, Font.BOLD,  28);
    private static final Font FONT_LABEL   = new Font(Font.SANS_SERIF, Font.BOLD,  11);
    private static final Font FONT_VALUE   = new Font(Font.SANS_SERIF, Font.BOLD,  28);
    private static final Font FONT_BODY    = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
    private static final Font FONT_SMALL   = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font FONT_MONO    = new Font(Font.MONOSPACED, Font.PLAIN, 13);
    private static final Font FONT_BTN     = new Font(Font.SANS_SERIF, Font.BOLD,  13);
    private static final Font FONT_STATUS  = new Font(Font.SANS_SERIF, Font.BOLD,  13);

    // ── UI controls ───────────────────────────────────────────────────────────
    final JTextField host       = styledField("127.0.0.1", 14);
    final JTextField port       = styledField("5051",       5);
    final JTextField auction    = styledField("demo",      12);
    final JTextField amount     = styledField("",          14);
    final JButton    connect    = glowButton("⚡ Connect",      ACCENT_CYAN,   BG_DARK);
    final JButton    disconnect = glowButton("✕  Disconnect",   ACCENT_RED,    BG_DARK);
    final JButton    join       = glowButton("🔑 Join Auction", ACCENT_PURPLE, BG_DARK);
    final JButton    bid        = glowButton("💰 Place Bid",    ACCENT_GOLD,   BG_DARK);
    final JButton    minimum    = glowButton("↓ Min Bid",       ACCENT_BLUE,   BG_DARK);

    // ── Status labels ─────────────────────────────────────────────────────────
    final JLabel connectionStatus = new JLabel("● OFFLINE");
    final JLabel auctionTitle     = new JLabel("Your next bid starts here");
    final JLabel price            = new JLabel("—");
    final JLabel nextBid          = new JLabel("—");
    final JLabel leader           = new JLabel("—");
    final JLabel countdown        = new JLabel("—");
    final JLabel outcome          = new JLabel("Connect to a server, then join an auction.");
    final JLabel state            = new JLabel("NOT JOINED");

    // ── Log area (colour-coded) ────────────────────────────────────────────────
    final JTextPane history = new JTextPane();

    // ── Internal state ────────────────────────────────────────────────────────
    private final Map<String, JsonNode> snapshots = new HashMap<>();
    private final Timer countdownTimer  = new Timer(1000, e -> updateCountdown());
    private final Timer pulseTimer      = new Timer(600,  e -> pulseTick());
    private GuiConnection session;
    private long    generation;
    private boolean online;
    private String  currentAuction;
    private String  bidderId;
    private boolean pulseOn;

    // ── Animated status dot ───────────────────────────────────────────────────
    private final AnimatedDot statusDot = new AnimatedDot();

    // =========================================================================
    public GuiClient() {
        super(new BorderLayout(0, 0));
        setBackground(BG_DARK);

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildBody(),    BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);

        wireActions();
        updateControls();
    }

    @Override public void addNotify() {
        super.addNotify();
        pulseTimer.start();
    }

    @Override public void removeNotify() {
        pulseTimer.stop();
        disconnect();
        super.removeNotify();
    }

    // ── Header ────────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        GradientPanel header = new GradientPanel(
                new Color(12, 18, 36), new Color(25, 15, 55));
        header.setLayout(new BorderLayout(0, 0));
        header.setBorder(new EmptyBorder(18, 28, 18, 28));

        // Brand
        JPanel brandPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brandPanel.setOpaque(false);
        statusDot.setPreferredSize(new Dimension(14, 14));
        JLabel brand = new JLabel("BidNet");
        brand.setFont(FONT_TITLE);
        brand.setForeground(ACCENT_CYAN);
        JLabel slash = new JLabel("/ LIVE AUCTIONS");
        slash.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        slash.setForeground(TEXT_MUTED);
        brandPanel.add(statusDot);
        brandPanel.add(brand);
        brandPanel.add(slash);

        // Connection status pill
        connectionStatus.setFont(FONT_STATUS);
        connectionStatus.setForeground(ACCENT_RED);
        connectionStatus.setBorder(new EmptyBorder(5, 14, 5, 14));

        JPanel connRow = darkRow();
        connRow.add(fieldLabel("HOST")); connRow.add(host);
        connRow.add(fieldLabel("PORT")); connRow.add(port);
        connRow.add(Box.createHorizontalStrut(8));
        connRow.add(connect); connRow.add(disconnect);
        connRow.add(Box.createHorizontalStrut(16));
        connRow.add(connectionStatus);

        header.add(brandPanel, BorderLayout.NORTH);
        header.add(connRow,    BorderLayout.SOUTH);
        return header;
    }

    // ── Body ──────────────────────────────────────────────────────────────────
    private JPanel buildBody() {
        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(18, 28, 0, 28));

        // Auction row
        JPanel auctionRow = darkRow();
        auctionTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        auctionTitle.setForeground(TEXT_PRIMARY);
        auctionRow.add(auctionTitle);
        auctionRow.add(Box.createHorizontalStrut(20));
        auctionRow.add(fieldLabel("AUCTION ID")); auctionRow.add(auction); auctionRow.add(join);
        state.setFont(FONT_STATUS); state.setForeground(ACCENT_PURPLE);
        auctionRow.add(Box.createHorizontalStrut(12)); auctionRow.add(state);
        body.add(auctionRow);
        body.add(Box.createVerticalStrut(14));

        // Stat cards
        JPanel cards = new JPanel(new GridLayout(1, 4, 14, 0));
        cards.setOpaque(false);
        cards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        cards.add(statCard("CURRENT PRICE",     price,     ACCENT_GOLD,   "💲"));
        cards.add(statCard("MINIMUM NEXT BID",  nextBid,   ACCENT_CYAN,   "📈"));
        cards.add(statCard("CURRENT LEADER",    leader,    ACCENT_PURPLE, "👑"));
        cards.add(statCard("TIME REMAINING",    countdown, ACCENT_RED,    "⏱"));
        JPanel cardsWrapper = new JPanel(new BorderLayout());
        cardsWrapper.setOpaque(false);
        cardsWrapper.add(cards);
        body.add(cardsWrapper);
        body.add(Box.createVerticalStrut(16));

        // Bidding panel
        body.add(buildBiddingPanel());
        body.add(Box.createVerticalStrut(14));

        // Activity log
        body.add(buildActivityPanel());
        body.add(Box.createVerticalStrut(14));

        return body;
    }

    private JPanel buildBiddingPanel() {
        RoundPanel bidPanel = new RoundPanel(BG_CARD, 16);
        bidPanel.setLayout(new BorderLayout(0, 10));
        bidPanel.setBorder(new EmptyBorder(18, 22, 18, 22));

        JPanel bidRow = darkRow();
        JLabel bidLabel = fieldLabel("YOUR BID  $");
        bidLabel.setForeground(ACCENT_GOLD);
        bidRow.add(bidLabel); bidRow.add(amount);
        bidRow.add(minimum); bidRow.add(bid);
        bidPanel.add(bidRow, BorderLayout.NORTH);

        outcome.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        outcome.setForeground(TEXT_MUTED);
        bidPanel.add(outcome, BorderLayout.CENTER);

        JLabel note = new JLabel("Minimum increments: 5% after the first bid. Countdown is an estimate — server decides expiry.");
        note.setForeground(TEXT_MUTED);
        note.setFont(FONT_SMALL);
        bidPanel.add(note, BorderLayout.SOUTH);

        return bidPanel;
    }

    private JPanel buildActivityPanel() {
        JPanel wrapper = new JPanel(new BorderLayout(0, 8));
        wrapper.setOpaque(false);

        JLabel title = new JLabel("  ▶  LIVE ACTIVITY");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        title.setForeground(ACCENT_CYAN);
        wrapper.add(title, BorderLayout.NORTH);

        history.setEditable(false);
        history.setBackground(new Color(8, 12, 22));
        history.setForeground(TEXT_PRIMARY);
        history.setFont(FONT_MONO);
        history.setBorder(new EmptyBorder(10, 14, 10, 14));
        history.setCaretColor(ACCENT_CYAN);

        JScrollPane scroll = new JScrollPane(history);
        scroll.setPreferredSize(new Dimension(900, 160));
        scroll.getViewport().setBackground(new Color(8, 12, 22));
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_GLOW, 1));
        scroll.setBackground(new Color(8, 12, 22));

        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    // ── Footer ────────────────────────────────────────────────────────────────
    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 8));
        footer.setBackground(new Color(8, 12, 22));
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_GLOW));
        for (String item : new String[]{
                "⚡ Real-time TCP", "✔ Server-validated bids", "🔒 In-memory auction state"}) {
            JLabel lbl = new JLabel(item);
            lbl.setFont(FONT_SMALL);
            lbl.setForeground(TEXT_MUTED);
            footer.add(lbl);
        }
        return footer;
    }

    // ── Factories ─────────────────────────────────────────────────────────────
    private static JPanel statCard(String label, JLabel value, Color accent, String icon) {
        RoundPanel card = new RoundPanel(BG_CARD2, 14);
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new CompoundBorder(
                new LineBorderGlow(accent.darker(), 1),
                new EmptyBorder(16, 18, 16, 18)));

        JLabel iconLabel = new JLabel(icon + "  " + label);
        iconLabel.setFont(FONT_LABEL);
        iconLabel.setForeground(accent);

        value.setFont(FONT_VALUE);
        value.setForeground(accent);
        value.setBorder(new EmptyBorder(4, 0, 0, 0));

        card.add(iconLabel, BorderLayout.NORTH);
        card.add(value,     BorderLayout.CENTER);
        return card;
    }

    /** Creates a glowing, hover-responsive button. */
    private static JButton glowButton(String text, Color accent, Color bg) {
        JButton btn = new JButton(text) {
            private float hoverAlpha = 0f;
            private final Timer hoverTimer = new Timer(16, null);
            { // instance init
                setOpaque(false); setContentAreaFilled(false); setFocusPainted(false);
                setBorderPainted(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setFont(FONT_BTN); setForeground(accent);
                setBorder(new CompoundBorder(
                        new LineBorderGlow(accent, 1),
                        new EmptyBorder(9, 18, 9, 18)));
                hoverTimer.addActionListener(e2 -> {
                    boolean hovering = getModel().isRollover();
                    hoverAlpha = hovering
                            ? Math.min(1f, hoverAlpha + 0.12f)
                            : Math.max(0f, hoverAlpha - 0.10f);
                    repaint();
                    if ((hovering && hoverAlpha == 1f) || (!hovering && hoverAlpha == 0f)) hoverTimer.stop();
                });
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hoverTimer.start(); }
                    @Override public void mouseExited(MouseEvent e)  { hoverTimer.start(); }
                });
            }
            @Override public void removeNotify() {
                hoverTimer.stop();
                super.removeNotify();
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight(), r = 10;
                // base fill
                g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 200));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                // hover glow fill
                if (hoverAlpha > 0f) {
                    Color fill = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                            (int)(hoverAlpha * 55));
                    g2.setColor(fill);
                    g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                }
                g2.dispose();
                super.paintComponent(g);
            }
            @Override public boolean isOpaque() { return false; }
        };
        return btn;
    }

    private static JTextField styledField(String text, int cols) {
        JTextField f = new JTextField(text, cols);
        f.setBackground(new Color(15, 20, 38));
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(ACCENT_CYAN);
        f.setFont(FONT_BODY);
        f.setBorder(new CompoundBorder(
                new LineBorderGlow(BORDER_GLOW, 1),
                new EmptyBorder(7, 10, 7, 10)));
        f.setSelectionColor(new Color(0, 180, 160, 100));
        return f;
    }

    private static JLabel fieldLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(FONT_LABEL);
        l.setForeground(TEXT_MUTED);
        return l;
    }

    private static JPanel darkRow() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        p.setOpaque(false);
        return p;
    }

    // ── Wire up events ────────────────────────────────────────────────────────
    private void wireActions() {
        connect.addActionListener(e -> connectToServer());
        disconnect.addActionListener(e -> disconnect());
        join.addActionListener(e -> joinAuction());
        auction.addActionListener(e -> joinAuction());
        bid.addActionListener(e -> placeBid());
        amount.addActionListener(e -> placeBid());
        minimum.addActionListener(e -> {
            JsonNode snap = snapshots.get(currentAuction);
            if (snap != null) {
                amount.setText(snap.path("minimumNextBid").asText());
                amount.requestFocusInWindow();
            }
        });
        minimum.setToolTipText("Fill in the minimum bid; click Place Bid to submit.");
        host.getAccessibleContext().setAccessibleName("Server host");
        port.getAccessibleContext().setAccessibleName("Server port");
        auction.getAccessibleContext().setAccessibleName("Auction ID");
        amount.getAccessibleContext().setAccessibleName("Bid amount");
    }

    // ── Pulse animation ───────────────────────────────────────────────────────
    private void pulseTick() {
        pulseOn = !pulseOn;
        if (online) statusDot.setState(AnimatedDot.State.ONLINE, pulseOn);
        else if (session != null) statusDot.setState(AnimatedDot.State.CONNECTING, pulseOn);
        else statusDot.setState(AnimatedDot.State.OFFLINE, false);
    }

    // ── Networking ────────────────────────────────────────────────────────────
    private void connectToServer() {
        int number;
        try {
            number = Integer.parseInt(port.getText().strip());
            if (number < 1 || number > 65535 || host.getText().isBlank()) throw new NumberFormatException();
        } catch (NumberFormatException ignored) {
            feedback("Enter a valid server host and port (1–65535).", false); return;
        }
        disconnect();
        long attempt = ++generation;
        snapshots.clear(); currentAuction = null; bidderId = null;
        price.setText("—"); nextBid.setText("—"); leader.setText("—"); countdown.setText("—");
        auctionTitle.setText("Your next bid starts here"); state.setText("NOT JOINED");
        session = new GuiConnection(
                frame  -> onUi(attempt, () -> receive(frame)),
                reason -> onUi(attempt, () -> { disconnect(); feedback(reason, false); }));
        connectionStatus.setText("● CONNECTING…");
        connectionStatus.setForeground(ACCENT_GOLD);
        updateControls();
        session.connect(host.getText().strip(), number, () -> onUi(attempt, () -> {
            online = true;
            connectionStatus.setText("● CONNECTED");
            connectionStatus.setForeground(ACCENT_GREEN);
            countdownTimer.start();
            updateControls();
            feedback("Connected. Join an auction to start bidding.", true);
        }));
    }

    private void onUi(long attempt, Runnable action) {
        SwingUtilities.invokeLater(() -> { if (attempt == generation) action.run(); });
    }

    private void joinAuction() {
        if (!online) return;
        String id = auction.getText().strip();
        if (id.isEmpty()) { feedback("Enter an auction ID.", false); return; }
        session.send("JOIN_AUCTION", id, null);
        feedback("Joining " + id + "…", true);
    }

    private void placeBid() {
        if (!bid.isEnabled()) return;
        String value = amount.getText().strip();
        if (value.length() > 64 || !value.matches("[0-9]+(\\.[0-9]{1,2})?")) {
            feedback("Enter a positive decimal amount with at most two decimal places.", false); return;
        }
        session.send("PLACE_BID", currentAuction, value);
    }

    // ── Frame handling ────────────────────────────────────────────────────────
    void receive(JsonNode frame) {
        if (frame == null || !frame.path("payload").isObject()) return;
        String type    = frame.path("type").asText();
        JsonNode payload = frame.get("payload");
        if ("ERROR".equals(type)) { feedback("Server: " + payload.path("reason").asText(), false); return; }
        if (!java.util.Set.of("AUCTION_STATE","AUCTION_UPDATE","AUCTION_ENDED","BID_RESULT").contains(type)) return;
        JsonNode incoming = payload.path("snapshot");
        if (!incoming.path("auctionId").isTextual() || !incoming.path("version").isIntegralNumber()) return;
        String id  = incoming.path("auctionId").asText();
        JsonNode known = snapshots.get(id);
        boolean newer  = known == null || incoming.path("version").asLong() > known.path("version").asLong();
        if (newer) snapshots.put(id, incoming);
        if ("AUCTION_STATE".equals(type)) {
            bidderId       = payload.path("bidderId").asText();
            currentAuction = id;
            feedback("Joined " + id + ". You are ready to bid.", true);
        }
        if (id.equals(currentAuction)) renderSnapshot(snapshots.get(id));
        if ("BID_RESULT".equals(type)) {
            boolean accepted = payload.path("accepted").asBoolean();
            feedback(accepted
                    ? "✅ Bid accepted for " + id + "."
                    : "❌ Bid rejected: " + payload.path("reason").asText(), accepted);
        } else if (newer && "CLOSED".equals(incoming.path("status").asText())) {
            feedback(finalResult(incoming), true);
        } else if (newer && "AUCTION_UPDATE".equals(type)) {
            log("📢 " + id + " updated — price now " + incoming.path("currentPrice").asText(),
                    ACCENT_CYAN);
        }
    }

    private void renderSnapshot(JsonNode snapshot) {
        auctionTitle.setText("Auction  /  " + currentAuction);
        price.setText(snapshot.path("currentPrice").asText());
        nextBid.setText(snapshot.path("minimumNextBid").asText());
        String id = snapshot.path("highestBidderId").asText("");
        leader.setText(id.isEmpty() ? "No bids yet" : id.equals(bidderId) ? "🏆 You" : shortId(id));
        leader.setToolTipText(id.isEmpty() ? "No accepted bids" : id);
        boolean closed = "CLOSED".equals(snapshot.path("status").asText());
        state.setText(closed ? "🔒 CLOSED" : "🟢 LIVE  v" + snapshot.path("version").asLong());
        state.setForeground(closed ? ACCENT_RED : ACCENT_GREEN);
        if (closed) { nextBid.setText("Bidding closed"); feedbackIfFinal(snapshot); }
        updateCountdown(); updateControls();
    }

    private static String shortId(String id) {
        return id.length() > 10 ? id.substring(0, 8) + "…" : id;
    }

    private String finalResult(JsonNode snap) {
        String id = snap.path("highestBidderId").asText("");
        return id.isEmpty()
                ? "🏁 Auction " + snap.path("auctionId").asText() + " ended — no bids, no winner."
                : "🏆 Auction " + snap.path("auctionId").asText() + " ended. Winner: "
                        + (id.equals(bidderId) ? "YOU!" : shortId(id))
                        + "  |  Final price: " + snap.path("currentPrice").asText();
    }

    private void feedbackIfFinal(JsonNode snap) {
        outcome.setText(finalResult(snap));
        outcome.setForeground(ACCENT_GOLD);
    }

    private void updateCountdown() {
        JsonNode snap = snapshots.get(currentAuction);
        if (snap == null) return;
        if ("CLOSED".equals(snap.path("status").asText())) { countdown.setText("Ended"); return; }
        try {
            Instant deadline = Instant.parse(snap.path("deadline").asText());
            long seconds = Math.max(0, Duration.between(Instant.now(), deadline).toSeconds());
            countdown.setText(seconds == 0 ? "Awaiting…" : "%02d:%02d".formatted(seconds / 60, seconds % 60));
            countdown.setForeground(seconds <= 10 ? ACCENT_RED : ACCENT_GOLD);
            countdown.setToolTipText("Server deadline: " + deadline);
        } catch (DateTimeException ignored) { countdown.setText("—"); }
    }

    private void updateControls() {
        boolean busy = session != null;
        connect.setEnabled(!busy); disconnect.setEnabled(busy);
        host.setEnabled(!busy); port.setEnabled(!busy);
        join.setEnabled(online); auction.setEnabled(online);
        JsonNode snap = snapshots.get(currentAuction);
        boolean canBid = online && snap != null && "OPEN".equals(snap.path("status").asText());
        bid.setEnabled(canBid); minimum.setEnabled(canBid); amount.setEnabled(canBid);
        if (!busy) {
            connectionStatus.setText("● OFFLINE");
            connectionStatus.setForeground(ACCENT_RED);
        }
    }

    // ── Feedback / log ────────────────────────────────────────────────────────
    private void feedback(String text, boolean success) {
        outcome.setText(text);
        outcome.setForeground(success ? ACCENT_GREEN : ACCENT_RED);
        log(text, success ? ACCENT_GREEN : ACCENT_RED);
    }

    private void log(String text, Color color) {
        String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        StyledDocument doc = history.getStyledDocument();
        Style tsStyle = history.addStyle("ts", null);
        StyleConstants.setForeground(tsStyle, TEXT_MUTED);
        Style msgStyle = history.addStyle("msg", null);
        StyleConstants.setForeground(msgStyle, color);
        try {
            doc.insertString(doc.getLength(), timestamp + "  ", tsStyle);
            doc.insertString(doc.getLength(), text + "\n", msgStyle);
            if (doc.getLength() > 24000) doc.remove(0, 12000);
            history.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {}
    }

    void disconnect() {
        ++generation;
        if (session != null) session.close();
        session = null; online = false; countdownTimer.stop();
        updateControls();
        outcome.setText("Disconnected. Connect again to start a new session.");
        outcome.setForeground(TEXT_MUTED);
    }

    // =========================================================================
    // ── Custom paint helpers ──────────────────────────────────────────────────

    /** A rounded-corner opaque panel. */
    private static class RoundPanel extends JPanel {
        private final Color bg; private final int radius;
        RoundPanel(Color bg, int radius) {
            this.bg = bg; this.radius = radius;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), radius, radius));
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A panel with a horizontal gradient background. */
    private static class GradientPanel extends JPanel {
        private final Color c1, c2;
        GradientPanel(Color c1, Color c2) { this.c1 = c1; this.c2 = c2; setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setPaint(new GradientPaint(0, 0, c1, getWidth(), getHeight(), c2));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Coloured border that creates a subtle glow. */
    private static class LineBorderGlow extends AbstractBorder {
        private final Color color; private final int thickness;
        LineBorderGlow(Color color, int thickness) { this.color = color; this.thickness = thickness; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(thickness));
            g2.draw(new RoundRectangle2D.Float(x, y, w - 1, h - 1, 10, 10));
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(thickness,thickness,thickness,thickness); }
    }

    /** An animated status indicator dot. */
    private static class AnimatedDot extends JComponent {
        enum State { OFFLINE, CONNECTING, ONLINE }
        private State dotState = State.OFFLINE;
        private boolean bright = false;
        void setState(State s, boolean bright) { this.dotState = s; this.bright = bright; repaint(); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color base = switch (dotState) {
                case OFFLINE     -> ACCENT_RED;
                case CONNECTING  -> ACCENT_GOLD;
                case ONLINE      -> ACCENT_GREEN;
            };
            Color glow = bright ? base.brighter() : base.darker();
            int d = Math.min(getWidth(), getHeight()) - 2;
            int x = (getWidth()  - d) / 2;
            int y = (getHeight() - d) / 2;
            g2.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 50));
            g2.fillOval(x - 2, y - 2, d + 4, d + 4);
            g2.setColor(glow);
            g2.fillOval(x, y, d, d);
            g2.dispose();
        }
    }

    // ── main ──────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
            catch (Exception ignored) {}

            GuiClient client = new GuiClient();
            if (args.length > 0) client.host.setText(args[0]);
            if (args.length > 1) client.port.setText(args[1]);

            JFrame frame = new JFrame("BidNet | Live Auction");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent e) { client.disconnect(); }
            });
            frame.getContentPane().setBackground(BG_DARK);
            frame.setContentPane(client);
            frame.pack();
            frame.setMinimumSize(new Dimension(1080, 720));
            frame.setSize(1200, 800);
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }
}

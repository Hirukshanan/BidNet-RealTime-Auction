package com.bidnet.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.bidnet.network.ServerDiscovery;
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

    final JButton findServers = discoveryButton();
    final JComboBox<ServerDiscovery.Server> availableServers = serverSelector();
    private boolean searching;
    private long searchVersion;

    // ── Status labels ─────────────────────────────────────────────────────────
    final JLabel connectionStatus = new JLabel("● OFFLINE");
    final JLabel auctionTitle     = new JLabel("Your next bid starts here");
    final JLabel price            = new JLabel("—");
    final JLabel nextBid          = new JLabel("—");
    final JLabel leader           = new JLabel("—");
    final JLabel countdown        = new JLabel("—");
    final JLabel outcome          = new JLabel("Connect to a server, then join an auction.");
    final JLabel state            = new JLabel("NOT JOINED");
    private final JLabel workflow = new JLabel();
    private final JLabel nextAction = new JLabel();

    // ── Log area (colour-coded) ────────────────────────────────────────────────
    final JTextPane history = new JTextPane();

    // ── Internal state ────────────────────────────────────────────────────────
    private final Map<String, JsonNode> snapshots = new HashMap<>();
    private final Timer countdownTimer  = new Timer(1000, e -> updateCountdown());
    private final Timer pulseTimer      = new Timer(600,  e -> pulseTick());
    private final Timer guidanceTimer = new Timer(40, event -> {
        join.repaint(); amount.repaint();
    });
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
        JScrollPane page = new JScrollPane(buildBody(),
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        page.setBorder(BorderFactory.createEmptyBorder());
        page.getViewport().setBackground(BG_DARK);
        page.getVerticalScrollBar().setUnitIncrement(24);
        styleScrollBar(page.getVerticalScrollBar());
        add(page, BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);

        wireActions();
        updateControls();
    }

    @Override public void addNotify() {
        super.addNotify();
        pulseTimer.start();
        updateControls();
    }

    @Override public void removeNotify() {
        pulseTimer.stop();
        guidanceTimer.stop();
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
        JPanel guide = new JPanel(new BorderLayout(0, 7));
        guide.setOpaque(false);
        guide.setBorder(new EmptyBorder(16, 10, 14, 10));
        workflow.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        nextAction.setFont(FONT_BODY);
        nextAction.setForeground(TEXT_PRIMARY);
        guide.add(workflow, BorderLayout.NORTH);
        guide.add(nextAction, BorderLayout.SOUTH);
        header.add(guide, BorderLayout.CENTER);
        JPanel networkRows = new JPanel(new BorderLayout());
        networkRows.setOpaque(false);
        networkRows.add(connRow, BorderLayout.NORTH);
        JPanel discoveryRow = new JPanel(new BorderLayout(0, 7));
        discoveryRow.setOpaque(false);
        discoveryRow.setBorder(new EmptyBorder(6, 10, 6, 10));
        JPanel discoveryControls = new JPanel(new BorderLayout(12, 0));
        discoveryControls.setOpaque(false);
        discoveryControls.add(findServers, BorderLayout.WEST);
        availableServers.setPreferredSize(new Dimension(420, 42));
        availableServers.setToolTipText("Select a discovered server, then click Connect.");
        discoveryControls.add(availableServers, BorderLayout.CENTER);
        discoveryRow.add(discoveryControls, BorderLayout.NORTH);
        JLabel discoveryHint = new JLabel("Click Find servers → select a result → Connect");
        discoveryHint.setForeground(TEXT_MUTED);
        discoveryHint.setFont(FONT_SMALL);
        discoveryRow.add(discoveryHint, BorderLayout.SOUTH);
        networkRows.add(discoveryRow, BorderLayout.SOUTH);
        header.add(networkRows, BorderLayout.SOUTH);
        return header;
    }

    // ── Body ──────────────────────────────────────────────────────────────────
    private JPanel buildBody() {
        JPanel body = new ScrollableBody();
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
        cardsWrapper.setMinimumSize(new Dimension(0, 112));
        cardsWrapper.setPreferredSize(new Dimension(900, 120));
        cardsWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
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
        scroll.setPreferredSize(new Dimension(900, 300));
        scroll.setMinimumSize(new Dimension(0, 280));
        scroll.getViewport().setBackground(new Color(8, 12, 22));
        styleScrollBar(scroll.getVerticalScrollBar());
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
                new LineBorderGlow(accent, 1),
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

    private static void styleScrollBar(JScrollBar bar) {
        bar.setPreferredSize(new Dimension(10, 0));
        bar.setBackground(BG_DARK);
        bar.setBorder(BorderFactory.createEmptyBorder());
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return noArrow(); }
            @Override protected JButton createIncreaseButton(int orientation) { return noArrow(); }
            private JButton noArrow() {
                JButton button = new JButton();
                Dimension zero = new Dimension(0, 0);
                button.setPreferredSize(zero); button.setMinimumSize(zero); button.setMaximumSize(zero);
                button.setBorder(BorderFactory.createEmptyBorder());
                button.setFocusable(false);
                return button;
            }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
                g.setColor(BG_DARK);
                g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }
            @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle bounds) {
                if (bounds.isEmpty() || !bar.isEnabled()) return;
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(isDragging ? ACCENT_CYAN.darker()
                        : isThumbRollover() ? new Color(48, 132, 137) : new Color(40, 68, 84));
                g.fillRoundRect(bounds.x + 2, bounds.y + 2,
                        Math.max(3, bounds.width - 4), Math.max(8, bounds.height - 4), 6, 6);
                g.dispose();
            }
        });
    }

    private static JComboBox<ServerDiscovery.Server> serverSelector() {
        JComboBox<ServerDiscovery.Server> selector = new JComboBox<>() {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(BG_CARD2);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        selector.setOpaque(false);
        selector.setBackground(BG_CARD2);
        selector.setForeground(TEXT_PRIMARY);
        selector.setFont(FONT_BODY);
        selector.setBorder(new CompoundBorder(new LineBorderGlow(ACCENT_CYAN.darker(), 1),
                new EmptyBorder(4, 9, 4, 5)));
        selector.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        selector.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value == null ? "Select a server" : value.toString());
            label.setFont(FONT_BODY);
            label.setForeground(selected ? ACCENT_CYAN : TEXT_PRIMARY);
            label.setBackground(selected ? new Color(21, 65, 70) : BG_CARD2);
            label.setOpaque(index >= 0);
            label.setBorder(new EmptyBorder(index < 0 ? 0 : 9, 8, index < 0 ? 0 : 9, 8));
            return label;
        });
        selector.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override public void paintCurrentValue(Graphics g, Rectangle bounds, boolean focus) {
                Component value = comboBox.getRenderer().getListCellRendererComponent(
                        listBox, comboBox.getSelectedItem(), -1, false, false);
                value.setForeground(comboBox.isEnabled() ? TEXT_PRIMARY : TEXT_MUTED);
                value.setFont(FONT_BODY);
                currentValuePane.paintComponent(g, value, comboBox,
                        bounds.x, bounds.y, bounds.width, bounds.height, true);
            }
            @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean focus) {
                // The rounded component paints its own background.
            }
            @Override protected JButton createArrowButton() {
                JButton arrow = new JButton() {
                    @Override protected void paintComponent(Graphics graphics) {
                        Graphics2D g = (Graphics2D) graphics.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(isEnabled() ? ACCENT_CYAN : TEXT_MUTED);
                        int x = getWidth() / 2, y = getHeight() / 2;
                        g.fillPolygon(new int[] {x-5,x+5,x}, new int[] {y-2,y-2,y+4},3);
                        g.dispose();
                    }
                };
                arrow.setOpaque(false); arrow.setContentAreaFilled(false);
                arrow.setBorderPainted(false); arrow.setFocusable(false);
                arrow.setPreferredSize(new Dimension(28,28));
                return arrow;
            }
            @Override protected javax.swing.plaf.basic.ComboPopup createPopup() {
                var popup = new javax.swing.plaf.basic.BasicComboPopup(comboBox);
                popup.setBackground(BG_CARD2);
                popup.setBorder(new LineBorderGlow(ACCENT_CYAN.darker(),1));
                popup.getList().setBackground(BG_CARD2);
                popup.getList().setForeground(TEXT_PRIMARY);
                return popup;
            }
        });
        return selector;
    }

    /** Filled primary action with a gentle periodic sheen; paused when unavailable. */
    private static JButton discoveryButton() {
        return new JButton("Find servers") {
            private final Timer shine = new Timer(40, event -> repaint());
            private long started = System.nanoTime();
            {
                setOpaque(false);
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setForeground(BG_DARK);
                setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
                setBorder(new EmptyBorder(12, 22, 12, 22));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("Click to find BidNet servers on your Wi-Fi. No IP address needed.");
                getAccessibleContext().setAccessibleDescription("Search this network for auction servers");
                addPropertyChangeListener("enabled", event -> {
                    if (isEnabled() && isShowing()) { started = System.nanoTime(); shine.start(); }
                    else shine.stop();
                    repaint();
                });
            }
            @Override public void addNotify() {
                super.addNotify();
                if (isEnabled()) { started = System.nanoTime(); shine.start(); }
            }
            @Override public void removeNotify() { shine.stop(); super.removeNotify(); }
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int width = getWidth(), height = getHeight();
                Shape face = new RoundRectangle2D.Float(2, 2, width - 5, height - 5, 14, 14);
                boolean enabled = isEnabled();
                boolean hover = getModel().isRollover() || hasFocus();
                Color top = enabled ? (hover ? new Color(114, 255, 231) : new Color(65, 235, 210)) : new Color(55, 77, 86);
                Color bottom = enabled ? new Color(0, 171, 160) : new Color(33, 48, 61);
                if (getModel().isPressed()) { top = new Color(0, 156, 146); bottom = new Color(0, 120, 117); }
                g.setPaint(new GradientPaint(0, 0, top, 0, height, bottom));
                g.fill(face);
                if (enabled) {
                    g.setClip(face);
                    // One soft sweep per four seconds; no flashing or size changes.
                    double phase = ((System.nanoTime() - started) / 1_000_000_000.0) % 4.0;
                    if (phase < 1.1) {
                        float x = (float)((phase / 1.1) * (width + 120) - 80);
                        g.setPaint(new GradientPaint(x, 0, new Color(255,255,255,0),
                                x + 40, 0, new Color(255,255,255,85), true));
                        g.fill(new Polygon(new int[] {(int)x, (int)x+40, (int)x+80, (int)x+40},
                                new int[] {0,0,height,height},4));
                    }
                    g.setClip(null);
                }
                g.setColor(enabled ? (hover ? Color.WHITE : ACCENT_CYAN) : new Color(76,97,108));
                g.setStroke(new BasicStroke(hasFocus() ? 2.5f : 1.2f));
                g.draw(face);
                g.dispose();
                super.paintComponent(graphics);
            }
        };
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
                if (Boolean.TRUE.equals(getClientProperty("guided")) && isEnabled()) {
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55));
                    g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                }
                // hover glow fill
                if (hoverAlpha > 0f) {
                    Color fill = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                            (int)(hoverAlpha * 55));
                    g2.setColor(fill);
                    g2.fill(new RoundRectangle2D.Float(0, 0, w, h, r, r));
                }
                g2.dispose();
                super.paintComponent(g);
                paintStepGlow(this, g, accent);
            }
            @Override public boolean isOpaque() { return false; }
        };
        return btn;
    }

    private static void paintStepGlow(JComponent control, Graphics graphics, Color accent) {
        if (!control.isEnabled() || !Boolean.TRUE.equals(control.getClientProperty("guided"))) return;
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double seconds = System.nanoTime() / 1_000_000_000.0;
        int alpha = (int)(145 + 65 * Math.sin(seconds * Math.PI));
        Shape outline = new RoundRectangle2D.Float(2, 2, control.getWidth()-5, control.getHeight()-5, 10, 10);
        g.setStroke(new BasicStroke(2));
        g.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),alpha));
        g.draw(outline);
        float x = (float)((seconds % 3.5) / 3.5 * (control.getWidth()+100) - 50);
        g.setPaint(new GradientPaint(x,0,new Color(255,255,255,0),x+35,0,new Color(255,255,255,200),true));
        g.clipRect((int)x,0,70,control.getHeight());
        g.draw(outline);
        g.dispose();
    }

    private static JTextField styledField(String text, int cols) {
        JTextField f = new JTextField(text, cols) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getBackground());
                g.fillRoundRect(1, 1, getWidth()-2, getHeight()-2, 10, 10);
                g.dispose();
                super.paintComponent(graphics);
            }
            @Override protected void paintBorder(Graphics g) {
                super.paintBorder(g);
                paintStepGlow(this, g, ACCENT_GOLD);
            }
        };
        f.setOpaque(false);
        f.setBackground(new Color(15, 20, 38));
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(ACCENT_CYAN);
        f.setFont(FONT_BODY);
        f.setBorder(new CompoundBorder(
                new LineBorderGlow(BORDER_GLOW, 1),
                new EmptyBorder(7, 10, 7, 10)));
        f.setSelectionColor(new Color(0, 180, 160, 100));
        f.setDisabledTextColor(new Color(155, 173, 203));
        Dimension fieldSize = f.getPreferredSize();
        f.setPreferredSize(new Dimension(fieldSize.width, 38));
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
        ((FlowLayout)p.getLayout()).setAlignOnBaseline(true);
        p.setOpaque(false);
        return p;
    }

    // ── Wire up events ────────────────────────────────────────────────────────
    private void wireActions() {
        findServers.addActionListener(e -> findServers());
        availableServers.addActionListener(e -> {
            if (session == null && availableServers.getSelectedItem() instanceof ServerDiscovery.Server selected) {
                host.setText(selected.host()); port.setText(String.valueOf(selected.port()));
            }
        });
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
    private void findServers() {
        long scan = ++searchVersion;
        searching = true;
        availableServers.removeAllItems();
        findServers.setText("Searching…");
        updateControls();
        feedback("Looking for BidNet servers on this network…", true);
        Thread.startVirtualThread(() -> {
            java.util.List<ServerDiscovery.Server> results;
            String failure = null;
            try { results = ServerDiscovery.find(Duration.ofSeconds(3)); }
            catch (java.io.IOException error) { results = java.util.List.of(); failure = "Network discovery failed. Enter a host manually."; }
            java.util.List<ServerDiscovery.Server> servers = results;
            String message = failure;
            SwingUtilities.invokeLater(() -> {
                if (scan != searchVersion) return;
                searching = false;
                findServers.setText("Find servers");
                for (ServerDiscovery.Server server : servers) availableServers.addItem(server);
                updateControls();
                feedback(message != null ? message : servers.isEmpty()
                        ? "No servers found. Start the server on the same Wi-Fi; check UDP 5052 or enter its host manually."
                        : servers.size() == 1
                                ? "Server found. Host and port filled in — click Connect."
                                : "Found " + servers.size() + " servers. Select one, then click Connect.", !servers.isEmpty());
            });
        });
    }

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
        findServers.setEnabled(!busy && !searching);
        availableServers.setEnabled(!busy && !searching && availableServers.getItemCount() > 0);
        availableServers.setVisible(!busy && !searching && availableServers.getItemCount() > 1);
        availableServers.getParent().revalidate();
        availableServers.getParent().repaint();
        host.setEnabled(!busy); port.setEnabled(!busy);
        join.setEnabled(online); auction.setEnabled(online);
        JsonNode snap = snapshots.get(currentAuction);
        boolean canBid = online && snap != null && "OPEN".equals(snap.path("status").asText());
        bid.setEnabled(canBid); minimum.setEnabled(canBid); amount.setEnabled(canBid);
        boolean needsJoin = online && currentAuction == null;
        join.putClientProperty("guided", needsJoin);
        amount.putClientProperty("guided", canBid);
        if (isDisplayable() && (needsJoin || canBid)) guidanceTimer.start();
        else guidanceTimer.stop();
        join.repaint(); amount.repaint();
        int step = !online ? 1 : currentAuction == null ? 2 : 3;
        String[] steps = {"1. Connect to server", "2. Join auction", "3. Place bid"};
        StringBuilder guide = new StringBuilder("<html>");
        for (int i = 0; i < steps.length; i++) {
            if (i > 0) guide.append(" &nbsp; → &nbsp; ");
            guide.append("<font color='").append(i + 1 == step ? "#00DCC8" : "#6E82AA")
                    .append("'>").append(steps[i]).append("</font>");
        }
        workflow.setText(guide.append("</html>").toString());
        nextAction.setText(!online
                ? busy ? "Connecting… Please wait, or click Disconnect to cancel."
                       : "Start here: click Find servers, then Connect. Or enter the server host and port manually."
                : currentAuction == null ? "Connected! Enter demo in Auction ID, then click Join Auction."
                : canBid ? "Ready! Enter an amount or click Min Bid, then Place Bid. Live updates appear automatically."
                         : "This auction has ended. See the final result below, or join another available auction.");
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
        ++searchVersion; searching = false; findServers.setText("Find servers");
        ++generation;
        if (session != null) session.close();
        session = null; online = false; countdownTimer.stop();
        updateControls();
        outcome.setText("Disconnected. Connect again to start a new session.");
        outcome.setForeground(TEXT_MUTED);
    }

    // =========================================================================
    // ── Custom paint helpers ──────────────────────────────────────────────────

    /** Keep content at a readable height; scroll vertically instead of squeezing the log. */
    private static class ScrollableBody extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 24);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

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
            float inset = thickness / 2f + 0.75f;
            g2.draw(new RoundRectangle2D.Float(x + inset, y + inset,
                    Math.max(0, w - 2 * inset), Math.max(0, h - 2 * inset), 10, 10));
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) {
            int padding = thickness + 2;
            return new Insets(padding,padding,padding,padding);
        }
        @Override public Insets getBorderInsets(Component c, Insets insets) {
            int padding = thickness + 2;
            insets.set(padding,padding,padding,padding);
            return insets;
        }
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

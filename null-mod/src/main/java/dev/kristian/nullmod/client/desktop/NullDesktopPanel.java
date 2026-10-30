package dev.kristian.nullmod.client.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.swing.JComponent;

/**
 * Paints one desktop window: a black panel with white and grey monospaced text, occasional red,
 * a small NULL mark and, always, the footer that says where the window comes from. Animation
 * (typewriter, flicker, character glitches) is advanced by {@link NullDesktopWindow}.
 * <p>
 * Swing event thread only (or any single thread when painting off-screen for previews).
 * No Minecraft classes.
 */
final class NullDesktopPanel extends JComponent {
    static final Color BACKGROUND = new Color(0x000000);
    private static final Color BORDER = new Color(0x3C3C3C);
    private static final Color BORDER_RED = new Color(0x5E1616);
    private static final Color TEXT = new Color(0xE9E9E9);
    private static final Color DIM = new Color(0x8C8C8C);
    private static final Color FAINT = new Color(0x2E2E2E);
    private static final Color RED = new Color(0xCF2A2A);
    private static final Color BRAND = new Color(0x6E6E6E);
    private static final Color BRAND_RED = new Color(0x8A2020);
    private static final Color FOOTER = new Color(0x8A8A8A);
    private static final Color TITLE_BAR = new Color(0x0B0B0B);
    private static final Color TAG = new Color(0x1A1A1A);
    private static final String GLITCH_CHARS = "#%&@$*!?/\\|<>=+~_01";
    private static final String BRAND_TEXT = "NULL";
    private static final String TEST_TEXT = "TEST";
    private static final long MIN_FLICKER_PAUSE_MS = 300L;

    private final DesktopWindowSpec spec;
    private final Random random;
    private final List<Row> rows = new ArrayList<>();

    // layout (pixels, at the panel's own scale)
    private Font headerFont;
    private Font bodyFont;
    private Font extraFont;
    private Font footerFont;
    private Font brandFont;
    private int padding;
    private int panelWidth;
    private int panelHeight;
    private int headerBaseline;
    private int separatorY;
    private int footerBaseline;
    private int keyColumn;
    private int totalChars;

    // animation state
    private int revealed = Integer.MAX_VALUE;
    private boolean typing;
    private boolean cursorOn;
    private float brightness = 1.0F;
    private float windowFlicker = 1.0F;
    private long glitchUntil;
    private long nextGlitchAt;
    private long flickerUntil;
    private long nextFlickerAt;
    private float flickerDepth;
    private int jitterRow = -1;
    private int jitterPx;

    /** One line of text after wrapping. */
    private static final class Row {
        final String label;
        final String text;
        final DesktopWindowSpec.Tone tone;
        final boolean extra;
        String shownLabel;
        String shownText;
        int baseline;
        int x;

        Row(String label, String text, DesktopWindowSpec.Tone tone, boolean extra) {
            this.label = label;
            this.text = text;
            this.tone = tone;
            this.extra = extra;
            this.shownLabel = label;
            this.shownText = text;
        }

        int length() {
            return label.length() + text.length();
        }
    }

    NullDesktopPanel(DesktopWindowSpec spec, int maxWidth, int maxHeight) {
        this.spec = spec;
        this.random = new Random(spec.seed());
        setOpaque(true);
        setBackground(BACKGROUND);
        setFocusable(false);
        layoutContent(Math.max(200, maxWidth), Math.max(90, maxHeight));
        Dimension size = new Dimension(panelWidth, panelHeight);
        setPreferredSize(size);
        setMinimumSize(size);
        setSize(size);
        if (spec.typewriter()) {
            revealed = 0;
            typing = true;
        }
        nextGlitchAt = 250L + (long) (random.nextFloat() * 900.0F);
        nextFlickerAt = 150L + (long) (random.nextFloat() * 700.0F);
    }

    // ------------------------------------------------------------------------------------------
    // layout

    private void layoutContent(int maxWidth, int maxHeight) {
        float scale = spec.textScale();
        for (int attempt = 0; attempt < 10; attempt++) {
            if (tryLayout(scale, maxWidth) && panelHeight <= maxHeight) {
                return;
            }
            scale *= 0.86F;
            if (scale < 0.5F) {
                break;
            }
        }
        tryLayout(Math.max(0.5F, scale), maxWidth);
    }

    /** Lays the content out at {@code scale}; false when it does not fit {@code maxWidth}. */
    private boolean tryLayout(float scale, int maxWidth) {
        DesktopWindowSpec.Layout layout = spec.layout();
        float headerSize;
        float bodySize;
        int bodyStyle = Font.PLAIN;
        int contentMax;
        int minWidth;
        int pad;
        switch (layout) {
            case ERROR_BOX -> {
                headerSize = 13.0F;
                bodySize = 14.0F;
                contentMax = 420;
                minWidth = 300;
                pad = 14;
            }
            case BIG_TEXT -> {
                headerSize = 11.0F;
                bodySize = 30.0F;
                bodyStyle = Font.BOLD;
                contentMax = 640;
                minWidth = 260;
                pad = 18;
            }
            case REPORT -> {
                headerSize = 14.0F;
                bodySize = 13.0F;
                contentMax = 480;
                minWidth = 340;
                pad = 14;
            }
            default -> {
                headerSize = 12.0F;
                bodySize = 14.0F;
                contentMax = 340;
                minWidth = 250;
                pad = 12;
            }
        }
        padding = Math.round(pad * Math.max(0.8F, Math.min(1.4F, scale)));
        headerFont = mono(layout == DesktopWindowSpec.Layout.REPORT || layout == DesktopWindowSpec.Layout.ERROR_BOX ? Font.BOLD : Font.PLAIN,
                headerSize * Math.max(0.85F, Math.min(1.3F, scale)));
        bodyFont = mono(bodyStyle, bodySize * scale);
        extraFont = mono(Font.PLAIN, (layout == DesktopWindowSpec.Layout.BIG_TEXT ? 15.0F : bodySize) * Math.max(0.8F, scale * 0.9F));
        footerFont = mono(Font.PLAIN, 11.0F);
        brandFont = mono(Font.BOLD, 11.0F);

        Graphics2D g = scratch();
        FontMetrics header = g.getFontMetrics(headerFont);
        FontMetrics body = g.getFontMetrics(bodyFont);
        FontMetrics extra = g.getFontMetrics(extraFont);
        FontMetrics footer = g.getFontMetrics(footerFont);
        FontMetrics brand = g.getFontMetrics(brandFont);

        int available = Math.min(Math.round(contentMax * Math.max(1.0F, scale)), maxWidth - padding * 2);
        boolean fits = true;

        // wrap the message lines
        rows.clear();
        keyColumn = 0;
        if (layout == DesktopWindowSpec.Layout.REPORT) {
            for (DesktopWindowSpec.Line line : spec.lines()) {
                if (!line.label().isEmpty()) {
                    keyColumn = Math.max(keyColumn, body.stringWidth(line.label() + " "));
                }
            }
            keyColumn = Math.min(keyColumn, available / 2);
        }
        for (int i = 0; i < spec.lines().size(); i++) {
            DesktopWindowSpec.Line line = spec.lines().get(i);
            boolean isExtra = layout == DesktopWindowSpec.Layout.BIG_TEXT && i > 0;
            FontMetrics metrics = isExtra ? extra : body;
            int width = available - (layout == DesktopWindowSpec.Layout.REPORT ? keyColumn : 0);
            List<String> wrapped = wrap(line.text(), metrics, Math.max(40, width));
            for (int j = 0; j < wrapped.size(); j++) {
                String piece = wrapped.get(j);
                if (metrics.stringWidth(piece) > width) {
                    fits = false;
                }
                rows.add(new Row(j == 0 ? line.label() : "", piece, line.tone(), isExtra));
            }
        }

        // widths
        int contentWidth = 0;
        for (Row row : rows) {
            FontMetrics metrics = row.extra ? extra : body;
            int width = (layout == DesktopWindowSpec.Layout.REPORT ? keyColumn : 0) + metrics.stringWidth(row.text);
            if (layout == DesktopWindowSpec.Layout.ERROR_BOX) {
                width += body.stringWidth("> ");
            }
            contentWidth = Math.max(contentWidth, width);
        }
        int brandWidth = brand.stringWidth(BRAND_TEXT) + (spec.test() ? brand.stringWidth(" " + TEST_TEXT) + 8 : 0);
        if (!spec.header().isEmpty()) {
            contentWidth = Math.max(contentWidth, header.stringWidth(spec.header()) + 18 + brandWidth);
        }
        contentWidth = Math.max(contentWidth, footer.stringWidth(spec.footer()));
        panelWidth = Math.max(minWidth, contentWidth + padding * 2);
        if (panelWidth > maxWidth) {
            fits = false;
            panelWidth = maxWidth;
        }

        // vertical layout
        int y = padding;
        switch (layout) {
            case ERROR_BOX -> {
                int bar = header.getHeight() + 12;
                headerBaseline = (bar - header.getHeight()) / 2 + header.getAscent();
                separatorY = bar;
                y = bar + padding;
            }
            case BIG_TEXT -> {
                headerBaseline = padding - 4 + brand.getAscent();
                separatorY = -1;
                y = padding + brand.getHeight() + Math.round(8 * scale);
            }
            default -> {
                headerBaseline = y + header.getAscent();
                y += header.getHeight() + 6;
                separatorY = y;
                y += 8;
            }
        }
        int lineGap = layout == DesktopWindowSpec.Layout.BIG_TEXT ? 4 : 3;
        totalChars = 0;
        for (Row row : rows) {
            FontMetrics metrics = row.extra ? extra : body;
            if (row.extra) {
                y += 6;
            }
            row.baseline = y + metrics.getAscent();
            y += metrics.getHeight() + lineGap;
            totalChars += row.length();
            if (layout == DesktopWindowSpec.Layout.BIG_TEXT) {
                row.x = (panelWidth - metrics.stringWidth(row.text)) / 2;
            } else {
                row.x = padding + (layout == DesktopWindowSpec.Layout.ERROR_BOX ? body.stringWidth("> ") : 0);
            }
        }
        y += layout == DesktopWindowSpec.Layout.BIG_TEXT ? Math.round(10 * scale) : 8;
        footerBaseline = y + footer.getAscent();
        y += footer.getHeight() + padding - 2;
        panelHeight = y;
        return fits;
    }

    private static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> out = new ArrayList<>(2);
        if (metrics.stringWidth(text) <= width) {
            out.add(text);
            return out;
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= width || line.isEmpty()) {
                line.setLength(0);
                line.append(candidate);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }

    private static Font mono(int style, float size) {
        return new Font(Font.MONOSPACED, style, 12).deriveFont(style, Math.max(9.0F, size));
    }

    private static Graphics2D scratchGraphics;

    /** A graphics context for measuring text before the window exists (works headless too). */
    private static Graphics2D scratch() {
        if (scratchGraphics == null) {
            Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            scratchGraphics = g;
        }
        return scratchGraphics;
    }

    // ------------------------------------------------------------------------------------------
    // animation

    /** Advances typewriter, flicker and glitches to {@code ageMs} after the window opened. */
    void advance(long ageMs) {
        // typewriter
        if (typing) {
            int perSecond = spec.layout() == DesktopWindowSpec.Layout.REPORT ? 46 : 34;
            long start = 260L;
            revealed = ageMs <= start ? 0 : (int) Math.min(Integer.MAX_VALUE, (ageMs - start) * perSecond / 1000L);
            if (revealed >= totalChars) {
                revealed = Integer.MAX_VALUE;
                typing = false;
            }
        }
        // the cursor blinks while typing (steady when flashing is off) and shortly after
        cursorOn = typing && (spec.flicker() <= 0.0F || (ageMs / 380L) % 2L == 0L);

        // brightness flicker: short dips at random moments, each with one steady depth, and never
        // more than about three dips a second (photosensitivity)
        brightness = 1.0F;
        windowFlicker = 1.0F;
        if (spec.flicker() > 0.0F) {
            if (ageMs >= nextFlickerAt) {
                flickerUntil = ageMs + 40L + random.nextInt(110);
                flickerDepth = spec.flicker() * (0.35F + 0.5F * random.nextFloat());
                float rate = spec.layout() == DesktopWindowSpec.Layout.BIG_TEXT ? 0.35F : 1.6F;
                long pause = (long) ((rate * 1000.0F) * (0.3F + random.nextFloat()) / (0.25F + spec.flicker()));
                nextFlickerAt = flickerUntil + Math.max(MIN_FLICKER_PAUSE_MS, pause);
            }
            if (ageMs < flickerUntil) {
                brightness = Math.max(0.15F, 1.0F - flickerDepth);
                windowFlicker = Math.max(0.35F, 1.0F - flickerDepth * 0.7F);
            }
        }

        // character glitches: a few characters swap for a few frames
        if (spec.distortion() > 0.0F) {
            if (ageMs >= nextGlitchAt) {
                glitchUntil = ageMs + 70L + random.nextInt(130);
                float pause = 600.0F + 2600.0F * (1.0F - spec.distortion());
                nextGlitchAt = glitchUntil + (long) (pause * (0.4F + random.nextFloat()));
                scramble();
            } else if (ageMs >= glitchUntil && jitterRow != -2) {
                restore();
            }
        }
    }

    private void scramble() {
        int swaps = 1 + Math.round(spec.distortion() * 3.0F);
        for (Row row : rows) {
            row.shownText = swap(row.text, random.nextFloat() < 0.5F + spec.distortion() * 0.5F ? swaps : 0);
            row.shownLabel = swap(row.label, random.nextFloat() < 0.25F ? 1 : 0);
        }
        jitterRow = rows.isEmpty() ? -1 : random.nextInt(rows.size());
        jitterPx = (random.nextBoolean() ? 1 : -1) * (1 + Math.round(spec.distortion() * (2 + random.nextInt(4))));
    }

    private void restore() {
        for (Row row : rows) {
            row.shownText = row.text;
            row.shownLabel = row.label;
        }
        jitterRow = -2;
        jitterPx = 0;
    }

    private String swap(String text, int count) {
        if (count <= 0 || text.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < count; i++) {
            int index = random.nextInt(chars.length);
            if (chars[index] != ' ') {
                chars[index] = GLITCH_CHARS.charAt(random.nextInt(GLITCH_CHARS.length()));
            }
        }
        return new String(chars);
    }

    /** 0.35..1 opacity factor for windows that flicker as a whole (when translucency works). */
    float windowFlicker() {
        return windowFlicker;
    }

    /** Shows the whole text at once (used for previews and when the window closes early). */
    void finishTyping() {
        revealed = Integer.MAX_VALUE;
        typing = false;
        cursorOn = false;
    }

    // ------------------------------------------------------------------------------------------
    // painting

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, w, h);
            g.setColor(spec.redAccent() ? BORDER_RED : BORDER);
            g.drawRect(0, 0, w - 1, h - 1);

            paintHeader(g, w);
            paintRows(g);
            paintFooter(g);
        } finally {
            g.dispose();
        }
    }

    private void paintHeader(Graphics2D g, int w) {
        DesktopWindowSpec.Layout layout = spec.layout();
        FontMetrics brand = g.getFontMetrics(brandFont);
        int brandX = w - padding - brand.stringWidth(BRAND_TEXT);
        if (layout == DesktopWindowSpec.Layout.ERROR_BOX) {
            g.setColor(TITLE_BAR);
            g.fillRect(1, 1, w - 2, separatorY - 1);
            g.setColor(spec.redAccent() ? BORDER_RED : BORDER);
            g.drawLine(1, separatorY, w - 2, separatorY);
            // a small blinking block before the brand; steady when flashing is off
            boolean blink = spec.flicker() <= 0.0F || cursorBlink();
            if (blink) {
                g.setColor(RED);
                int size = Math.max(5, brand.getAscent() - 4);
                g.fillRect(brandX - size - 7, headerBaseline - size, size, size);
            }
        }
        if (!spec.header().isEmpty() && layout != DesktopWindowSpec.Layout.BIG_TEXT) {
            g.setFont(headerFont);
            boolean warn = spec.header().startsWith("WARNING") || spec.redAccent();
            Color headerColor = layout == DesktopWindowSpec.Layout.CORNER ? (warn ? RED : DIM) : TEXT;
            g.setColor(scale(headerColor, brightness));
            g.drawString(spec.header(), padding, headerBaseline);
        }
        // brand mark (top right; top left for big text)
        g.setFont(brandFont);
        g.setColor(spec.redAccent() ? BRAND_RED : BRAND);
        if (layout == DesktopWindowSpec.Layout.BIG_TEXT) {
            g.drawString(BRAND_TEXT, padding, headerBaseline);
            if (spec.test()) {
                paintTestTag(g, padding + brand.stringWidth(BRAND_TEXT + " "), headerBaseline);
            }
        } else {
            g.drawString(BRAND_TEXT, brandX, headerBaseline);
            if (spec.test()) {
                int tagWidth = brand.stringWidth(TEST_TEXT) + 6;
                int offset = layout == DesktopWindowSpec.Layout.ERROR_BOX ? Math.max(5, brand.getAscent() - 4) + 14 : 8;
                paintTestTag(g, brandX - offset - tagWidth, headerBaseline);
            }
        }
        if (separatorY > 0 && layout != DesktopWindowSpec.Layout.ERROR_BOX) {
            g.setColor(FAINT);
            if (layout == DesktopWindowSpec.Layout.REPORT) {
                // a dashed rule under the report title
                for (int x = padding; x < w - padding; x += 7) {
                    g.drawLine(x, separatorY, Math.min(x + 3, w - padding), separatorY);
                }
            } else {
                g.drawLine(padding, separatorY, w - padding, separatorY);
            }
        }
    }

    private void paintTestTag(Graphics2D g, int x, int baseline) {
        FontMetrics metrics = g.getFontMetrics(brandFont);
        int width = metrics.stringWidth(TEST_TEXT) + 6;
        g.setColor(TAG);
        g.fillRect(x, baseline - metrics.getAscent() - 1, width, metrics.getAscent() + 3);
        g.setColor(DIM);
        g.drawString(TEST_TEXT, x + 3, baseline);
    }

    private boolean cursorBlink() {
        return (System.currentTimeMillis() / 520L) % 2L == 0L;
    }

    private void paintRows(Graphics2D g) {
        DesktopWindowSpec.Layout layout = spec.layout();
        int budget = revealed;
        Color normal = scale(TEXT, brightness);
        Color dim = scale(DIM, brightness);
        Color red = scale(RED, Math.max(0.4F, brightness));
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (budget <= 0 && !(cursorOn && i == 0)) {
                break;
            }
            int jitter = i == jitterRow ? jitterPx : 0;
            Font font = row.extra ? extraFont : bodyFont;
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics(font);
            int x = row.x + jitter;

            if (layout == DesktopWindowSpec.Layout.ERROR_BOX && i == 0) {
                g.setColor(red);
                g.drawString(">", padding + jitter, row.baseline);
            }
            String label = row.shownLabel;
            String text = row.shownText;
            int labelChars = Math.min(label.length(), budget);
            budget -= labelChars;
            int textChars = Math.min(text.length(), Math.max(0, budget));
            budget -= textChars;

            if (layout == DesktopWindowSpec.Layout.REPORT) {
                if (labelChars > 0) {
                    g.setColor(dim);
                    g.drawString(label.substring(0, labelChars), padding + jitter, row.baseline);
                }
                x = padding + keyColumn + jitter;
            }
            if (textChars > 0) {
                g.setColor(switch (row.tone) {
                    case WARNING -> red;
                    case DIM -> dim;
                    default -> normal;
                });
                g.drawString(text.substring(0, textChars), x, row.baseline);
            }
            boolean rowComplete = labelChars == label.length() && textChars == text.length();
            if (cursorOn && (!rowComplete || i == rows.size() - 1 || budget <= 0)) {
                int cursorX = x + metrics.stringWidth(text.substring(0, textChars)) + 2;
                g.setColor(normal);
                g.fillRect(cursorX, row.baseline - metrics.getAscent() + 2, Math.max(6, metrics.charWidth('M') - 2), metrics.getAscent() - 1);
                break;
            }
        }
    }

    private void paintFooter(Graphics2D g) {
        // the label is always steady and readable: no flicker, no glitches, no typewriter
        g.setFont(footerFont);
        g.setColor(FOOTER);
        g.drawString(spec.footer(), padding, footerBaseline);
    }

    private static Color scale(Color color, float factor) {
        if (factor >= 0.999F) {
            return color;
        }
        float f = Math.max(0.0F, Math.min(1.0F, factor));
        return new Color(Math.round(color.getRed() * f), Math.round(color.getGreen() * f), Math.round(color.getBlue() * f));
    }

    /** Renders the finished window into an image (previews and tests). */
    BufferedImage renderPreview() {
        BufferedImage image = new BufferedImage(panelWidth, panelHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            setSize(panelWidth, panelHeight);
            paintComponent(g);
        } finally {
            g.dispose();
        }
        return image;
    }
}

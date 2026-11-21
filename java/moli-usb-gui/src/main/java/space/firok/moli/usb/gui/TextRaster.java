package space.firok.moli.usb.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * 用 {@link Font} 在主机上把文字光栅化成两色 RGB565。
 *
 * <p>不使用设备 ASCII/GB2312 字库。应在 JavaFX 线程和设备 worker 之外调用。</p>
 */
final class TextRaster {

    /** 滚动条带的宽度上限，避免超长文字一次分配过大的位图。 */
    static final int MAX_STRIP_WIDTH = 8192;

    private TextRaster() {
    }

    static Rgb565Image centered(int width, int height, String text,
                                int text565, int background565, int fontSize) {
        String[] lines = text == null || text.isEmpty() ? new String[]{""} : text.split("\\R", -1);
        int size = fit(width, longest(lines), fontSize);
        return draw(width, height, text565, background565, size, (g, font) -> {
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int block = metrics.getHeight() * lines.length;
            int y = (height - block) / 2 + metrics.getAscent();
            if (y < metrics.getAscent()) {
                y = metrics.getAscent();
            }
            for (String line : lines) {
                int x = Math.max(0, (width - metrics.stringWidth(line)) / 2);
                g.drawString(line, x, y);
                y += metrics.getHeight();
            }
        });
    }

    /**
     * 滚动用的横向条带：文字从左侧开始，右侧留出一个面板宽的空白，窗口滑过时文字能完全走出画面。
     */
    static Rgb565Image scrollingStrip(int panelWidth, int panelHeight, String text,
                                      int text565, int background565, int fontSize) {
        String line = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ');
        // 滚动文字允许比面板宽，不要为了塞进一屏而缩小字号。
        int size = Math.max(1, fontSize);
        int textWidth = stringWidth(line, size);
        int stripWidth = textWidth + panelWidth;
        if (stripWidth < panelWidth) {
            stripWidth = panelWidth;
        }
        if (stripWidth > MAX_STRIP_WIDTH) {
            stripWidth = MAX_STRIP_WIDTH;
        }
        int width = stripWidth;
        return draw(width, panelHeight, text565, background565, size, (g, font) -> {
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int y = (panelHeight - metrics.getHeight()) / 2 + metrics.getAscent();
            if (y < metrics.getAscent()) {
                y = metrics.getAscent();
            }
            g.drawString(line, 0, y);
        });
    }

    static Rgb565Image twoLines(int width, int height,
                                String primary, int primarySize,
                                String secondary, int secondarySize,
                                int text565, int background565) {
        int primaryFit = fit(width, primary, primarySize);
        int secondaryFit = fit(width, secondary, secondarySize);
        return draw(width, height, text565, background565, primaryFit, (g, ignored) -> {
            Font primaryFont = panelFont(primaryFit);
            Font secondaryFont = panelFont(secondaryFit);
            g.setFont(primaryFont);
            FontMetrics primaryMetrics = g.getFontMetrics();
            g.setFont(secondaryFont);
            FontMetrics secondaryMetrics = g.getFontMetrics();
            int block = primaryMetrics.getHeight() + secondaryMetrics.getHeight();
            int y = (height - block) / 2 + primaryMetrics.getAscent();
            if (y < primaryMetrics.getAscent()) {
                y = primaryMetrics.getAscent();
            }
            g.setFont(primaryFont);
            int primaryX = Math.max(0, (width - primaryMetrics.stringWidth(primary)) / 2);
            g.drawString(primary, primaryX, y);
            y += primaryMetrics.getHeight() - primaryMetrics.getAscent() + secondaryMetrics.getAscent();
            g.setFont(secondaryFont);
            int secondaryX = Math.max(0, (width - secondaryMetrics.stringWidth(secondary)) / 2);
            g.drawString(secondary, secondaryX, y);
        });
    }

    private static Rgb565Image draw(int width, int height, int text565, int background565,
                                    int fontSize, Painter painter) {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = mask.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            painter.paint(graphics, panelFont(fontSize));
        } finally {
            graphics.dispose();
        }
        int[] argb = mask.getRGB(0, 0, width, height, null, 0, width);
        int[] pixels = new int[width * height];
        int background = background565 & 0xFFFF;
        int foreground = text565 & 0xFFFF;
        Arrays.fill(pixels, background);
        for (int i = 0; i < argb.length; i++) {
            if ((argb[i] & 0xFFFFFF) != 0) {
                pixels[i] = foreground;
            }
        }
        return new Rgb565Image(width, height, pixels);
    }

    private static int fit(int width, String text, int requested) {
        int size = Math.max(1, requested);
        if (text == null || text.isEmpty() || width <= 2) {
            return size;
        }
        while (size > 1 && stringWidth(text, size) > width - 2) {
            size--;
        }
        return size;
    }

    private static int stringWidth(String text, int size) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = probe.createGraphics();
        try {
            graphics.setFont(panelFont(size));
            return graphics.getFontMetrics().stringWidth(text == null ? "" : text);
        } finally {
            graphics.dispose();
        }
    }

    private static String longest(String[] lines) {
        String best = "";
        for (String line : lines) {
            if (line != null && line.length() > best.length()) {
                best = line;
            }
        }
        return best;
    }

    /**
     * 优先雅黑，以便中文主机文字能画出来；没有时退回逻辑字体 Dialog。
     * 这是主机字体，不是设备字库。
     */
    static Font panelFont(int size) {
        int safe = Math.max(1, size);
        Font yahei = new Font("Microsoft YaHei", Font.PLAIN, safe);
        if (yahei.canDisplay('中') && yahei.canDisplay('文')) {
            return yahei;
        }
        return new Font(Font.DIALOG, Font.PLAIN, safe);
    }

    @FunctionalInterface
    private interface Painter {
        void paint(Graphics2D graphics, Font font);
    }
}

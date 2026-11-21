package space.firok.moli.usb.gui;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.OptionalDouble;

/**
 * 主机绘制的两色系统状态。标签和百分比条都用 {@link java.awt.Font}，不用设备字库。
 * 面板宽高由调用方传入，不假定 160×80 或 64×64。
 */
final class StatsRaster {

    private static final String[] LABELS = {"CPU", "内存", "磁盘", "电池"};

    private StatsRaster() {
    }

    static Rgb565Image render(int width, int height, SystemSample sample, int text565, int background565) {
        BufferedImage mask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = mask.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            paint(graphics, width, height, sample);
        } finally {
            graphics.dispose();
        }
        int[] argb = mask.getRGB(0, 0, width, height, null, 0, width);
        int[] pixels = new int[width * height];
        int background = background565 & 0xFFFF;
        int foreground = text565 & 0xFFFF;
        for (int i = 0; i < argb.length; i++) {
            pixels[i] = (argb[i] & 0xFFFFFF) != 0 ? foreground : background;
        }
        return new Rgb565Image(width, height, pixels);
    }

    private static void paint(Graphics2D graphics, int width, int height, SystemSample sample) {
        String[] values = {
                formatPercent(sample.cpuPercent()),
                formatPercent(sample.memoryUsedPercent()),
                formatPercent(sample.diskUsedPercent()),
                formatBattery(sample.battery())
        };
        Double[] bars = {
                bar(sample.cpuPercent()),
                bar(sample.memoryUsedPercent()),
                bar(sample.diskUsedPercent()),
                sample.battery().kind() == BatteryKind.PRESENT ? bar(sample.battery().percent()) : null
        };
        int rowHeight = height / LABELS.length;
        if (rowHeight <= 0 || width <= 0) {
            return;
        }
        int fontSize = fitFont(width, values, rowHeight);
        Font font = TextRaster.panelFont(fontSize);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics();
        boolean drawBars = rowHeight >= metrics.getHeight() + 4 && width >= 8;
        for (int i = 0; i < LABELS.length; i++) {
            int top = i * rowHeight;
            int textY = drawBars
                    ? top + metrics.getAscent()
                    : top + Math.max(0, (rowHeight - metrics.getHeight()) / 2) + metrics.getAscent();
            graphics.drawString(LABELS[i] + " " + values[i], 1, textY);
            if (!drawBars || bars[i] == null) {
                continue;
            }
            int barTop = top + metrics.getHeight() + 1;
            int barHeight = rowHeight - metrics.getHeight() - 2;
            int barWidth = width - 2;
            if (barHeight < 2 || barWidth < 2) {
                continue;
            }
            graphics.drawRect(1, barTop, barWidth - 1, barHeight - 1);
            int inner = barWidth - 2;
            int filled = (int) Math.round(inner * bars[i] / 100.0);
            filled = Math.max(0, Math.min(inner, filled));
            if (filled > 0 && barHeight > 2) {
                graphics.fillRect(2, barTop + 1, filled, barHeight - 2);
            }
        }
    }

    private static String formatPercent(OptionalDouble value) {
        if (value == null || value.isEmpty()) {
            return "不可用";
        }
        return Math.round(value.getAsDouble()) + "%";
    }

    private static String formatBattery(BatteryReading battery) {
        return switch (battery.kind()) {
            case ABSENT -> "无电池";
            case UNAVAILABLE -> "不可用";
            case PRESENT -> formatPercent(battery.percent());
        };
    }

    private static Double bar(OptionalDouble value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.getAsDouble();
    }

    private static int fitFont(int width, String[] values, int rowHeight) {
        int size = Math.max(1, Math.min(rowHeight, 18));
        while (size > 1 && !fits(width, values, size)) {
            size--;
        }
        return size;
    }

    private static boolean fits(int width, String[] values, int size) {
        Font font = TextRaster.panelFont(size);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = probe.createGraphics();
        try {
            graphics.setFont(font);
            FontMetrics metrics = graphics.getFontMetrics();
            for (int i = 0; i < LABELS.length; i++) {
                if (metrics.stringWidth(LABELS[i] + " " + values[i]) > width - 2) {
                    return false;
                }
            }
            return true;
        } finally {
            graphics.dispose();
        }
    }
}

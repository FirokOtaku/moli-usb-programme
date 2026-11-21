package space.firok.moli.usb.gui;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 主机光栅化的时钟。字号按面板高度计算，不使用设备 ASCII/GB2312 字形。
 */
final class ClockRaster {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private ClockRaster() {
    }

    static Rgb565Image render(int width, int height, ZonedDateTime time, int text565, int background565) {
        int timeSize = Math.max(8, height * 42 / 100);
        int dateSize = Math.max(8, height * 20 / 100);
        return TextRaster.twoLines(
                width,
                height,
                TIME.format(time),
                timeSize,
                DATE.format(time),
                dateSize,
                text565,
                background565);
    }
}

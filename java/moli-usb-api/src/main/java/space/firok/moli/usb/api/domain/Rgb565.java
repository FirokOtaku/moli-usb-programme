package space.firok.moli.usb.api.domain;

import java.awt.Color;
import java.util.Objects;

/**
 * 领域层：RGB565 颜色值。
 *
 * <p>16 位无符号颜色值；图像数据按高字节在前发送。超出 0x0000..0xFFFF 的值视为
 * 非法参数。</p>
 */
public record Rgb565(int value) {

    /** 红色（0xF800），与厂商演示常量一致。 */
    public static final Rgb565 RED = new Rgb565(0xF800);
    /** 绿色（0x07E0）。 */
    public static final Rgb565 GREEN = new Rgb565(0x07E0);
    /** 蓝色（0x001F）。 */
    public static final Rgb565 BLUE = new Rgb565(0x001F);
    /** 白色（0xFFFF）。 */
    public static final Rgb565 WHITE = new Rgb565(0xFFFF);
    /** 黑色（0x0000）。 */
    public static final Rgb565 BLACK = new Rgb565(0x0000);

    public Rgb565 {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(
                    "RGB565 value must be 0x0000..0xFFFF: 0x" + Integer.toHexString(value));
        }
    }

    /**
     * 从 8-8-8 RGB 分量近似量化为 RGB565（截断低位的简单量化）。
     */
    public static Rgb565 fromRgb888(int r, int g, int b) {
        int r5 = Math.clamp(r, 0, 255) >> 3;
        int g6 = Math.clamp(g, 0, 255) >> 2;
        int b5 = Math.clamp(b, 0, 255) >> 3;
        return new Rgb565((r5 << 11) | (g6 << 5) | b5);
    }

    /** Converts {@code 0xRRGGBB} to RGB565. */
    public static Rgb565 fromRgb(int rgb) {
        return fromRgb888((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF);
    }

    /** Converts {@code 0xAARRGGBB} to RGB565, ignoring alpha. */
    public static Rgb565 fromArgb(int argb) {
        return fromRgb(argb);
    }

    /** Converts a color to RGB565, ignoring its alpha component. */
    public static Rgb565 fromColor(Color color) {
        Objects.requireNonNull(color, "color");
        return fromRgb888(color.getRed(), color.getGreen(), color.getBlue());
    }

    /** 高字节（线上先发送）。 */
    public int highByte() {
        return (value >> 8) & 0xFF;
    }

    /** 低字节。 */
    public int lowByte() {
        return value & 0xFF;
    }
}

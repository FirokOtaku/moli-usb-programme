package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.domain.Rgb565;

import java.util.Arrays;

/**
 * 纯 Java 的画面缩放、RGB565 打包和滚动窗口裁剪。
 *
 * <p>不依赖 JavaFX，也不打开串口。面板宽高由调用方传入，这里不假定 160×80 或 64×64。</p>
 */
final class FrameOps {

    private FrameOps() {
    }

    /** 8-8-8 分量量化为 RGB565，走 {@link Rgb565#fromRgb888(int, int, int)}。 */
    static int pack(int r, int g, int b) {
        return Rgb565.fromRgb888(r, g, b).value();
    }

    /** {@code 0xRRGGBB}（高位忽略）量化为 RGB565。 */
    static int packRgb(int rgb) {
        return pack((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    /** 把 RGB565 展开为不透明 ARGB，供预览使用。不做串口 IO。 */
    static int toArgb(int rgb565) {
        int r = ((rgb565 >> 11) & 0x1F) * 255 / 31;
        int g = ((rgb565 >> 5) & 0x3F) * 255 / 63;
        int b = (rgb565 & 0x1F) * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int[] toArgb(int[] rgb565) {
        int[] argb = new int[rgb565.length];
        for (int i = 0; i < rgb565.length; i++) {
            argb[i] = toArgb(rgb565[i]);
        }
        return argb;
    }

    static int[] fill(int width, int height, int rgb565) {
        requireSize(width, height);
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, rgb565 & 0xFFFF);
        return pixels;
    }

    /**
     * 等比适应（contain）：整图落在目标内，多余处用 {@code pad565} 留边。
     * 最近邻取样，行优先。
     *
     * @param srcRgb 行优先 {@code 0xRRGGBB}
     */
    static int[] scaleContain(int srcWidth, int srcHeight, int[] srcRgb,
                              int dstWidth, int dstHeight, int pad565) {
        requireSize(srcWidth, srcHeight);
        requireSize(dstWidth, dstHeight);
        requireRgb(srcRgb, srcWidth, srcHeight);
        int[] dst = fill(dstWidth, dstHeight, pad565);
        int drawWidth;
        int drawHeight;
        // contain 取较小缩放比：源更宽时受宽度限制。
        boolean widthLimited = (long) dstWidth * srcHeight <= (long) dstHeight * srcWidth;
        if (widthLimited) {
            drawWidth = dstWidth;
            drawHeight = (int) ((long) srcHeight * dstWidth / srcWidth);
        } else {
            drawHeight = dstHeight;
            drawWidth = (int) ((long) srcWidth * dstHeight / srcHeight);
        }
        drawWidth = Math.max(1, Math.min(dstWidth, drawWidth));
        drawHeight = Math.max(1, Math.min(dstHeight, drawHeight));
        int offsetX = (dstWidth - drawWidth) / 2;
        int offsetY = (dstHeight - drawHeight) / 2;
        blitNearest(srcWidth, srcHeight, srcRgb, drawWidth, drawHeight, dst, dstWidth, offsetX, offsetY);
        return dst;
    }

    /**
     * 等比铺满（cover）：放大到盖住目标后居中裁掉溢出，不留边。
     */
    static int[] scaleCover(int srcWidth, int srcHeight, int[] srcRgb, int dstWidth, int dstHeight) {
        requireSize(srcWidth, srcHeight);
        requireSize(dstWidth, dstHeight);
        requireRgb(srcRgb, srcWidth, srcHeight);
        int windowWidth;
        int windowHeight;
        int cropX;
        int cropY;
        // cover 取较大缩放比：源更宽时裁左右，源更高时裁上下。
        boolean cropVertical = (long) dstWidth * srcHeight >= (long) dstHeight * srcWidth;
        if (cropVertical) {
            windowWidth = srcWidth;
            windowHeight = (int) ((long) srcWidth * dstHeight / dstWidth);
            windowHeight = Math.max(1, Math.min(srcHeight, windowHeight));
            cropX = 0;
            cropY = (srcHeight - windowHeight) / 2;
        } else {
            windowHeight = srcHeight;
            windowWidth = (int) ((long) srcHeight * dstWidth / dstHeight);
            windowWidth = Math.max(1, Math.min(srcWidth, windowWidth));
            cropY = 0;
            cropX = (srcWidth - windowWidth) / 2;
        }
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int sy = cropY + (int) ((long) y * windowHeight / dstHeight);
            if (sy >= srcHeight) {
                sy = srcHeight - 1;
            }
            for (int x = 0; x < dstWidth; x++) {
                int sx = cropX + (int) ((long) x * windowWidth / dstWidth);
                if (sx >= srcWidth) {
                    sx = srcWidth - 1;
                }
                dst[y * dstWidth + x] = packRgb(srcRgb[sy * srcWidth + sx]);
            }
        }
        return dst;
    }

    /**
     * 从横向条带裁出窗口。{@code originX} 任意整数，按条带宽度取模；超出右边界时环绕。
     * 纵向不环绕，从条带顶部取 {@code windowHeight} 行。
     */
    static int[] cropWindow(int stripWidth, int stripHeight, int[] pixels,
                            int originX, int windowWidth, int windowHeight) {
        requireSize(stripWidth, stripHeight);
        requireSize(windowWidth, windowHeight);
        if (windowHeight > stripHeight) {
            throw new IllegalArgumentException(
                    "窗口高度超出条带: " + windowHeight + " > " + stripHeight);
        }
        requirePixels(pixels, stripWidth, stripHeight);
        int[] out = new int[windowWidth * windowHeight];
        for (int y = 0; y < windowHeight; y++) {
            int srcRow = y * stripWidth;
            int dstRow = y * windowWidth;
            for (int x = 0; x < windowWidth; x++) {
                int sx = Math.floorMod(originX + x, stripWidth);
                out[dstRow + x] = pixels[srcRow + sx];
            }
        }
        return out;
    }

    private static void blitNearest(int srcWidth, int srcHeight, int[] srcRgb,
                                    int drawWidth, int drawHeight,
                                    int[] dst, int dstWidth, int offsetX, int offsetY) {
        for (int y = 0; y < drawHeight; y++) {
            int sy = (int) ((long) y * srcHeight / drawHeight);
            if (sy >= srcHeight) {
                sy = srcHeight - 1;
            }
            int dstRow = (offsetY + y) * dstWidth;
            for (int x = 0; x < drawWidth; x++) {
                int sx = (int) ((long) x * srcWidth / drawWidth);
                if (sx >= srcWidth) {
                    sx = srcWidth - 1;
                }
                dst[dstRow + offsetX + x] = packRgb(srcRgb[sy * srcWidth + sx]);
            }
        }
    }

    private static void requireSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("尺寸必须为正: " + width + "x" + height);
        }
    }

    private static void requireRgb(int[] rgb, int width, int height) {
        requirePixels(rgb, width, height);
    }

    private static void requirePixels(int[] pixels, int width, int height) {
        if (pixels == null || pixels.length != (long) width * height) {
            throw new IllegalArgumentException("像素数量与尺寸不符");
        }
    }
}

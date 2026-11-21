package space.firok.moli.usb.gui;

/**
 * 行优先 RGB565 画面。像素值应落在 {@code 0..0xFFFF}。调用方持有数组，本类不复制。
 */
record Rgb565Image(int width, int height, int[] pixels) {

    Rgb565Image {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("画面尺寸必须为正: " + width + "x" + height);
        }
        if (pixels == null || pixels.length != (long) width * height) {
            throw new IllegalArgumentException(
                    "像素数量与尺寸不符: " + (pixels == null ? "null" : pixels.length)
                            + " != " + width + "x" + height);
        }
    }
}

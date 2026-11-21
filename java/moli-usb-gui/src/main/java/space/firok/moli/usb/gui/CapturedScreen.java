package space.firok.moli.usb.gui;

/**
 * 一帧主屏捕获。像素是行优先 {@code 0xAARRGGBB}，缩放时只使用 RGB。
 */
record CapturedScreen(int width, int height, int[] rgb) {

    CapturedScreen {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("捕获尺寸必须为正: " + width + "x" + height);
        }
        if (rgb == null || rgb.length != (long) width * height) {
            throw new IllegalArgumentException("捕获像素数量与尺寸不符");
        }
    }
}

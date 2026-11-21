package space.firok.moli.usb.gui;

/**
 * 已在内容线程合成、尚未交给设备的一帧。预览和发送共用这份像素。
 *
 * @param fillColor 纯色模式的 RGB565；其它模式与像素内容一致，发送时不单独使用
 */
record ComposedFrame(ContentMode mode, int width, int height, int[] pixels, int fillColor) {

    ComposedFrame {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("画面尺寸必须为正: " + width + "x" + height);
        }
        if (pixels == null || pixels.length != (long) width * height) {
            throw new IllegalArgumentException("像素数量与尺寸不符");
        }
        fillColor &= 0xFFFF;
    }
}

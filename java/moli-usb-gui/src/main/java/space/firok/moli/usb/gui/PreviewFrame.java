package space.firok.moli.usb.gui;

/**
 * 已展开的预览图。在内容线程建好，再交回 JavaFX 线程贴到窗口上。不含设备句柄。
 */
record PreviewFrame(int width, int height, int[] argb) {

    PreviewFrame {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("预览尺寸必须为正: " + width + "x" + height);
        }
        if (argb == null || argb.length != (long) width * height) {
            throw new IllegalArgumentException("预览像素数量与尺寸不符");
        }
    }
}

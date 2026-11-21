package space.firok.moli.usb.gui;

import java.nio.file.Path;

/**
 * 一次内容发送的设置快照。宽高来自已连接设备的快照，不写死面板尺寸。
 *
 * @param periodMillis 大于 0 时按该间隔重复发送；0 表示只发一帧
 * @param scrollStep   每个滚动节拍前进的像素
 */
record ContentSpec(
        ContentMode mode,
        int width,
        int height,
        int foreground,
        int background,
        String text,
        Path imageFile,
        boolean coverCrop,
        int fontSize,
        int periodMillis,
        int scrollStep) {

    ContentSpec {
        text = text == null ? "" : text;
        fontSize = Math.max(1, fontSize);
        if (periodMillis < 0) {
            throw new IllegalArgumentException("periodMillis 不能为负");
        }
        scrollStep = Math.max(1, scrollStep);
    }

    boolean canSend() {
        if (width <= 0 || height <= 0) {
            return false;
        }
        return switch (mode) {
            case SOLID, CLOCK, STATS, MIRROR -> true;
            case STATIC_TEXT, SCROLL -> !text.isBlank();
            case IMAGE -> imageFile != null;
        };
    }

    /** 上一帧还没发完时，延迟再试，而不是把帧排进队列。 */
    long retryMillis() {
        return periodMillis > 0 ? periodMillis : 40;
    }
}

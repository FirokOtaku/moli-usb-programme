package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发送路径选择。不打开串口，也不启动 JavaFX。
 */
class FrameSenderTest {

    @Test
    @DisplayName("纯色走填充，照片和桌面镜像不压缩，文本、时钟和系统状态走两色")
    void kindFollowsMode() {
        assertEquals(FrameSender.Kind.FILL, FrameSender.kindFor(ContentMode.SOLID));
        assertEquals(FrameSender.Kind.UNCOMPRESSED, FrameSender.kindFor(ContentMode.IMAGE));
        assertEquals(FrameSender.Kind.TWO_COLOR, FrameSender.kindFor(ContentMode.STATIC_TEXT));
        assertEquals(FrameSender.Kind.TWO_COLOR, FrameSender.kindFor(ContentMode.SCROLL));
        assertEquals(FrameSender.Kind.TWO_COLOR, FrameSender.kindFor(ContentMode.CLOCK));
        assertEquals(FrameSender.Kind.TWO_COLOR, FrameSender.kindFor(ContentMode.STATS));
        assertEquals(FrameSender.Kind.UNCOMPRESSED, FrameSender.kindFor(ContentMode.MIRROR));
    }

    @Test
    @DisplayName("两色帧只在最坏情况不超过上界时走 RLE，照片始终未压缩")
    void rleFallsBackWhenWorstCaseExceedsBound() {
        assertTrue(FrameSender.rleFits(17 * 13));
        assertTrue(FrameSender.rleFits(320 * 172));
        assertFalse(FrameSender.rleFits(1920 * 1080));
        assertEquals(FrameSender.StreamPath.RLE, FrameSender.plan(ContentMode.STATIC_TEXT, 17 * 13));
        assertEquals(FrameSender.StreamPath.RLE, FrameSender.plan(ContentMode.CLOCK, 320 * 172));
        assertEquals(FrameSender.StreamPath.UNCOMPRESSED, FrameSender.plan(ContentMode.SCROLL, 1920 * 1080));
        assertEquals(FrameSender.StreamPath.UNCOMPRESSED, FrameSender.plan(ContentMode.IMAGE, 17 * 13));
        assertEquals(FrameSender.StreamPath.UNCOMPRESSED, FrameSender.plan(ContentMode.MIRROR, 17 * 13));
        assertEquals(FrameSender.StreamPath.RLE, FrameSender.plan(ContentMode.STATS, 17 * 13));
        assertEquals(FrameSender.StreamPath.UNCOMPRESSED, FrameSender.plan(ContentMode.STATS, 1920 * 1080));
        assertEquals(FrameSender.StreamPath.FILL, FrameSender.plan(ContentMode.SOLID, 17 * 13));
    }
}

package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 桌面镜像缩放复用已有的 contain / cover，不另写一套取样。不捕获屏幕。
 */
class MirrorFramesTest {

    @Test
    @DisplayName("默认缩放与 FrameOps.contain 相同")
    void defaultsToExistingContainMath() {
        int[] wide = new int[]{
                0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF,
                0x000000, 0xFFFF00, 0xFF00FF, 0x00FFFF
        };
        assertArrayEquals(
                FrameOps.scaleContain(4, 2, wide, 4, 4, 0),
                MirrorFrames.scale(4, 2, wide, 4, 4, false));

        int[] tall = new int[]{
                0xFF0000, 0x00FF00,
                0x0000FF, 0xFFFFFF,
                0xFFFF00, 0xFF00FF,
                0x00FFFF, 0x010101
        };
        int pad = 0;
        assertArrayEquals(
                FrameOps.scaleContain(2, 4, tall, 6, 4, pad),
                MirrorFrames.scale(2, 4, tall, 6, 4, false));
        assertArrayEquals(
                FrameOps.scaleContain(3, 2, new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x010203, 0xABCDE0}, 17, 13, 0),
                MirrorFrames.scale(3, 2, new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x010203, 0xABCDE0}, 17, 13, false));
    }

    @Test
    @DisplayName("铺满开关走已有的 cover，不改变 contain 默认")
    void coverToggleUsesExistingCoverMath() {
        int[] wide = new int[]{
                0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF,
                0x000000, 0xFFFF00, 0xFF00FF, 0x00FFFF
        };
        assertArrayEquals(
                FrameOps.scaleCover(4, 2, wide, 2, 2),
                MirrorFrames.scale(4, 2, wide, 2, 2, true));
    }
}

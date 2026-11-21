package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.domain.Rgb565;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缩放、RGB565 打包和滚动窗口裁剪。不启动 JavaFX，也不打开串口。
 * 面板尺寸用奇数和小尺寸，避免把某种机型分辨率写进算法。
 */
class FrameOpsTest {

    @Test
    @DisplayName("RGB565 打包走 fromRgb888，截断低位")
    void packsRgb565() {
        assertEquals(0xF800, FrameOps.pack(255, 0, 0));
        assertEquals(0x07E0, FrameOps.pack(0, 255, 0));
        assertEquals(0x001F, FrameOps.pack(0, 0, 255));
        assertEquals(0xFFFF, FrameOps.pack(255, 255, 255));
        assertEquals(0x0000, FrameOps.pack(0, 0, 0));
        assertEquals(Rgb565.fromRgb888(10, 20, 30).value(), FrameOps.pack(10, 20, 30));
        assertEquals(Rgb565.fromRgb888(255, 128, 64).value(), FrameOps.packRgb(0xFF8040));
        assertEquals(0xFFFF0000, FrameOps.toArgb(0xF800));
        assertEquals(0xFF00FF00, FrameOps.toArgb(0x07E0));
        assertEquals(0xFF0000FF, FrameOps.toArgb(0x001F));
    }

    @Test
    @DisplayName("contain 对更宽的图上下留边，并量化为 RGB565")
    void containLetterboxesWideImage() {
        int[] src = new int[]{
                0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF,
                0x000000, 0xFFFF00, 0xFF00FF, 0x00FFFF
        };
        int[] dst = FrameOps.scaleContain(4, 2, src, 4, 4, 0x0000);

        assertEquals(16, dst.length);
        assertEquals(0x0000, dst[0]);
        assertEquals(0x0000, dst[3]);
        assertEquals(0xF800, dst[4]);
        assertEquals(0x07E0, dst[5]);
        assertEquals(0x001F, dst[6]);
        assertEquals(0xFFFF, dst[7]);
        assertEquals(0x0000, dst[8]);
        assertEquals(0xFFE0, dst[9]);
        assertEquals(0xF81F, dst[10]);
        assertEquals(0x07FF, dst[11]);
        assertEquals(0x0000, dst[12]);
        assertEquals(0x0000, dst[15]);
    }

    @Test
    @DisplayName("contain 对更高的图左右留边")
    void containLetterboxesTallImage() {
        int[] src = new int[]{
                0xFF0000, 0x00FF00,
                0x0000FF, 0xFFFFFF,
                0xFFFF00, 0xFF00FF,
                0x00FFFF, 0x010101
        };
        int pad = FrameOps.pack(1, 2, 3);
        int[] dst = FrameOps.scaleContain(2, 4, src, 6, 4, pad);

        assertEquals(24, dst.length);
        for (int y = 0; y < 4; y++) {
            assertEquals(pad, dst[y * 6], "左留边 y=" + y);
            assertEquals(pad, dst[y * 6 + 1]);
            assertEquals(FrameOps.packRgb(src[y * 2]), dst[y * 6 + 2]);
            assertEquals(FrameOps.packRgb(src[y * 2 + 1]), dst[y * 6 + 3]);
            assertEquals(pad, dst[y * 6 + 4], "右留边 y=" + y);
            assertEquals(pad, dst[y * 6 + 5]);
        }
    }

    @Test
    @DisplayName("cover 居中裁掉左右溢出，不留边")
    void coverCropsHorizontalOverflow() {
        int[] src = new int[]{
                0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF,
                0x000000, 0xFFFF00, 0xFF00FF, 0x00FFFF
        };
        int[] dst = FrameOps.scaleCover(4, 2, src, 2, 2);

        assertEquals(4, dst.length);
        assertArrayEquals(new int[]{0x07E0, 0x001F, 0xFFE0, 0xF81F}, dst);
        assertFalse(contains(dst, 0xF800), "左侧红色应被裁掉");
        assertFalse(contains(dst, 0xFFFF), "右侧白色应被裁掉");
    }

    @Test
    @DisplayName("cover 居中裁掉上下溢出")
    void coverCropsVerticalOverflow() {
        int[] src = new int[]{
                0xFF0000, 0x00FF00,
                0x0000FF, 0xFFFFFF,
                0xFFFF00, 0xFF00FF,
                0x00FFFF, 0x111111
        };
        int[] dst = FrameOps.scaleCover(2, 4, src, 2, 2);

        assertEquals(4, dst.length);
        assertArrayEquals(new int[]{
                FrameOps.packRgb(0x0000FF), FrameOps.packRgb(0xFFFFFF),
                FrameOps.packRgb(0xFFFF00), FrameOps.packRgb(0xFF00FF)
        }, dst);
    }

    @Test
    @DisplayName("1:1 缩放保持量化后的像素，输出长度等于目标面积")
    void identityScaleUsesDestinationSize() {
        int[] src = new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x010203, 0xABCDE0};
        int[] contained = FrameOps.scaleContain(3, 2, src, 3, 2, 0);
        int[] covered = FrameOps.scaleCover(3, 2, src, 3, 2);
        int[] expected = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            expected[i] = FrameOps.packRgb(src[i]);
        }
        assertArrayEquals(expected, contained);
        assertArrayEquals(expected, covered);
        assertEquals(17 * 13, FrameOps.scaleContain(2, 2, new int[]{1, 2, 3, 4}, 17, 13, 0).length);
    }

    @Test
    @DisplayName("滚动窗口按行裁剪，越过右边界时环绕")
    void cropWindowWrapsHorizontally() {
        int[] strip = new int[]{
                10, 20, 30, 40,
                50, 60, 70, 80
        };
        assertArrayEquals(new int[]{10, 20, 50, 60},
                FrameOps.cropWindow(4, 2, strip, 0, 2, 2));
        assertArrayEquals(new int[]{30, 40, 70, 80},
                FrameOps.cropWindow(4, 2, strip, 2, 2, 2));
        assertArrayEquals(new int[]{40, 10, 80, 50},
                FrameOps.cropWindow(4, 2, strip, 3, 2, 2));
        assertArrayEquals(new int[]{40, 10, 80, 50},
                FrameOps.cropWindow(4, 2, strip, -1, 2, 2));
        assertArrayEquals(new int[]{20, 30, 60, 70},
                FrameOps.cropWindow(4, 2, strip, 5, 2, 2));
    }

    @Test
    @DisplayName("窗口比条带宽时重复取样，且不假定固定面板宽度")
    void cropWindowTilesWhenWindowIsWider() {
        int[] strip = new int[]{1, 2, 3};
        int[] window = FrameOps.cropWindow(3, 1, strip, 1, 5, 1);
        assertArrayEquals(new int[]{2, 3, 1, 2, 3}, window);
        assertEquals(9 * 5, FrameOps.cropWindow(3, 5, new int[15], 0, 9, 5).length);
    }

    private static boolean contains(int[] pixels, int value) {
        for (int pixel : pixels) {
            if (pixel == value) {
                return true;
            }
        }
        return false;
    }
}

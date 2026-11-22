package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.error.InvalidArgumentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * RLE 编解码边界测试。
 *
 * <p>重点覆盖厂商 C 函数的三个缺陷：不足 128 像素的尾部、{@code run_b == 0}、以及
 * 最坏情况膨胀没有容量上界。</p>
 */
class RleCodecTest {

    private static final int RED = 0xF800;
    private static final int BLACK = 0x0000;
    private static final int WHITE = 0xFFFF;

    private static int[] repeat(int color, int count) {
        int[] pixels = new int[count];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }

    private static int pixelsIn(List<Frame> frames) {
        int total = 0;
        for (Frame frame : frames) {
            total += ((frame.subcommand() >>> 4) & 0x0F) + (frame.subcommand() & 0x0F);
        }
        return total;
    }

    @Nested
    @DisplayName("规范向量与基本布局")
    class BasicLayout {

        @Test
        @DisplayName("红×3 黑×2 编成一帧，run_a=3 run_b=2")
        void encodesSpecVector() {
            List<Frame> frames = RleCodec.encode(new int[]{RED, RED, RED, BLACK, BLACK});
            assertEquals(1, frames.size());
            Frame frame = frames.getFirst();
            assertEquals(Commands.RLE, frame.command());
            assertEquals(0x32, frame.subcommand());
            assertEquals(RED, frame.u16AtData0());
            assertEquals(BLACK, frame.u16AtData2());
        }

        @Test
        @DisplayName("128 像素块与 MSN_compaction 逐字节一致")
        void matchesMsnCompactionBlock() {
            int green = 0x07E0;
            int blue = 0x001F;
            int[] pixels = new int[128];
            int index = 0;
            for (int pair = 0; pair < 4; pair++) {
                for (int i = 0; i < 15; i++) {
                    pixels[index++] = RED;
                }
                for (int i = 0; i < 15; i++) {
                    pixels[index++] = green;
                }
            }
            for (int i = 0; i < 4; i++) {
                pixels[index++] = blue;
            }
            for (int i = 0; i < 4; i++) {
                pixels[index++] = WHITE;
            }
            List<Frame> frames = RleCodec.encode(pixels);
            assertEquals(5, frames.size());
            byte[] expected = new byte[] {
                    0x09, (byte) 0xFF, (byte) 0xF8, 0x00, 0x07, (byte) 0xE0,
                    0x09, (byte) 0xFF, (byte) 0xF8, 0x00, 0x07, (byte) 0xE0,
                    0x09, (byte) 0xFF, (byte) 0xF8, 0x00, 0x07, (byte) 0xE0,
                    0x09, (byte) 0xFF, (byte) 0xF8, 0x00, 0x07, (byte) 0xE0,
                    0x09, 0x44, 0x00, 0x1F, (byte) 0xFF, (byte) 0xFF
            };
            byte[] actual = new byte[expected.length];
            for (int i = 0; i < frames.size(); i++) {
                System.arraycopy(frames.get(i).toWire(), 0, actual, i * Frame.LENGTH, Frame.LENGTH);
            }
            assertArrayEquals(expected, actual);
            assertEquals(128, pixelsIn(frames));
        }

        @Test
        @DisplayName("解码往返：任意长度都能还原原始像素")
        void decodeRoundTrip() {
            int[] pixels = new int[300];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = (i / 7) % 2 == 0 ? RED : BLACK;
            }
            List<Frame> frames = RleCodec.encode(pixels);
            assertArrayEquals(pixels, RleCodec.decode(frames, pixels.length, frames.size()));
        }
    }

    @Nested
    @DisplayName("不足 128 像素的尾部")
    class PartialTail {

        @Test
        @DisplayName("1 像素尾部也能编码，且 run_b=0")
        void singlePixelTail() {
            List<Frame> frames = RleCodec.encode(new int[]{WHITE});
            assertEquals(1, frames.size());
            Frame frame = frames.getFirst();
            assertEquals(1, (frame.subcommand() >>> 4) & 0x0F);
            assertEquals(0, frame.subcommand() & 0x0F);
        }

        @Test
        @DisplayName("127 像素：仍在同一个块内，编码后总像素数不变")
        void justUnderOneBlock() {
            int[] pixels = repeat(RED, 127);
            List<Frame> frames = RleCodec.encode(pixels);
            assertEquals(127, pixelsIn(frames));
            // 与 MSN_compaction 相同：15+15 配对，120 像素 4 帧，余 7 像素 1 帧。
            assertEquals(5, frames.size());
        }

        @Test
        @DisplayName("129 像素：跨两个块，第二块只有 1 像素")
        void justOverOneBlock() {
            int[] pixels = repeat(RED, 129);
            List<Frame> frames = RleCodec.encode(pixels);
            assertEquals(129, pixelsIn(frames));
            // 第一块 128 像素 5 帧（4×30 + 8），第二块 1 像素 1 帧。
            assertEquals(6, frames.size());
            Frame tail = frames.getLast();
            assertEquals(1, (tail.subcommand() >>> 4) & 0x0F);
            assertEquals(0, tail.subcommand() & 0x0F);
        }

        @Test
        @DisplayName("256 像素：正好两个完整块，尾部不会越块")
        void exactlyTwoBlocks() {
            List<Frame> frames = RleCodec.encode(repeat(RED, 256));
            assertEquals(256, pixelsIn(frames));
            // 每块 128 像素 5 帧，两块不串在一起。
            assertEquals(10, frames.size());
        }
    }

    @Nested
    @DisplayName("run_b == 0 的语义")
    class ZeroSecondRun {

        @Test
        @DisplayName("全同色块用单帧 (run_a=15, run_b=0)，第二颜色回填为第一颜色")
        void allSameColor() {
            List<Frame> frames = RleCodec.encode(repeat(WHITE, 15));
            assertEquals(1, frames.size());
            Frame frame = frames.getFirst();
            assertEquals(0x0F, frame.subcommand() >>> 4);
            assertEquals(0x00, frame.subcommand() & 0x0F);
            // 缺陷修复：第二颜色不能是未初始化的内存值。
            assertEquals(WHITE, frame.u16AtData2());
        }

        @Test
        @DisplayName("run_b=0 的帧解码后不产生第二颜色的像素")
        void zeroRunContributesNoPixels() {
            int[] decoded = RleCodec.decodeFrame(
                    Frame.of(Commands.RLE, 0x50, 0xF8, 0x00, 0x00, 0x00));
            assertEquals(5, decoded[1]);
            assertEquals(0, decoded[3]);
        }

        @Test
        @DisplayName("每帧总像素数不超过 15+15")
        void runsNeverExceedFifteen() {
            int[] alternating = new int[128];
            for (int i = 0; i < alternating.length; i++) {
                alternating[i] = i % 2 == 0 ? RED : BLACK;
            }
            for (Frame frame : RleCodec.encode(alternating)) {
                int runA = (frame.subcommand() >>> 4) & 0x0F;
                int runB = frame.subcommand() & 0x0F;
                assertTrue(runA <= 15 && runB <= 15);
                assertTrue(runA + runB <= RleCodec.MAX_PIXELS_PER_FRAME);
            }
        }
    }

    @Nested
    @DisplayName("最坏膨胀上界与容量参数")
    class WorstCaseBound {

        @Test
        @DisplayName("逐像素交替是最坏情况：每块 64 帧")
        void alternatingIsWorstCase() {
            int[] alternating = new int[128];
            for (int i = 0; i < alternating.length; i++) {
                alternating[i] = i % 2 == 0 ? RED : BLACK;
            }
            List<Frame> frames = RleCodec.encode(alternating);
            assertEquals(RleCodec.MAX_FRAMES_PER_BLOCK, frames.size());
            assertEquals(128, pixelsIn(frames));
        }

        @Test
        @DisplayName("worstCaseFrameCount 与实际最坏情况帧数一致")
        void worstCaseBoundIsTight() {
            assertEquals(0, RleCodec.worstCaseFrameCount(0));
            assertEquals(64, RleCodec.worstCaseFrameCount(128));
            assertEquals(64, RleCodec.worstCaseFrameCount(127));
            assertEquals(128, RleCodec.worstCaseFrameCount(129));
            assertEquals(64 * 3 * 6, RleCodec.worstCaseByteCount(384));
        }

        @Test
        @DisplayName("最坏情况也绝不超出声称的上界")
        void encodingNeverExceedsDeclaredBound() {
            int[] alternating = new int[300];
            for (int i = 0; i < alternating.length; i++) {
                alternating[i] = i % 2 == 0 ? RED : BLACK;
            }
            int bound = RleCodec.worstCaseFrameCount(alternating.length);
            List<Frame> frames = RleCodec.encode(alternating, bound);
            assertTrue(frames.size() <= bound,
                    "实际 " + frames.size() + " 帧，上界 " + bound);
        }

        @Test
        @DisplayName("给定过小的容量上界时立即失败，而不是继续膨胀")
        void rejectsWhenCapacityTooSmall() {
            int[] alternating = new int[128];
            for (int i = 0; i < alternating.length; i++) {
                alternating[i] = i % 2 == 0 ? RED : BLACK;
            }
            assertThrows(InvalidArgumentError.class, () -> RleCodec.encode(alternating, 8));
        }

        @Test
        @DisplayName("可增长模式不需要调用方预知帧数")
        void growableModeNeedsNoBound() {
            List<Frame> frames = RleCodec.encode(repeat(RED, 1000));
            assertEquals(1000, pixelsIn(frames));
        }
    }

    @Nested
    @DisplayName("参数校验与解码校验")
    class Validation {

        @Test
        @DisplayName("空像素列表被拒绝")
        void rejectsEmpty() {
            assertThrows(InvalidArgumentError.class, () -> RleCodec.encode(new int[0]));
            assertThrows(InvalidArgumentError.class, () -> RleCodec.encode((int[]) null));
        }

        @Test
        @DisplayName("超出 RGB565 范围的像素被拒绝")
        void rejectsOutOfRangePixel() {
            assertThrows(InvalidArgumentError.class, () -> RleCodec.encode(new int[]{0x1_0000}));
            assertThrows(InvalidArgumentError.class, () -> RleCodec.encode(new int[]{-1}));
        }

        @Test
        @DisplayName("解码时像素多出预期即判为畸形，而不是截断")
        void decodeRejectsOverflow() {
            List<Frame> frames = RleCodec.encode(new int[]{RED, RED, RED});
            assertThrows(space.firok.moli.usb.api.error.MalformedResponseError.class,
                    () -> RleCodec.decode(frames, 2, 1));
        }

        @Test
        @DisplayName("解码时像素不足预期同样判为畸形")
        void decodeRejectsUnderflow() {
            List<Frame> frames = RleCodec.encode(new int[]{RED, RED, RED});
            assertThrows(space.firok.moli.usb.api.error.MalformedResponseError.class,
                    () -> RleCodec.decode(frames, 4, 1));
        }

        @Test
        @DisplayName("帧数不符时报畸形")
        void decodeRejectsWrongFrameCount() {
            List<Frame> frames = RleCodec.encode(new int[]{RED, RED});
            assertThrows(space.firok.moli.usb.api.error.MalformedResponseError.class,
                    () -> RleCodec.decode(frames, 2, 2));
        }

        @Test
        @DisplayName("单块解出超过 128 像素时报畸形")
        void decodeRejectsOversizedBlock() {
            List<Frame> frames = new ArrayList<>();
            // 10 帧 × 15 像素 = 150 像素，超过单块 128 的上限。
            for (int i = 0; i < 10; i++) {
                frames.add(Frame.of(Commands.RLE, 0xF0, 0xF8, 0x00, 0x00, 0x00));
            }
            assertThrows(space.firok.moli.usb.api.error.MalformedResponseError.class,
                    () -> RleCodec.decode(frames, 150, frames.size()));
        }
    }
}
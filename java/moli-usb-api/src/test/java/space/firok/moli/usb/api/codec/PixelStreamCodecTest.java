package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import space.firok.moli.usb.api.error.InvalidArgumentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 像素流管线测试：0x07 → 暂存区 0x04 → 提交 0x08 → 结束 0x09。
 */
class PixelStreamCodecTest {

    @Test
    @DisplayName("RGB565 像素展平为高字节在前的线字节")
    void flattensBigEndian() {
        byte[] wire = PixelStreamCodec.toWireBytes(new int[]{0xF800, 0x07E0, 0x001F});
        assertArrayEquals(new byte[]{
                (byte) 0xF8, 0x00,
                0x07, (byte) 0xE0,
                0x00, 0x1F}, wire);
    }

    @Test
    @DisplayName("不足 256 字节时只有一次提交，长度是真实字节数")
    void singleBatchUnderScratch() {
        List<Frame> frames = PixelStreamCodec.encodeStream(new byte[16]);
        // 0x07 + 4 个暂存区帧（16/4）+ 0x08 + 0x09
        assertEquals(7, frames.size());
        assertEquals(DisplayAction.BEGIN_STREAM, frames.getFirst().data0());
        assertEquals(DisplayAction.COMMIT_STAGED, frames.get(5).data0());
        assertEquals(16, (frames.get(5).data1() << 8) | frames.get(5).data2());
        assertEquals(DisplayAction.WAIT_TRANSFER_DONE, frames.getLast().data0());
    }

    @Test
    @DisplayName("超过 256 字节时按 256 字节分批提交")
    void multipleBatchesOverScratch() {
        List<Frame> frames = PixelStreamCodec.encodeStream(new byte[300]);
        long commits = frames.stream()
                .filter(frame -> frame.command() == Commands.DISPLAY
                        && frame.data0() == DisplayAction.COMMIT_STAGED)
                .count();
        assertEquals(2, commits, "300 字节应分成 256 + 44 两批");
        int totalStaged = frames.stream()
                .filter(frame -> frame.command() == Commands.SCRATCH)
                .mapToInt(frame -> ScratchCodec.BYTES_PER_FRAME)
                .sum();
        // 64 帧（256 字节）+ 11 帧（44 字节，末帧补零）
        assertEquals(256 + 44, totalStaged);
        assertEquals(75, frames.stream()
                .filter(frame -> frame.command() == Commands.SCRATCH).count());
    }

    @Test
    @DisplayName("奇数长度线字节被拒绝（RGB565 每像素 2 字节）")
    void rejectsOddByteCount() {
        assertThrows(InvalidArgumentError.class,
                () -> PixelStreamCodec.encodeStream(new byte[3]));
        assertThrows(InvalidArgumentError.class,
                () -> PixelStreamCodec.encodeStream(new byte[0]));
        assertThrows(InvalidArgumentError.class,
                () -> PixelStreamCodec.encodeStream(null));
    }

    @Test
    @DisplayName("超出 RGB565 范围的像素被拒绝")
    void rejectsOutOfRangePixels() {
        assertThrows(InvalidArgumentError.class,
                () -> PixelStreamCodec.toWireBytes(new int[]{0x1_0000}));
        assertThrows(InvalidArgumentError.class,
                () -> PixelStreamCodec.toWireBytes(new int[]{-1}));
        assertThrows(InvalidArgumentError.class,
                () -> PixelStreamCodec.toWireBytes(new int[0]));
    }

    @Test
    @DisplayName("RLE 路径也是 0x07 → RLE 帧 → 0x09，没有 0x08")
    void rleStreamHasNoCommit() {
        List<Frame> frames = PixelStreamCodec.encodeRleStream(new int[]{0xF800, 0xF800}, 4);
        assertEquals(3, frames.size());
        assertEquals(Commands.DISPLAY, frames.getFirst().command());
        assertEquals(DisplayAction.BEGIN_STREAM, frames.getFirst().data0());
        assertEquals(Commands.RLE, frames.get(1).command());
        assertEquals(DisplayAction.WAIT_TRANSFER_DONE, frames.getLast().data0());
    }
}
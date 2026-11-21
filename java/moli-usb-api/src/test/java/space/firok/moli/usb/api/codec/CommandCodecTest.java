package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 显示、SFR、Flash、暂存区、ADC 各命令的帧构造测试（向量之外的分支）。
 */
class CommandCodecTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Nested
    @DisplayName("显示命令 0x02")
    class Display {

        @Test
        @DisplayName("设置方向：动作 0x0A")
        void setOrientation() {
            assertArrayEquals(bytes(0x02, 0x03, 0x0A, 0x01, 0x00, 0x00),
                    DisplayCodec.setOrientation(1).toWire());
        }

        @Test
        @DisplayName("设置背光：动作 0x12，PWM 大端 16 位")
        void setBacklight() {
            assertArrayEquals(bytes(0x02, 0x03, 0x12, 0x03, 0xE8, 0x00),
                    DisplayCodec.setBacklight(1000).toWire());
            assertThrows(InvalidArgumentError.class, () -> DisplayCodec.setBacklight(1001));
            assertThrows(InvalidArgumentError.class, () -> DisplayCodec.setBacklight(-1));
        }

        @Test
        @DisplayName("彩色素材页地址：LOW_HIGH_ZERO 用 16 位页并置零末字节")
        void colorPageLite() {
            assertArrayEquals(bytes(0x02, 0x03, 0x00, 0x12, 0x34, 0x00),
                    DisplayCodec.showFlashColor(0x1234, PageAddressOrder.LOW_HIGH_ZERO).toWire());
            // 16 位布局无法承载 24 位页，必须报错而不是截断。
            assertThrows(InvalidArgumentError.class,
                    () -> DisplayCodec.showFlashColor(0x012345, PageAddressOrder.LOW_HIGH_ZERO));
        }

        @Test
        @DisplayName("彩色素材页地址：MID_LOW_HIGH 用 24 位中、低、高布局")
        void colorPagePro() {
            assertArrayEquals(bytes(0x02, 0x03, 0x00, 0x23, 0x45, 0x01),
                    DisplayCodec.showFlashColor(0x012345, PageAddressOrder.MID_LOW_HIGH).toWire());
        }

        @Test
        @DisplayName("两种页地址布局不是同一套：PRO 能写 24 位页，LITE 只能写 16 位页")
        void pageLayoutsDiffer() {
            // LOW_HIGH_ZERO：16 位页（高、低）+ 0。
            assertArrayEquals(bytes(0x02, 0x03, 0x00, 0x12, 0x34, 0x00),
                    DisplayCodec.showFlashColor(0x1234, PageAddressOrder.LOW_HIGH_ZERO).toWire());
            // MID_LOW_HIGH：24 位的（中、低、高）→ 23 45 01。
            assertArrayEquals(bytes(0x02, 0x03, 0x00, 0x23, 0x45, 0x01),
                    DisplayCodec.showFlashColor(0x012345, PageAddressOrder.MID_LOW_HIGH).toWire());
            // 16 位布局无法承载 24 位页：必须报错而不是截断高位。
            assertThrows(InvalidArgumentError.class,
                    () -> DisplayCodec.showFlashColor(0x012345, PageAddressOrder.LOW_HIGH_ZERO));
        }

        @Test
        @DisplayName("单色素材：动作 0x01，page_high/page_low/00")
        void monoPage() {
            assertArrayEquals(bytes(0x02, 0x03, 0x01, 0x12, 0x34, 0x00),
                    DisplayCodec.showFlashMono(0x1234).toWire());
        }

        @Test
        @DisplayName("流式写入：0x07 开始，0x08 提交长度，0x09 结束")
        void streamActions() {
            assertArrayEquals(bytes(0x02, 0x03, 0x07, 0x00, 0x00, 0x00),
                    DisplayCodec.beginStream().toWire());
            assertArrayEquals(bytes(0x02, 0x03, 0x08, 0x01, 0x00, 0x00),
                    DisplayCodec.commitStaged(256).toWire());
            assertArrayEquals(bytes(0x02, 0x03, 0x09, 0x00, 0x00, 0x00),
                    DisplayCodec.waitTransferDone().toWire());
        }

        @Test
        @DisplayName("RGB 灯：0x05 暂存单灯，0x0C 提交")
        void leds() {
            assertArrayEquals(bytes(0x02, 0x05, 0x00, 0xFF, 0x80, 0x10),
                    DisplayCodec.setLed(0, 0xFF, 0x80, 0x10).toWire());
            assertArrayEquals(bytes(0x02, 0x03, 0x0C, 0x00, 0x00, 0x00),
                    DisplayCodec.applyLeds().toWire());
        }

        @Test
        @DisplayName("十六进制颜色按高字节在前")
        void colorsAreBigEndian() {
            assertArrayEquals(bytes(0x02, 0x02, 0x07, 0xE0, 0xF8, 0x00),
                    DisplayCodec.setColors(Rgb565.GREEN.value(), Rgb565.RED.value()).toWire());
        }
    }

    @Nested
    @DisplayName("SFR 命令 0x00")
    class Sfr {

        @Test
        @DisplayName("读 u16：00 30 20 addr 00 00")
        void readU16() {
            assertArrayEquals(bytes(0x00, 0x30, 0x20, 0x7F, 0x00, 0x00),
                    SfrCodec.readU16(0x7F).toWire());
            assertThrows(InvalidArgumentError.class, () -> SfrCodec.readU16(0x100));
        }

        @Test
        @DisplayName("写 u16 使用候选操作码 0xA0，而不是与读冲突的 0x20")
        void writeU16UsesCandidateOpcode() {
            assertArrayEquals(bytes(0x00, 0x30, 0xA0, 0x7F, 0x12, 0x34),
                    SfrCodec.writeU16(0x7F, 0x1234).toWire());
            assertEquals(0xA0, SfrCodec.writeU16(0x00, 0).toWire()[2] & 0xFF);
            assertTrue((SfrCodec.writeU16(0x00, 0).toWire()[2] & 0xFF) != SfrOp.READ_U16);
        }

        @Test
        @DisplayName("地址与数值范围校验")
        void validatesRanges() {
            assertThrows(InvalidArgumentError.class, () -> SfrCodec.readU8(0x1_0000));
            assertThrows(InvalidArgumentError.class, () -> SfrCodec.writeU8(0x0123, 0x100));
            assertThrows(InvalidArgumentError.class, () -> SfrCodec.writeU16(0x10, 0x1_0000));
        }
    }

    @Nested
    @DisplayName("Flash 命令 0x03")
    class Flash {

        @Test
        @DisplayName("暂存区写页：03 01 page_u24 page_count")
        void writePagesFromScratch() {
            assertArrayEquals(bytes(0x03, 0x01, 0x01, 0x02, 0x03, 0x04),
                    FlashCodec.writePagesFromScratch(0x010203, 4).toWire());
        }

        @Test
        @DisplayName("擦除页：03 02 start_u16 count_u16")
        void erasePages() {
            assertArrayEquals(bytes(0x03, 0x02, 0x00, 0x10, 0x00, 0x20),
                    FlashCodec.erasePages(0x0010, 0x0020).toWire());
            assertThrows(InvalidArgumentError.class,
                    () -> FlashCodec.erasePages(0, 0));
        }

        @Test
        @DisplayName("快速写：03 03 page_u24 page_count")
        void writePagesFast() {
            assertArrayEquals(bytes(0x03, 0x03, 0x01, 0x02, 0x03, 0x08),
                    FlashCodec.writePagesFast(0x010203, 8).toWire());
        }

        @Test
        @DisplayName("03 04 串行 Flash 布局：start_page_u16 00 00")
        void highSpeedStartSerialFlash() {
            assertArrayEquals(bytes(0x03, 0x04, 0x12, 0x34, 0x00, 0x00),
                    FlashCodec.highSpeedWriteStart(FlashMedium.SERIAL_FLASH, 0x1234).toWire());
        }

        @Test
        @DisplayName("03 04 NAND 布局：00 block_u16 00 —— 与 LITE 参数位置不同")
        void highSpeedStartNand() {
            assertArrayEquals(bytes(0x03, 0x04, 0x00, 0x12, 0x34, 0x00),
                    FlashCodec.highSpeedWriteStart(FlashMedium.NAND, 0x1234).toWire());
        }

        @Test
        @DisplayName("介质必须显式给出：不允许推测 0x03/0x04 的布局")
        void mediumIsMandatory() {
            assertThrows(InvalidArgumentError.class,
                    () -> FlashCodec.highSpeedWriteStart(null, 0));
            byte[] flash = FlashCodec.highSpeedWriteStart(FlashMedium.SERIAL_FLASH, 0x1234).toWire();
            byte[] nand = FlashCodec.highSpeedWriteStart(FlashMedium.NAND, 0x1234).toWire();
            assertTrue(!java.util.Arrays.equals(flash, nand));
        }

        @Test
        @DisplayName("芯片擦除与 NAND 块擦除")
        void eraseVariants() {
            assertArrayEquals(bytes(0x03, 0x05, 0x00, 0x00, 0x00, 0x00),
                    FlashCodec.chipErase().toWire());
            assertArrayEquals(bytes(0x03, 0x06, 0x03, 0xFF, 0x00, 0x00),
                    FlashCodec.eraseNandBlock(1023).toWire());
            assertThrows(InvalidArgumentError.class, () -> FlashCodec.eraseNandBlock(0x1_0000));
        }

        @Test
        @DisplayName("读字节地址是 24 位大端")
        void readByteAddressIsU24() {
            assertArrayEquals(bytes(0x03, 0x00, 0xFF, 0xFF, 0xFF, 0x00),
                    FlashCodec.readByte(0xFFFFFF).toWire());
            assertThrows(InvalidArgumentError.class, () -> FlashCodec.readByte(0x1_000000));
        }
    }

    @Nested
    @DisplayName("暂存区 0x04")
    class Scratch {

        @Test
        @DisplayName("256 字节数据恰好拆成 64 帧，偏移 0..63")
        void fullScratchSplitsInto64Frames() {
            byte[] data = new byte[ScratchCodec.SCRATCH_SIZE];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            var frames = ScratchCodec.writeChunk(data);
            assertEquals(64, frames.size());
            assertEquals(0, frames.getFirst().subcommand());
            assertEquals(63, frames.getLast().subcommand());
            assertArrayEquals(bytes(0x04, 0x00, 0x00, 0x01, 0x02, 0x03),
                    frames.getFirst().toWire());
        }

        @Test
        @DisplayName("末帧不足 4 字节补零，提交长度由 0x08 携带")
        void partialTailPadsWithZero() {
            var frames = ScratchCodec.writeChunk(bytes(0xAA, 0xBB, 0xCC));
            assertEquals(1, frames.size());
            assertArrayEquals(bytes(0x04, 0x00, 0xAA, 0xBB, 0xCC, 0x00),
                    frames.getFirst().toWire());
        }

        @Test
        @DisplayName("超过 256 字节或偏移越界被拒绝")
        void rejectsOversized() {
            assertThrows(InvalidArgumentError.class,
                    () -> ScratchCodec.writeChunk(new byte[257]));
            assertThrows(InvalidArgumentError.class,
                    () -> ScratchCodec.writeChunk(new byte[0]));
            assertThrows(InvalidArgumentError.class,
                    () -> ScratchCodec.write(64, bytes(0, 0, 0, 0)));
            assertThrows(InvalidArgumentError.class,
                    () -> ScratchCodec.write(0, bytes(0, 0, 0)));
        }
    }

    @Nested
    @DisplayName("ADC 0x08")
    class Adc {

        @Test
        @DisplayName("通道号放在 byte 1，后续字节全零")
        void channelInByte1() {
            assertArrayEquals(bytes(0x08, 0x0A, 0x00, 0x00, 0x00, 0x00),
                    AdcCodec.readChannel(10).toWire());
            assertThrows(InvalidArgumentError.class, () -> AdcCodec.readChannel(256));
        }

        @Test
        @DisplayName("采样值取响应 byte 4..5，大端")
        void decodesSample() {
            Frame response = Frame.of(0x08, 0x09, 0x00, 0x00, 0x01, 0xF4);
            assertEquals(500, AdcCodec.decodeSample(response));
        }

        @Test
        @DisplayName("命令不对应时报畸形，不把任意数据当采样")
        void rejectsForeignFrame() {
            assertThrows(space.firok.moli.usb.api.error.MalformedResponseError.class,
                    () -> AdcCodec.decodeSample(Frame.of(0x02, 0x09, 0, 0, 0, 0)));
            assertThrows(InvalidArgumentError.class, () -> AdcCodec.decodeSample(null));
        }
    }
}
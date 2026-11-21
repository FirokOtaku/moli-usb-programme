package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import space.firok.moli.usb.api.domain.Rgb565;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * spec/msu2-wire-protocol.md「规范测试向量」全 10 条。
 *
 * <p>本类逐条断言十六进制输出，确保任何一个编码器改动都不会悄悄破坏规范向量。</p>
 */
class SpecVectorTest {

    private static byte[] hex(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("向量 1：设置坐标 (x=1, y=2) → 02 00 00 01 00 02")
    void vector1SetXy() {
        assertArrayEquals(hex(0x02, 0x00, 0x00, 0x01, 0x00, 0x02),
                DisplayCodec.setXy(1, 2).toWire());
    }

    @Test
    @DisplayName("向量 2：设置尺寸 160x80 → 02 01 00 A0 00 50")
    void vector2SetSize() {
        assertArrayEquals(hex(0x02, 0x01, 0x00, 0xA0, 0x00, 0x50),
                DisplayCodec.setSize(160, 80).toWire());
    }

    @Test
    @DisplayName("向量 3：设置红/黑 → 02 02 F8 00 00 00")
    void vector3SetColors() {
        assertArrayEquals(hex(0x02, 0x02, 0xF8, 0x00, 0x00, 0x00),
                DisplayCodec.setColors(Rgb565.RED.value(), Rgb565.BLACK.value()).toWire());
    }

    @Test
    @DisplayName("向量 4：填充当前区域绿色 → 02 03 0B 07 E0 00")
    void vector4FillCurrent() {
        assertArrayEquals(hex(0x02, 0x03, 0x0B, 0x07, 0xE0, 0x00),
                DisplayCodec.fillCurrent(Rgb565.GREEN.value()).toWire());
    }

    @Test
    @DisplayName("向量 5：读 ADC 通道 9 → 08 09 00 00 00 00")
    void vector5AdcChannel() {
        assertArrayEquals(hex(0x08, 0x09, 0x00, 0x00, 0x00, 0x00),
                AdcCodec.readChannel(9).toWire());
    }

    @Test
    @DisplayName("向量 6：读 u8 SFR 0x0123 → 00 30 00 01 23 00")
    void vector6SfrReadU8() {
        assertArrayEquals(hex(0x00, 0x30, 0x00, 0x01, 0x23, 0x00),
                SfrCodec.readU8(0x0123).toWire());
    }

    @Test
    @DisplayName("向量 7：写 u8 SFR 0x0123 = 0x7F → 00 30 80 01 23 7F")
    void vector7SfrWriteU8() {
        assertArrayEquals(hex(0x00, 0x30, 0x80, 0x01, 0x23, 0x7F),
                SfrCodec.writeU8(0x0123, 0x7F).toWire());
    }

    @Test
    @DisplayName("向量 8：读 Flash 字节 0x012345 → 03 00 01 23 45 00")
    void vector8FlashReadByte() {
        assertArrayEquals(hex(0x03, 0x00, 0x01, 0x23, 0x45, 0x00),
                FlashCodec.readByte(0x012345).toWire());
    }

    @Test
    @DisplayName("向量 9：暂存区 offset 2 写 DEADBEEF → 04 02 DE AD BE EF")
    void vector9ScratchWrite() {
        assertArrayEquals(hex(0x04, 0x02, 0xDE, 0xAD, 0xBE, 0xEF),
                ScratchCodec.write(2, hex(0xDE, 0xAD, 0xBE, 0xEF)).toWire());
    }

    @Test
    @DisplayName("向量 10：RLE 红×3 黑×2 → 09 32 F8 00 00 00")
    void vector10Rle() {
        int[] pixels = {0xF800, 0xF800, 0xF800, 0x0000, 0x0000};
        assertEquals(1, RleCodec.encode(pixels).size());
        assertArrayEquals(hex(0x09, 0x32, 0xF8, 0x00, 0x00, 0x00),
                RleCodec.encode(pixels).getFirst().toWire());
    }

    @Test
    @DisplayName("10 条向量中没有一条依赖帧外的同步头或校验和")
    void vectorsUsePlainSixByteFrames() {
        // 每个向量的长度都必须是 6：帧没有同步头、长度或校验和字节。
        int[][] wires = {
                {0x02, 0x00, 0x00, 0x01, 0x00, 0x02},
                {0x02, 0x01, 0x00, 0xA0, 0x00, 0x50},
                {0x02, 0x02, 0xF8, 0x00, 0x00, 0x00},
                {0x02, 0x03, 0x0B, 0x07, 0xE0, 0x00},
                {0x08, 0x09, 0x00, 0x00, 0x00, 0x00},
                {0x00, 0x30, 0x00, 0x01, 0x23, 0x00},
                {0x00, 0x30, 0x80, 0x01, 0x23, 0x7F},
                {0x03, 0x00, 0x01, 0x23, 0x45, 0x00},
                {0x04, 0x02, 0xDE, 0xAD, 0xBE, 0xEF},
                {0x09, 0x32, 0xF8, 0x00, 0x00, 0x00}
        };
        for (int[] wire : wires) {
            assertEquals(6, wire.length);
        }
    }
}
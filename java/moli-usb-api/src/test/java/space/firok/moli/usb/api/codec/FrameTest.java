package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import space.firok.moli.usb.api.error.InvalidArgumentError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * 6 字节帧的构造、校验与取值测试。
 */
class FrameTest {

    @Test
    @DisplayName("帧固定 6 字节，字段顺序为 command/subcommand/data0..3")
    void layoutIsSixBytes() {
        Frame frame = Frame.of(0x02, 0x03, 0x0B, 0x07, 0xE0, 0x00);
        assertEquals(6, Frame.LENGTH);
        assertEquals(6, frame.toWire().length);
        assertArrayEquals(new byte[]{0x02, 0x03, 0x0B, 0x07, (byte) 0xE0, 0x00},
                frame.toWire());
    }

    @Test
    @DisplayName("帧没有同步头、长度或校验和：首字节就是 command")
    void noHeaderOrChecksum() {
        Frame frame = Frame.of(0x00, 0x30, 0x00, 0x01, 0x23, 0x00);
        assertEquals(0x00, frame.toWire()[0]);
        assertEquals(0x00, frame.command());
    }

    @Test
    @DisplayName("多字节整数高字节在前")
    void multiByteFieldsAreBigEndian() {
        Frame frame = Frame.of(0x02, 0x01, 0x00, 0xA0, 0x00, 0x50);
        assertEquals(160, frame.u16AtData0());
        assertEquals(80, frame.u16AtData2());
        assertEquals(0x07E0,
                Frame.of(0x02, 0x02, 0x07, 0xE0, 0xF8, 0x00).u16AtData0());
    }

    @Test
    @DisplayName("u24 组合按高、中、低顺序（占用 data0..data2）")
    void u24IsBigEndian() {
        Frame frame = Frame.of(0x03, 0x00, 0x01, 0x23, 0x45, 0x00);
        assertEquals(0x012345, frame.u24AtData0());
    }

    @Test
    @DisplayName("任何字段超出 0..255 都被拒绝")
    void rejectsOutOfRangeByte() {
        assertThrows(InvalidArgumentError.class, () -> Frame.of(0x100, 0, 0, 0, 0, 0));
        assertThrows(InvalidArgumentError.class, () -> Frame.of(0, -1, 0, 0, 0, 0));
        assertThrows(InvalidArgumentError.class, () -> Frame.of(0, 0, 0, 0, 0, 256));
    }

    @Test
    @DisplayName("fromWire 只接受 6 字节")
    void fromWireRequiresSixBytes() {
        assertThrows(InvalidArgumentError.class, () -> Frame.fromWire(new byte[5]));
        assertThrows(InvalidArgumentError.class, () -> Frame.fromWire(new byte[7]));
        assertThrows(InvalidArgumentError.class, () -> Frame.fromWire(null));
        Frame frame = Frame.fromWire(new byte[]{0x09, 0x32, (byte) 0xF8, 0x00, 0x00, 0x00});
        assertEquals(0x09, frame.command());
        assertEquals(0x32, frame.subcommand());
    }

    @Test
    @DisplayName("toWire 每次返回独立副本")
    void toWireReturnsCopy() {
        Frame frame = Frame.of(0x01, 0x02, 0x03, 0x04, 0x05, 0x06);
        byte[] first = frame.toWire();
        first[0] = (byte) 0xFF;
        assertEquals(0x01, frame.toWire()[0]);
    }

    @Test
    @DisplayName("值语义：内容相同即相等，可安全用作集合键")
    void valueSemantics() {
        Frame a = Frame.of(0x02, 0x00, 0x00, 0x01, 0x00, 0x02);
        Frame b = Frame.fromWire(new byte[]{0x02, 0x00, 0x00, 0x01, 0x00, 0x02});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, Frame.of(0x02, 0x00, 0x00, 0x01, 0x00, 0x03));
    }

    @Test
    @DisplayName("byteAt 越界被拒绝，hex 输出大写单空格分隔")
    void byteAtAndHex() {
        Frame frame = Frame.of(0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01);
        assertEquals(0xBE, frame.byteAt(2));
        assertThrows(InvalidArgumentError.class, () -> frame.byteAt(6));
        assertThrows(InvalidArgumentError.class, () -> frame.byteAt(-1));
        assertEquals("DE AD BE EF 00 01", frame.hex());
    }

    @Test
    @DisplayName("ofU16Pair 展开两个大端 u16")
    void ofU16Pair() {
        Frame frame = Frame.ofU16Pair(0x03, 0x02, 0x0004, 0x0010);
        assertArrayEquals(new byte[]{0x03, 0x02, 0x00, 0x04, 0x00, 0x10}, frame.toWire());
        assertThrows(InvalidArgumentError.class,
                () -> Frame.ofU16Pair(0x03, 0x02, 0x1_0000, 0));
    }

    @Test
    @DisplayName("Hex.of 对 null 与范围越界有明确行为")
    void hexHelper() {
        assertEquals("(null)", Hex.of(null));
        assertEquals("01 02", Hex.of(new byte[]{1, 2}));
        assertEquals("02", Hex.of(new byte[]{1, 2, 3}, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Hex.of(new byte[]{1, 2}, 1, 2));
        assertEquals("DE AD BE EF 00 01",
                Hex.of(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0, 1}));
    }
}
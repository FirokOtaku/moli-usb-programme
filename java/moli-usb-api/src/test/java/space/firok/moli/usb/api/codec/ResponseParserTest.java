package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 响应解析测试：成功 / 畸形 / 失步三分类。
 *
 * <p>规范要求「不能把任意非空数据当成成功」，因此这里逐条验证每种非正常长度与内容的
 * 分类结果。</p>
 */
class ResponseParserTest {

    private static final Frame READ_U8 = SfrCodec.readU8(0x0123);

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("完整且回显匹配的响应判为 OK")
    void matchingEchoIsOk() {
        ResponseCheck check = ResponseParser.check(READ_U8,
                bytes(0x00, 0x30, 0x00, 0x01, 0x23, 0x7F));
        assertTrue(check.isOk());
        assertEquals(ResponseOutcome.OK, check.outcome());
        assertEquals(0x7F, check.u8AtByte5());
    }

    @Test
    @DisplayName("空响应是畸形，而不是「无消息即成功」")
    void emptyIsMalformed() {
        ResponseCheck check = ResponseParser.check(READ_U8, new byte[0]);
        assertFalse(check.isOk());
        assertEquals(ResponseOutcome.MALFORMED, check.outcome());
    }

    @Test
    @DisplayName("null 响应是畸形")
    void nullIsMalformed() {
        assertEquals(ResponseOutcome.MALFORMED,
                ResponseParser.check(READ_U8, null).outcome());
    }

    @Test
    @DisplayName("不足 6 字节是畸形（多字节响应未收齐）")
    void shortIsMalformed() {
        for (int length = 1; length < 6; length++) {
            byte[] partial = new byte[length];
            assertEquals(ResponseOutcome.MALFORMED,
                    ResponseParser.check(READ_U8, partial).outcome(),
                    "长度 " + length + " 应判为畸形");
        }
    }

    @Test
    @DisplayName("多于 6 字节是失步：说明存在残留或主动推送")
    void longerThanFrameIsDesynchronized() {
        ResponseCheck check = ResponseParser.check(READ_U8,
                bytes(0x00, 0x30, 0x00, 0x01, 0x23, 0x7F, 0x00));
        assertEquals(ResponseOutcome.DESYNCHRONIZED, check.outcome());
    }

    @Test
    @DisplayName("命令不对应是失步，不是「任意非空数据即成功」")
    void wrongCommandIsDesynchronized() {
        ResponseCheck check = ResponseParser.check(READ_U8,
                bytes(0x02, 0x03, 0x0B, 0x07, 0xE0, 0x00));
        assertEquals(ResponseOutcome.DESYNCHRONIZED, check.outcome());
    }

    @Test
    @DisplayName("子命令不对应同样判失步")
    void wrongSubcommandIsDesynchronized() {
        ResponseCheck check = ResponseParser.check(READ_U8,
                bytes(0x00, 0x31, 0x00, 0x01, 0x23, 0x00));
        assertEquals(ResponseOutcome.DESYNCHRONIZED, check.outcome());
    }

    @Test
    @DisplayName("命令阶段收到握手横幅视为失步，设备已回到握手状态")
    void bannerDuringCommandPhaseIsDesynchronized() {
        ResponseCheck check = ResponseParser.check(READ_U8,
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31));
        assertEquals(ResponseOutcome.DESYNCHRONIZED, check.outcome());
        assertTrue(check.detail().contains("握手"));
    }

    @Test
    @DisplayName("宽松校验只要求完整帧，但仍拒绝残片")
    void lenientStillRequiresSixBytes() {
        assertTrue(ResponseParser.checkLenient(READ_U8,
                bytes(0x99, 0x98, 0x00, 0x01, 0x23, 0x7F)).isOk());
        assertEquals(ResponseOutcome.MALFORMED,
                ResponseParser.checkLenient(READ_U8, new byte[3]).outcome());
    }

    @Test
    @DisplayName("写操作默认不接受空响应，除非显式声明允许")
    void writeCheckHonoursAllowEmpty() {
        assertEquals(ResponseOutcome.MALFORMED,
                ResponseParser.checkWrite(READ_U8, new byte[0], false).outcome());
        assertTrue(ResponseParser.checkWrite(READ_U8, new byte[0], true).isOk());
        assertTrue(ResponseParser.checkWrite(READ_U8,
                bytes(0x00, 0x30, 0x80, 0x01, 0x23, 0x00), false).isOk());
    }

    @Test
    @DisplayName("u16 取值辅助方法按 byte 4..5 / byte 2..3 组合")
    void u16Helpers() {
        ResponseCheck check = ResponseParser.check(READ_U8,
                bytes(0x00, 0x30, 0x12, 0x34, 0x56, 0x78));
        assertEquals(0x5678, check.u16AtBytes4And5());
        assertEquals(0x1234, check.u16AtBytes2And3());
    }

    @Test
    @DisplayName("非 OK 结果读取数值会失败，避免把失败响应当数据")
    void readingValuesFromFailedCheckThrows() {
        ResponseCheck check = ResponseParser.check(READ_U8, new byte[0]);
        assertThrows(IllegalStateException.class, check::u8AtByte5);
        assertThrows(IllegalStateException.class, check::u16AtBytes4And5);
        assertThrows(IllegalStateException.class, check::responseFrame);
    }

    @Test
    @DisplayName("ResponseCheck 保留请求与原始字节副本")
    void keepsContext() {
        byte[] raw = bytes(0x00, 0x30, 0x00, 0x01, 0x23, 0x7F);
        ResponseCheck check = ResponseParser.check(READ_U8, raw);
        raw[5] = 0;
        assertEquals(0x7F, check.raw()[5]);
        assertEquals(READ_U8, check.request());
        assertEquals("00 30 00 01 23 7F", check.responseFrame().hex());
    }
}
package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.error.MalformedResponseError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * SFR 自描述表解析测试。
 *
 * <p>表项格式（spec/msu2-wire-protocol.md「SFR」）：名称 NUL、单位 NUL、family NUL、
 * family 决定长度的数据。family 2/4 的长度语义仍有缺陷，因此解析失败一律报畸形，
 * 不做猜测性截断。</p>
 */
class SfrTableParserTest {

    private static final int TABLE_LENGTH = SfrTableParser.TABLE_LENGTH;

    /** 构造一张表：把若干条目按规范格式写进去，其余填零。 */
    private static byte[] table(Object... items) {
        byte[] table = new byte[TABLE_LENGTH];
        int offset = 0;
        for (Object item : items) {
            Entry entry = (Entry) item;
            offset = putString(table, offset, entry.name());
            offset = putString(table, offset, entry.unit());
            table[offset++] = (byte) entry.family();
            table[offset++] = 0;
            for (byte b : entry.data()) {
                table[offset++] = b;
            }
        }
        return table;
    }

    private static int putString(byte[] table, int offset, String text) {
        for (char c : text.toCharArray()) {
            table[offset++] = (byte) c;
        }
        table[offset++] = 0;
        return offset;
    }

    private record Entry(String name, String unit, int family, byte[] data) {
    }

    @Test
    @DisplayName("解析 u8 SFR 条目：family 0，数据是 16 位地址")
    void parsesU8Sfr() {
        List<SfrTableParser.Entry> entries = SfrTableParser.parse(
                table(new Entry("Lcd_X", "px", 0x01, new byte[]{0x01, 0x10})));
        assertEquals(1, entries.size());
        SfrTableParser.Entry entry = entries.getFirst();
        assertEquals("Lcd_X", entry.name());
        assertEquals("px", entry.unit());
        assertEquals(0, entry.kind());
        assertEquals(1, entry.count());
        assertEquals(0x0110, entry.addressU16());
    }

    @Test
    @DisplayName("解析 u16 SFR 条目：family 1，数据是 8 位地址")
    void parsesU16Sfr() {
        List<SfrTableParser.Entry> entries = SfrTableParser.parse(
                table(new Entry("Lcd_Y", "px", 0x21, new byte[]{0x22})));
        assertEquals(1, entries.size());
        assertEquals(1, entries.getFirst().kind());
        assertEquals(1, entries.getFirst().count());
        assertEquals(0x22, entries.getFirst().addressU8());
    }

    @Test
    @DisplayName("解析字节字符串条目：family 3，低 5 位是长度")
    void parsesByteString() {
        byte[] uid = "MSN12345678".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        List<SfrTableParser.Entry> entries = SfrTableParser.parse(
                table(new Entry("MSN_UID", "", 0x60 | uid.length, uid)));
        assertEquals(1, entries.size());
        assertEquals(3, entries.getFirst().kind());
        assertEquals(uid.length, entries.getFirst().count());
        assertEquals("MSN12345678", new String(entries.getFirst().data(),
                java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("多个条目按顺序解析，table 全零即结束")
    void parsesMultipleEntries() {
        List<SfrTableParser.Entry> entries = SfrTableParser.parse(table(
                new Entry("A", "u", 0x01, new byte[]{0x00, 0x01}),
                new Entry("B", "u", 0x21, new byte[]{0x02}),
                new Entry("C", "", 0x60 | 3, new byte[]{1, 2, 3})));
        assertEquals(3, entries.size());
        assertEquals(List.of("A", "B", "C"), entries.stream()
                .map(SfrTableParser.Entry::name).toList());
    }

    @Test
    @DisplayName("全零表解析为空列表（设备未提供自描述）")
    void zeroTableHasNoEntries() {
        assertTrue(SfrTableParser.parse(new byte[TABLE_LENGTH]).isEmpty());
    }

    @Test
    @DisplayName("表长度不足时报畸形")
    void shortTableIsMalformed() {
        assertThrows(MalformedResponseError.class,
                () -> SfrTableParser.parse(new byte[TABLE_LENGTH - 1]));
        assertThrows(MalformedResponseError.class, () -> SfrTableParser.parse(null));
    }

    @Test
    @DisplayName("字符串缺少结尾 NUL 时报畸形")
    void missingNulIsMalformed() {
        byte[] table = new byte[TABLE_LENGTH];
        java.util.Arrays.fill(table, (byte) 'A');
        assertThrows(MalformedResponseError.class, () -> SfrTableParser.parse(table));
    }

    @Test
    @DisplayName("数据长度越过表尾时报畸形，不做截断")
    void truncatedDataIsMalformed() {
        byte[] table = new byte[TABLE_LENGTH];
        // 用长名称把条目推到表尾，使其声明的 31 字节数据必然越界。
        int offset = putString(table, 0, "X".repeat(240));
        offset = putString(table, offset, "u");
        table[offset++] = (byte) (0x60 | 31);
        table[offset] = 0;
        assertThrows(MalformedResponseError.class, () -> SfrTableParser.parse(table));
    }

    @Test
    @DisplayName("名称含非 ASCII 字节时报畸形，而不是猜测编码")
    void nonAsciiNameIsMalformed() {
        byte[] table = new byte[TABLE_LENGTH];
        table[0] = (byte) 0xC3;
        table[1] = (byte) 0xA9;
        table[2] = 0;
        table[3] = 0;
        table[4] = 0;
        assertThrows(MalformedResponseError.class, () -> SfrTableParser.parse(table));
    }

    @Test
    @DisplayName("未定义的 family 种类（5..7）报畸形")
    void unknownKindIsMalformed() {
        byte[] table = new byte[TABLE_LENGTH];
        int offset = putString(table, 0, "X");
        offset = putString(table, offset, "u");
        table[offset++] = (byte) 0xA0; // 种类 5
        table[offset] = 0;
        assertThrows(MalformedResponseError.class, () -> SfrTableParser.parse(table));
    }

    @Test
    @DisplayName("family 后的 NUL 不是地址；Lcd_X 地址 0 不会截断 Lcd_Y")
    void familyNulIsNotTheAddress() {
        byte[] table = new byte[TABLE_LENGTH];
        int offset = 0;
        offset = putFirmwareEntry(table, offset, "Lcd_X", 0x21, new byte[]{0x00});
        offset = putFirmwareEntry(table, offset, "Lcd_Y", 0x21, new byte[]{0x01});
        putFirmwareEntry(table, offset, "Flash_Info", 0x21, new byte[]{0x02});

        List<SfrTableParser.Entry> entries = SfrTableParser.parse(table);
        assertEquals(List.of("Lcd_X", "Lcd_Y", "Flash_Info"), entries.stream()
                .map(SfrTableParser.Entry::name).toList());
        assertEquals(0x00, entries.get(0).addressU8());
        assertEquals(0x01, entries.get(1).addressU8());
        assertEquals(1, entries.get(0).kind());
        assertEquals(1, entries.get(1).kind());
    }

    @Test
    @DisplayName("family 缺少结尾 NUL 时报畸形，不把结束符当成地址")
    void missingFamilyNulIsMalformed() {
        byte[] table = new byte[TABLE_LENGTH];
        int offset = putString(table, 0, "Lcd_X");
        offset = putString(table, offset, " ");
        table[offset++] = 0x21;
        table[offset] = 0x05;
        assertThrows(MalformedResponseError.class, () -> SfrTableParser.parse(table));
    }

    private static int putFirmwareEntry(byte[] table, int offset, String name, int family, byte[] data) {
        offset = putString(table, offset, name);
        offset = putString(table, offset, " ");
        table[offset++] = (byte) family;
        table[offset++] = 0;
        System.arraycopy(data, 0, table, offset, data.length);
        return offset + data.length;
    }

    @Test
    @DisplayName("返回的条目是不可变列表，数据是副本")
    void entriesAreImmutableCopies() {
        List<SfrTableParser.Entry> entries = SfrTableParser.parse(
                table(new Entry("A", "u", 0, new byte[]{0x00, 0x01})));
        SfrTableParser.Entry entry = entries.getFirst();
        byte[] copy = entry.dataCopy();
        copy[0] = (byte) 0xFF;
        assertEquals(0x00, entry.data()[0]);
        assertThrows(UnsupportedOperationException.class, () -> entries.add(entry));
    }
}
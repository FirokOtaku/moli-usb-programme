package space.firok.moli.usb.api.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码层：SFR 自描述表解析。
 *
 * <p>规范（spec/msu2-wire-protocol.md「SFR」）：源码从 u8 地址 {@code 0x0100} 起读取
 * 256 字节自描述表。每项依次为 NUL 结尾的名称、NUL 结尾的单位、NUL 结尾的 family
 * （只用第一个字节）和 family 决定长度的数据。family 后面的 {@code 00} 是字段
 * 结束符，不是地址。family 高 3 位含义：</p>
 *
 * <ul>
 *   <li>{@code 0}：u8 SFR，数据是 16 位地址；低 5 位为元素个数；</li>
 *   <li>{@code 1}：u16 SFR，数据是 8 位地址；低 5 位为元素个数；</li>
 *   <li>{@code 2}：u32 SFR，数据是 16 位地址；厂商读取函数未完成；</li>
 *   <li>{@code 3}：字节字符串；低 5 位为长度；</li>
 *   <li>{@code 4}：字节数组；解析器对该类型的长度处理存在缺陷，需抓包确认。</li>
 * </ul>
 *
 * <p>表结构本身的解析是「已记录」事实，但 family 2/4 的长度语义仍有缺陷（见
 * spec/verification.md P1），因此解析失败一律报畸形响应，不做猜测性截断。</p>
 */
public final class SfrTableParser {

    /** 自描述表在 SFR 空间的起始地址。 */
    public static final int TABLE_ADDRESS = 0x0100;
    /** 自描述表长度（字节）。 */
    public static final int TABLE_LENGTH = 256;

    private SfrTableParser() {
    }

    /** 一项自描述表条目。 */
    public record Entry(String name, String unit, int family, byte[] data) {

        /** family 高 3 位：条目种类。 */
        public int kind() {
            return (family >>> 5) & 0x07;
        }

        /** family 低 5 位：元素个数或字节长度。 */
        public int count() {
            return family & 0x1F;
        }

        /** 数据副本。 */
        public byte[] dataCopy() {
            return data.clone();
        }

        /** 若该项是 u8/u32 SFR 或字节串，按大端解出首个 16 位地址。 */
        public int addressU16() {
            if (data.length < 2) {
                throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.Entry.addressU16", null,
                        "条目 " + name + " 数据不足 2 字节，无法读取 16 位地址");
            }
            return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        }

        /** 若该项是 u16 SFR，按大端解出 8 位地址。 */
        public int addressU8() {
            if (data.length < 1) {
                throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.Entry.addressU8", null,
                        "条目 " + name + " 为空，无法读取 8 位地址");
            }
            return data[0] & 0xFF;
        }
    }

    /**
     * 解析 256 字节自描述表。
     *
     * @param table 原始表字节，长度必须为 {@value #TABLE_LENGTH}
     * @throws space.firok.moli.usb.api.error.MalformedResponseError 表被截断、缺少结尾 NUL
     *         或 family 声明的长度超出剩余数据
     */
    public static List<Entry> parse(byte[] table) {
        if (table == null) {
            throw new space.firok.moli.usb.api.error.MalformedResponseError(
                    "SfrTableParser.parse", null, "自描述表为 null");
        }
        if (table.length < TABLE_LENGTH) {
            throw new space.firok.moli.usb.api.error.MalformedResponseError(
                    "SfrTableParser.parse", null,
                    "自描述表长度不足：" + table.length + " < " + TABLE_LENGTH);
        }
        List<Entry> entries = new ArrayList<>();
        int offset = 0;
        while (offset < TABLE_LENGTH) {
            if ((table[offset] & 0xFF) == 0) {
                // 全零区域表示表已结束。
                break;
            }
            int nameEnd = indexOfNul(table, offset);
            String name = ascii(table, offset, nameEnd);
            offset = nameEnd + 1;
            int unitEnd = indexOfNul(table, offset);
            String unit = ascii(table, offset, unitEnd);
            offset = unitEnd + 1;
            if (offset >= TABLE_LENGTH) {
                throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.parse", null, "条目 " + name + " 缺少 family 字节");
            }
            int family = table[offset] & 0xFF;
            offset++;
            if (offset >= TABLE_LENGTH || (table[offset] & 0xFF) != 0) {
                throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.parse", null,
                        "条目 " + name + " 的 family 缺少结尾 NUL");
            }
            offset++;
            int kind = (family >>> 5) & 0x07;
            int count = family & 0x1F;
            int dataLength = switch (kind) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 2;
                case 3, 4 -> count;
                default -> throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.parse", null,
                        "条目 " + name + " 的 family 种类 " + kind + " 未定义");
            };
            if (offset + dataLength > TABLE_LENGTH) {
                throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.parse", null,
                        "条目 " + name + " 的数据越出表尾: offset=" + offset
                                + ", length=" + dataLength);
            }
            byte[] data = new byte[dataLength];
            System.arraycopy(table, offset, data, 0, dataLength);
            entries.add(new Entry(name, unit, family, data));
            offset += dataLength;
        }
        return List.copyOf(entries);
    }

    private static int indexOfNul(byte[] table, int from) {
        for (int i = from; i < TABLE_LENGTH; i++) {
            if ((table[i] & 0xFF) == 0) {
                return i;
            }
        }
        throw new space.firok.moli.usb.api.error.MalformedResponseError(
                "SfrTableParser.parse", null, "自描述字符串缺少结尾 NUL（起始 " + from + "）");
    }

    private static String ascii(byte[] table, int from, int to) {
        StringBuilder builder = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            int c = table[i] & 0xFF;
            if (c < 0x20 || c > 0x7E) {
                throw new space.firok.moli.usb.api.error.MalformedResponseError(
                        "SfrTableParser.parse", null,
                        "自描述名称含非 ASCII 字节 0x" + Integer.toHexString(c));
            }
            builder.append((char) c);
        }
        return builder.toString();
    }
}
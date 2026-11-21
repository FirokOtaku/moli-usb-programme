package space.firok.moli.usb.api.codec;

/**
 * 编解码层：十六进制格式化工具。
 *
 * <p>只用于错误消息与测试断言，不参与协议处理。实现保持无 I/O、无状态。</p>
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

    private Hex() {
    }

    /** 把整段字节格式化为大写、单空格分隔的十六进制串；null 返回 {@code "(null)"}。 */
    public static String of(byte[] bytes) {
        if (bytes == null) {
            return "(null)";
        }
        return of(bytes, 0, bytes.length);
    }

    /** 把指定区间格式化为大写、单空格分隔的十六进制串。 */
    public static String of(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            return "(null)";
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException(
                    "十六进制格式化范围非法: offset=" + offset + ", length=" + length
                            + ", total=" + bytes.length);
        }
        StringBuilder builder = new StringBuilder(length * 3);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            int value = bytes[offset + i] & 0xFF;
            builder.append(DIGITS[value >>> 4]).append(DIGITS[value & 0x0F]);
        }
        return builder.toString();
    }
}
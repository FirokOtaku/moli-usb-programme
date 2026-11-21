package space.firok.moli.usb.api.codec;

import java.util.OptionalInt;

/**
 * 编解码层：握手编解码。
 *
 * <p>规范（spec/msu2-wire-protocol.md「传输与握手」）：</p>
 *
 * <ul>
 *   <li>设备约每 100 ms 广播 {@code 00 4D 53 4E 30 31} 一类的 6 字节横幅，即
 *       {@code \0MSN} 加两个 ASCII 十进制数字；</li>
 *   <li>主机发送 {@code 00 4D 53 4E 43 4E}（{@code \0MSNCN}）；</li>
 *   <li>设备回同样的 {@code \0MSNCN} 表示握手完成。</li>
 * </ul>
 *
 * <p>两个数字按十进制解析为协议版本。它们是否同时表示固件主次版本尚未确认
 * （spec/verification.md P0），因此本类型只暴露 {@code protocolVersion}。</p>
 */
public final class HandshakeCodec {

    /** 横幅与握手帧共享的 4 字节前缀：{@code \0MSN}。 */
    public static final byte[] MSN_PREFIX = {0x00, 0x4D, 0x53, 0x4E};

    /** 主机挑战帧 {@code \0MSNCN}。 */
    public static final byte[] CHALLENGE = {0x00, 0x4D, 0x53, 0x4E, 0x43, 0x4E};

    private HandshakeCodec() {
    }

    /** 返回 {@code \0MSNCN} 的新副本；调用方可安全修改返回值。 */
    public static byte[] encodeChallenge() {
        return CHALLENGE.clone();
    }

    /**
     * 尝试把 {@code bytes} 解析为设备横幅（{@code \0MSN} + 两位 ASCII 数字）。
     *
     * @return 协议版本；字节不足 6、前缀不符或两位不是 ASCII 数字时返回空
     */
    public static OptionalInt tryParseBanner(byte[] bytes) {
        if (bytes == null || bytes.length < 6) {
            return OptionalInt.empty();
        }
        if (!hasMsnPrefix(bytes, 0)) {
            return OptionalInt.empty();
        }
        int tens = bytes[4] & 0xFF;
        int ones = bytes[5] & 0xFF;
        if (tens < 0x30 || tens > 0x39 || ones < 0x30 || ones > 0x39) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((tens - 0x30) * 10 + (ones - 0x30));
    }

    /** {@code bytes} 是否为完整的设备横幅。 */
    public static boolean isBanner(byte[] bytes) {
        return tryParseBanner(bytes).isPresent();
    }

    /**
     * {@code bytes} 是否为握手确认响应 {@code \0MSNCN}。
     *
     * <p>握手阶段设备仍可能插入横幅，因此前 6 字节不是确认帧时返回 {@code false}，
     * 由会话层继续等待；只有确实收齐 6 字节且不等于确认帧才判定失败。</p>
     */
    public static boolean isConfirm(byte[] bytes) {
        if (bytes == null || bytes.length < 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (bytes[i] != CHALLENGE[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在字节流的 {@code offset} 处是否为 {@code \0MSN} 前缀。
     * 用于会话层在噪声或残留字节中重新对齐横幅。
     */
    public static boolean hasMsnPrefix(byte[] bytes, int offset) {
        if (bytes == null || offset < 0 || offset + 4 > bytes.length) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (bytes[offset + i] != MSN_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 严格解析握手横幅，失败时抛出协议不匹配错误。
     *
     * <p>握手确认之后再遇到的横幅属于协议失配，不能在宽松模式下静默忽略。</p>
     */
    public static int parseBannerStrict(byte[] bytes, String operation, String deviceId) {
        OptionalInt version = tryParseBanner(bytes);
        if (version.isEmpty()) {
            throw new space.firok.moli.usb.api.error.ProtocolMismatchError(operation, deviceId,
                    "横幅不是 \\0MSN + 两位 ASCII 十进制数字: " + Hex.of(bytes));
        }
        return version.getAsInt();
    }

    /** 由协议版本构造等效横幅字节，用于测试与回环模拟。 */
    public static byte[] encodeBanner(int protocolVersion) {
        if (protocolVersion < 0 || protocolVersion > 99) {
            throw new space.firok.moli.usb.api.error.InvalidArgumentError("HandshakeCodec.encodeBanner", null,
                    "协议版本必须落在 0..99（实际 " + protocolVersion + "）");
        }
        return new byte[]{
                MSN_PREFIX[0], MSN_PREFIX[1], MSN_PREFIX[2], MSN_PREFIX[3],
                (byte) ('0' + protocolVersion / 10), (byte) ('0' + protocolVersion % 10)
        };
    }
}
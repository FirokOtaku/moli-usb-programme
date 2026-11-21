package space.firok.moli.usb.api.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.ProtocolMismatchError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 握手编解码测试：{@code \0MSNxx} 横幅与 {@code \0MSNCN} 挑战/确认。
 */
class HandshakeCodecTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("挑战帧固定为 00 4D 53 4E 43 4E")
    void challengeIsFixed() {
        assertArrayEquals(HandshakeCodec.CHALLENGE,
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x43, 0x4E));
        assertArrayEquals(bytes(0x00, 0x4D, 0x53, 0x4E, 0x43, 0x4E),
                HandshakeCodec.encodeChallenge());
    }

    @Test
    @DisplayName("encodeChallenge 返回独立副本")
    void challengeIsCopied() {
        byte[] first = HandshakeCodec.encodeChallenge();
        first[0] = (byte) 0xFF;
        assertArrayEquals(bytes(0x00, 0x4D, 0x53, 0x4E, 0x43, 0x4E),
                HandshakeCodec.encodeChallenge());
    }

    @Test
    @DisplayName("规范横幅 00 4D 53 4E 30 31 解析为协议版本 1")
    void parsesSpecBanner() {
        var version = HandshakeCodec.tryParseBanner(bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31));
        assertTrue(version.isPresent());
        assertEquals(1, version.getAsInt());
        assertTrue(HandshakeCodec.isBanner(bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31)));
    }

    @Test
    @DisplayName("两位 ASCII 数字按十进制解析：10 → 版本 10，37 → 版本 37")
    void parsesDecimalVersion() {
        assertEquals(10, HandshakeCodec.tryParseBanner(
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x31, 0x30)).getAsInt());
        assertEquals(37, HandshakeCodec.tryParseBanner(
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x33, 0x37)).getAsInt());
        assertEquals(0, HandshakeCodec.tryParseBanner(
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x30)).getAsInt());
    }

    @Test
    @DisplayName("encodeBanner 与 tryParseBanner 往返一致")
    void bannerRoundTrip() {
        for (int version = 0; version <= 99; version++) {
            byte[] banner = HandshakeCodec.encodeBanner(version);
            assertEquals(version, HandshakeCodec.tryParseBanner(banner).getAsInt());
        }
    }

    @Test
    @DisplayName("不足 6 字节、前缀不符、非数字版本都不算横幅")
    void rejectsNonBanners() {
        assertTrue(HandshakeCodec.tryParseBanner(new byte[0]).isEmpty());
        assertTrue(HandshakeCodec.tryParseBanner(bytes(0x00, 0x4D, 0x53, 0x4E, 0x30)).isEmpty());
        assertTrue(HandshakeCodec.tryParseBanner(
                bytes(0x00, 0x4D, 0x53, 0x4F, 0x30, 0x31)).isEmpty());
        assertTrue(HandshakeCodec.tryParseBanner(
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x41, 0x31)).isEmpty());
        assertTrue(HandshakeCodec.tryParseBanner(
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x3A)).isEmpty());
        assertTrue(HandshakeCodec.tryParseBanner(null).isEmpty());
    }

    @Test
    @DisplayName("横幅与确认帧互不混淆")
    void bannerIsNotConfirm() {
        assertFalse(HandshakeCodec.isConfirm(bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31)));
        assertTrue(HandshakeCodec.isConfirm(bytes(0x00, 0x4D, 0x53, 0x4E, 0x43, 0x4E)));
        assertFalse(HandshakeCodec.isConfirm(bytes(0x00, 0x4D, 0x53, 0x4E, 0x43)));
        assertFalse(HandshakeCodec.isConfirm(null));
    }

    @Test
    @DisplayName("hasMsnPrefix 支持在偏移处重新对齐")
    void findsPrefixAtOffset() {
        byte[] noisy = bytes(0xFF, 0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31);
        assertTrue(HandshakeCodec.hasMsnPrefix(noisy, 1));
        assertFalse(HandshakeCodec.hasMsnPrefix(noisy, 0));
        assertFalse(HandshakeCodec.hasMsnPrefix(noisy, 4));
        assertFalse(HandshakeCodec.hasMsnPrefix(null, 0));
    }

    @Test
    @DisplayName("严格解析失败抛 ProtocolMismatch，不静默返回 0")
    void strictParseFailsLoudly() {
        assertThrows(ProtocolMismatchError.class,
                () -> HandshakeCodec.parseBannerStrict(bytes(0x00, 0x4D, 0x53, 0x4E, 0x41, 0x41),
                        "handshake", "dev"));
        assertEquals(7, HandshakeCodec.parseBannerStrict(
                bytes(0x00, 0x4D, 0x53, 0x4E, 0x30, 0x37), "handshake", "dev"));
    }

    @Test
    @DisplayName("encodeBanner 拒绝 00..99 之外的版本")
    void encodeBannerValidates() {
        assertThrows(InvalidArgumentError.class, () -> HandshakeCodec.encodeBanner(-1));
        assertThrows(InvalidArgumentError.class, () -> HandshakeCodec.encodeBanner(100));
    }
}
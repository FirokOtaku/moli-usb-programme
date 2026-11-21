package space.firok.moli.usb.serial.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.firok.moli.usb.api.codec.HandshakeCodec;
import space.firok.moli.usb.api.codec.Hex;
import space.firok.moli.usb.api.transport.Transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HandshakeProbe} 的分阶段握手测试。
 *
 * <p>用脚本化假传输回放字节，不依赖真实串口。测试重点是「失败原因必须能区分
 * 超时、未收到横幅、回显不匹配」，因为实机调试就靠这个提示。</p>
 */
class HandshakeProbeTest {

    /** 按脚本回放的传输：每次 read 返回队列中的一段，写出的字节被记录下来。 */
    private static final class ScriptedTransport implements Transport {

        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private boolean disconnected;
        private boolean closed;

        ScriptedTransport(byte[]... script) {
            for (byte[] chunk : script) {
                chunks.add(chunk);
            }
        }

        static ScriptedTransport disconnectedAfter(byte[]... script) {
            ScriptedTransport transport = new ScriptedTransport(script);
            transport.disconnected = true;
            return transport;
        }

        @Override
        public byte[] read(int maxBytes, int timeoutMs) {
            byte[] chunk = chunks.pollFirst();
            if (chunk != null) {
                return chunk;
            }
            if (disconnected) {
                return null;
            }
            return new byte[0];
        }

        @Override
        public void write(byte[] data) {
            written.write(data, 0, data.length);
        }

        @Override
        public void clearInput() {
            chunks.clear();
        }

        @Override
        public boolean isConnected() {
            return !closed && !disconnected;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public String address() {
            return "FAKE";
        }

        byte[] writtenBytes() {
            return written.toByteArray();
        }
    }

    @Test
    @DisplayName("收到横幅并得到回显后握手成功，版本按十进制解析")
    void successPath() throws IOException {
        byte[] banner = HandshakeCodec.encodeBanner(12);
        ScriptedTransport transport = new ScriptedTransport(
                new byte[]{0x11, 0x22},
                banner,
                HandshakeCodec.CHALLENGE.clone());

        HandshakeProbe.Result result = HandshakeProbe.handshake(transport, 2000);

        assertTrue(result.ok());
        assertEquals(12, result.protocolVersion());
        assertArrayEquals(banner, result.banner());
        assertArrayEquals(HandshakeCodec.CHALLENGE.clone(), transport.writtenBytes());
        // 原始字节要覆盖噪声与横幅，便于与 spec 对照。
        assertTrue(result.rawReceived().length >= 8);
    }

    @Test
    @DisplayName("完全没有数据时报告「超时未收到横幅」")
    void timeoutWithoutBanner() throws IOException {
        HandshakeProbe.Result result = HandshakeProbe.handshake(new ScriptedTransport(), 120);

        assertFalse(result.ok());
        assertEquals(HandshakeProbe.Failure.TIMEOUT_NO_BANNER, result.failure());
        assertNull(result.protocolVersion());
        assertTrue(result.failureText(120).contains("超时未收到横幅"));
    }

    @Test
    @DisplayName("只有噪声没有横幅时同样报告未收到横幅，并保留原始字节")
    void noiseOnly() throws IOException {
        ScriptedTransport transport = new ScriptedTransport(new byte[]{0x01, 0x02, 0x03});
        HandshakeProbe.Result result = HandshakeProbe.handshake(transport, 120);

        assertEquals(HandshakeProbe.Failure.TIMEOUT_NO_BANNER, result.failure());
        assertEquals("01 02 03", Hex.of(result.rawReceived()));
    }

    @Test
    @DisplayName("收到横幅但无回显时报告「超时未收到回显」，并已发出挑战")
    void timeoutWithoutEcho() throws IOException {
        ScriptedTransport transport = new ScriptedTransport(HandshakeCodec.encodeBanner(1));
        HandshakeProbe.Result result = HandshakeProbe.handshake(transport, 150);

        assertEquals(HandshakeProbe.Failure.TIMEOUT_NO_ECHO, result.failure());
        assertArrayEquals(HandshakeCodec.CHALLENGE.clone(), transport.writtenBytes());
        assertTrue(result.failureText(150).contains("超时未收到回显"));
    }

    @Test
    @DisplayName("回显字节不对时报告回显不匹配，并给出期望与实际")
    void echoMismatch() throws IOException {
        ScriptedTransport transport = new ScriptedTransport(
                HandshakeCodec.encodeBanner(1),
                new byte[]{0x00, 0x4D, 0x53, 0x4E, 0x41, 0x41});
        HandshakeProbe.Result result = HandshakeProbe.handshake(transport, 500);

        assertEquals(HandshakeProbe.Failure.ECHO_MISMATCH, result.failure());
        String text = result.failureText(500);
        assertTrue(text.contains("回显不匹配"));
        assertTrue(text.contains(Hex.of(HandshakeCodec.CHALLENGE)));
        assertTrue(text.contains("00 4D 53 4E 41 41"));
    }

    @Test
    @DisplayName("等待横幅期间断开时报告断开而不是超时")
    void disconnectedWhileWaiting() throws IOException {
        HandshakeProbe.Result result = HandshakeProbe.handshake(
                ScriptedTransport.disconnectedAfter(), 500);

        assertEquals(HandshakeProbe.Failure.DISCONNECTED, result.failure());
        assertTrue(result.failureText(500).contains("传输断开"));
    }

    @Test
    @DisplayName("横幅可跨多次读取拼出来")
    void bannerSplitAcrossReads() throws IOException {
        byte[] banner = HandshakeCodec.encodeBanner(21);
        ScriptedTransport transport = new ScriptedTransport(
                new byte[]{banner[0], banner[1]},
                new byte[]{banner[2], banner[3]},
                new byte[]{banner[4], banner[5]},
                HandshakeCodec.CHALLENGE.clone());

        HandshakeProbe.Result result = HandshakeProbe.handshake(transport, 2000);

        assertTrue(result.ok());
        assertEquals(21, result.protocolVersion());
        assertArrayEquals(banner, result.banner());
    }

    @Test
    @DisplayName("findBanner 在噪声里定位横幅并拒绝非数字版本位")
    void findBannerRejectsNonDigits() {
        byte[] withNoise = new byte[]{0x7F, 0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31, 0x00};
        assertArrayEquals(new int[]{1, 1}, HandshakeProbe.findBanner(withNoise));

        byte[] notDigits = new byte[]{0x00, 0x4D, 0x53, 0x4E, 0x41, 0x42};
        assertArrayEquals(new int[]{-1, -1}, HandshakeProbe.findBanner(notDigits));

        assertArrayEquals(new int[]{-1, -1}, HandshakeProbe.findBanner(new byte[]{0x00, 0x4D}));
    }

    @Test
    @DisplayName("拒绝非正超时")
    void rejectsNonPositiveTimeout() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HandshakeProbe.handshake(new ScriptedTransport(), 0));
    }

    @Test
    @DisplayName("原始字节累积有上限，不会因噪声无限增长")
    void rawBytesAreCapped() throws IOException {
        List<byte[]> noise = new ArrayList<>();
        byte[] chunk = new byte[1024];
        for (int i = 0; i < 20; i++) {
            noise.add(chunk.clone());
        }
        ScriptedTransport transport = new ScriptedTransport(noise.toArray(byte[][]::new));

        HandshakeProbe.Result result = HandshakeProbe.handshake(transport, 300);

        assertTrue(result.rawReceived().length <= HandshakeProbe.RAW_LIMIT);
    }
}
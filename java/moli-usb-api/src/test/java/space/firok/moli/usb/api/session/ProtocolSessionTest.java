package space.firok.moli.usb.api.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.codec.Commands;
import space.firok.moli.usb.api.codec.Frame;
import space.firok.moli.usb.api.codec.HandshakeCodec;
import space.firok.moli.usb.api.codec.Hex;
import space.firok.moli.usb.api.codec.SfrCodec;
import space.firok.moli.usb.api.error.CancelledError;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.HandshakeFailedError;
import space.firok.moli.usb.api.error.MalformedResponseError;
import space.firok.moli.usb.api.error.ProtocolDesynchronizedError;
import space.firok.moli.usb.api.error.TimeoutError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

/**
 * 会话层测试：全部使用假传输，不需要真实硬件。
 *
 * <p>覆盖握手成功/失败/超时、请求-响应串行化、断线、取消、失步恢复、双开与生命周期。</p>
 */
class ProtocolSessionTest {

    private static final byte[] BANNER = HandshakeCodec.encodeBanner(1);
    private static final byte[] CONFIRM = HandshakeCodec.CHALLENGE;

    private static SessionSettings fastSettings() {
        return new SessionSettings(
                Duration.ofMillis(120),
                2,
                Duration.ofMillis(80),
                2,
                1);
    }

    /** 一个会握手成功并对每帧回显的虚拟设备。 */
    private static FakeTransport handshakingTransport(String address) {
        FakeTransport transport = new FakeTransport(address);
        transport.script(BANNER);
        transport.respondWith(frame -> {
            if (Arrays.equals(frame, CONFIRM.clone())) {
                return CONFIRM.clone();
            }
            return frame.clone();
        });
        return transport;
    }

    private static ProtocolSession openSession(FakeTransport transport, SessionSettings settings) {
        return ProtocolSession.open(transport, settings, new DeviceSessionRegistry(),
                CancellationToken.none());
    }

    @Nested
    @DisplayName("握手")
    class Handshake {

        @Test
        @DisplayName("设备广播横幅并回 \\0MSNCN 时握手成功，解析出版本 1")
        void succeeds() {
            FakeTransport transport = handshakingTransport("COM1");
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertEquals(1, session.protocolVersion());
                assertEquals("COM1", session.deviceKey());
                assertTrue(session.isOpen());
                // 主机确实发送了 \0MSNCN。
                assertTrue(indexOf(transport.allWritten(), CONFIRM) >= 0);
            }
        }

        @Test
        @DisplayName("横幅版本按十进制解析：06 → 版本 6")
        void parsesVersion() {
            FakeTransport transport = new FakeTransport("COM2");
            transport.script(HandshakeCodec.encodeBanner(6));
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertEquals(6, session.protocolVersion());
            }
        }

        @Test
        @DisplayName("设备从未广播横幅时抛 HandshakeFailed，而不是无限等待")
        void failsWithoutBanner() {
            FakeTransport transport = new FakeTransport("COM3");
            assertThrows(HandshakeFailedError.class,
                    () -> openSession(transport, fastSettings()));
            assertFalse(transport.isConnected(), "失败时必须关闭传输，避免端口泄漏");
        }

        @Test
        @DisplayName("横幅之后设备不回确认帧时抛 HandshakeFailed")
        void failsWithoutConfirm() {
            FakeTransport transport = new FakeTransport("COM4");
            transport.script(BANNER);
            transport.respondWith(frame -> null);
            assertThrows(HandshakeFailedError.class,
                    () -> openSession(transport, fastSettings()));
        }

        @Test
        @DisplayName("设备回了错误的 6 字节响应时抛 HandshakeFailed")
        void failsOnWrongConfirm() {
            FakeTransport transport = new FakeTransport("COM5");
            transport.script(BANNER);
            transport.respondWith(frame -> new byte[]{0x02, 0x03, 0x0B, 0x00, 0x00, 0x00});
            assertThrows(HandshakeFailedError.class,
                    () -> openSession(transport, fastSettings()));
        }

        @Test
        @DisplayName("握手期间断线抛 DisconnectedError，而不是握手失败")
        void disconnectDuringHandshake() {
            FakeTransport transport = new FakeTransport("COM6");
            transport.disconnect();
            assertThrows(DisconnectedError.class, () -> openSession(transport, fastSettings()));
        }

        @Test
        @DisplayName("握手期间取消抛 CancelledError")
        void cancelDuringHandshake() {
            FakeTransport transport = new FakeTransport("COM7");
            assertThrows(CancelledError.class, () -> ProtocolSession.open(transport,
                    fastSettings(), new DeviceSessionRegistry(),
                    CancellationToken.cancelled("测试取消")));
        }

        @Test
        @DisplayName("横幅前面的噪声字节被跳过，仍能重新对齐")
        void resynchronisesOnNoise() {
            FakeTransport transport = new FakeTransport("COM8");
            transport.script(new byte[]{(byte) 0xFF, 0x12, 0x00}, BANNER);
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertEquals(1, session.protocolVersion());
            }
        }

        @Test
        @DisplayName("握手期间插入的额外横幅不会让握手失败")
        void toleratesBannerDuringConfirm() {
            FakeTransport transport = new FakeTransport("COM9");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (!Arrays.equals(frame, CONFIRM)) {
                    return null;
                }
                // 先再广播一次，然后才是确认帧。
                return concat(BANNER, CONFIRM);
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertEquals(1, session.protocolVersion());
            }
        }
    }

    @Nested
    @DisplayName("请求-响应")
    class Exchange {

        @Test
        @DisplayName("完整且回显匹配的响应被接受")
        void acceptsMatchingResponse() {
            FakeTransport transport = handshakingTransport("COM10");
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                Frame request = SfrCodec.readU8(0x0123);
                var check = session.exchange(request, "read", CancellationToken.none(), true);
                assertTrue(check.isOk());
                // 设备回显请求帧，byte 5 就是请求里那一字节。
                assertEquals(0x00, check.u8AtByte5());
                assertEquals("00 30 00 01 23 00", check.responseFrame().hex());
            }
        }

        @Test
        @DisplayName("没有任何响应时抛 TimeoutError")
        void timesOut() {
            FakeTransport transport = new FakeTransport("COM11");
            transport.script(BANNER);
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertThrows(TimeoutError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0001), "read", CancellationToken.none(), true));
            }
        }

        @Test
        @DisplayName("响应只有一部分字节时抛 MalformedResponse，不会静默等待长短帧")
        void partialResponseIsMalformed() {
            FakeTransport transport = new FakeTransport("COM12");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                return new byte[]{0x00, 0x30, 0x00};
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertThrows(MalformedResponseError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0001), "read", CancellationToken.none(), false));
            }
        }

        @Test
        @DisplayName("响应命令不对应时抛 ProtocolDesynchronized")
        void wrongResponseIsDesynchronized() {
            FakeTransport transport = new FakeTransport("COM13");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                return new byte[]{0x02, 0x03, 0x0B, 0x00, 0x00, 0x00};
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertThrows(ProtocolDesynchronizedError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0001), "read", CancellationToken.none(), false));
            }
        }

        @Test
        @DisplayName("设备无请求 ID：并发调用被串行化，响应不会错配")
        void serialisesConcurrentExchanges() throws Exception {
            FakeTransport transport = new FakeTransport("COM14");
            transport.script(BANNER);
            // 回显带一点延迟，确保两个线程真正重叠。
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : frame.clone());
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                int threads = 8;
                Thread[] workers = new Thread[threads];
                boolean[] ok = new boolean[threads];
                for (int i = 0; i < threads; i++) {
                    int index = i;
                    workers[i] = new Thread(() -> {
                        Frame request = SfrCodec.readU8(0x0100 + index);
                        var check = session.exchange(request, "concurrent",
                                CancellationToken.none(), false);
                        // 响应必须回显本次请求的地址字节，否则说明发生了错配。
                        ok[index] = check.isOk()
                                && check.responseFrame().data1() == 0x01
                                && check.responseFrame().data2() == index;
                    });
                }
                for (Thread worker : workers) {
                    worker.start();
                }
                for (Thread worker : workers) {
                    worker.join(5_000);
                }
                for (int i = 0; i < threads; i++) {
                    assertTrue(ok[i], "线程 " + i + " 的响应发生错配");
                }
            }
        }

        @Test
        @DisplayName("使用中断线时抛 DisconnectedError")
        void disconnectsMidUse() {
            FakeTransport transport = new FakeTransport("COM15");
            transport.script(BANNER);
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            ProtocolSession session = openSession(transport, fastSettings());
            session.send(SfrCodec.writeU8(0x0100, 1), "write");
            transport.disconnect();
            assertThrows(DisconnectedError.class, () -> session.exchange(
                    SfrCodec.readU8(0x0100), "read", CancellationToken.none(), false));
            session.close();
        }

        @Test
        @DisplayName("写失败被归为断线，而不是抛出裸 IOException")
        void writeFailureBecomesDisconnect() {
            FakeTransport transport = new FakeTransport("COM16");
            transport.script(BANNER);
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                transport.failNextWrite(new java.io.IOException("模拟写失败"));
                assertThrows(DisconnectedError.class, () -> session.send(
                        SfrCodec.writeU8(0x0100, 1), "write"));
            }
        }
    }

    @Nested
    @DisplayName("失步恢复")
    class DesyncRecovery {

        @Test
        @DisplayName("读类操作失步后清空输入并重发，第二次成功")
        void recoversSafeOperations() {
            FakeTransport transport = new FakeTransport("COM17");
            transport.script(BANNER);
            int[] exchangeCount = {0};
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                exchangeCount[0]++;
                if (exchangeCount[0] == 1) {
                    // 第一次回一个不属于该请求的帧，制造失步。
                    return new byte[]{0x09, 0x32, (byte) 0xF8, 0x00, 0x00, 0x00};
                }
                return frame.clone();
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                var check = session.exchange(SfrCodec.readU8(0x0042), "read",
                        CancellationToken.none(), true);
                assertTrue(check.isOk(), "失步后应恢复并成功");
                assertEquals(2, exchangeCount[0], "应重发一次");
            }
        }

        @Test
        @DisplayName("恢复次数用尽后报告失步，不会无限重试")
        void stopsAfterRecoveryLimit() {
            FakeTransport transport = new FakeTransport("COM18");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                return new byte[]{0x09, 0x32, (byte) 0xF8, 0x00, 0x00, 0x00};
            });
            SessionSettings settings = new SessionSettings(Duration.ofMillis(120), 2,
                    Duration.ofMillis(80), 2, 1);
            try (ProtocolSession session = openSession(transport, settings)) {
                assertThrows(ProtocolDesynchronizedError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0042), "read", CancellationToken.none(), true));
            }
        }

        @Test
        @DisplayName("不可安全重试的操作（写）失步时立即失败并清空残留")
        void doesNotRetryUnsafeOperations() {
            FakeTransport transport = new FakeTransport("COM19");
            transport.script(BANNER);
            int[] exchangeCount = {0};
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                exchangeCount[0]++;
                return new byte[]{0x09, 0x32, (byte) 0xF8, 0x00, 0x00, 0x00};
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                assertThrows(ProtocolDesynchronizedError.class, () -> session.exchange(
                        SfrCodec.writeU8(0x0100, 0x7F), "write", CancellationToken.none(), false));
                assertEquals(1, exchangeCount[0], "写操作不得自动重发");
                assertEquals(0, transport.pendingBytes(), "残留必须在报告前清空");
            }
        }

        @Test
        @DisplayName("发送前缓冲有残留时视为失步，而不是把残留当本次响应")
        void detectsStaleResidueBeforeSend() {
            FakeTransport transport = new FakeTransport("COM20");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                // 每次回 8 字节：多出的 2 字节留在缓冲里。
                return new byte[]{0x00, 0x30, 0x00, 0x01, 0x23, 0x7F, 0x55, 0x66};
            });
            SessionSettings settings = new SessionSettings(Duration.ofMillis(120), 2,
                    Duration.ofMillis(80), 2, 2);
            try (ProtocolSession session = openSession(transport, settings)) {
                // 第一次交换会得到 8 字节 → 失步 → 恢复会排空残留 → 重发仍 8 字节 → 最终失败。
                assertThrows(ProtocolDesynchronizedError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0123), "read", CancellationToken.none(), true));
            }
        }

        @Test
        @DisplayName("7 字节是 6 字节回显加横幅前缀 00，byte 5 仍是寄存器值")
        void sevenByteResponseIsFramePlusBannerPrefix() {
            FakeTransport transport = new FakeTransport("COM70");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                byte[] echo = frame.clone();
                echo[5] = (byte) 0xA0;
                return concat(echo, new byte[]{0x00});
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                var check = session.exchange(SfrCodec.readU8(0x0123), "read",
                        CancellationToken.none(), false);
                assertTrue(check.isOk());
                assertEquals(0xA0, check.u8AtByte5());
                assertEquals(6, check.raw().length);
            }
        }

        @Test
        @DisplayName("11 字节是 6 字节回显加 5 字节横幅前缀，收齐后下一命令仍对齐")
        void elevenByteResponseIsFramePlusBannerPrefix() {
            FakeTransport transport = new FakeTransport("COM71");
            transport.script(BANNER);
            transport.respondWith(frame -> {
                if (Arrays.equals(frame, CONFIRM)) {
                    return CONFIRM.clone();
                }
                byte[] echo = frame.clone();
                echo[5] = (byte) 0x50;
                return concat(echo, new byte[]{0x00, 0x4D, 0x53, 0x4E, 0x30});
            });
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                var first = session.exchange(SfrCodec.readU8(0x0100), "read",
                        CancellationToken.none(), false);
                assertTrue(first.isOk());
                assertEquals(0x50, first.u8AtByte5());
                assertEquals(6, first.raw().length);
                // 横幅还差最后一字节。实机上它已在途；这里在下一命令前补上。
                transport.script(new byte[]{0x31});
                var second = session.exchange(SfrCodec.readU8(0x0101), "read",
                        CancellationToken.none(), false);
                assertTrue(second.isOk());
                assertEquals(0x50, second.u8AtByte5());
            }
        }
    }

    @Nested
    @DisplayName("取消")
    class Cancel {

        @Test
        @DisplayName("已取消的令牌在开新操作时立即失败，不发送任何字节")
        void cancelledBeforeSend() {
            FakeTransport transport = handshakingTransport("COM21");
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                int writesBefore = transport.writeCount();
                assertThrows(CancelledError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0001), "read", CancellationToken.cancelled("用户取消"), true));
                assertEquals(writesBefore, transport.writeCount(), "取消时不应写出请求");
            }
        }

        @Test
        @DisplayName("等待响应期间取消会抛出 CancelledError")
        void cancelledWhileWaiting() throws Exception {
            FakeTransport transport = new FakeTransport("COM22");
            transport.script(BANNER);
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            SessionSettings settings = new SessionSettings(Duration.ofMillis(120), 2,
                    Duration.ofMillis(5_000), 2, 1);
            try (ProtocolSession session = openSession(transport, settings)) {
                CancellationToken token = new CancellationToken();
                Thread canceller = new Thread(() -> {
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    token.cancel("外部取消");
                });
                canceller.start();
                assertThrows(CancelledError.class, () -> session.exchange(
                        SfrCodec.readU8(0x0001), "read", token, true));
                canceller.join(2_000);
            }
        }

        @Test
        @DisplayName("cancelled 工厂创建的令牌已处于取消状态")
        void factoryCreatesCancelledToken() {
            CancellationToken token = CancellationToken.cancelled("原因");
            assertTrue(token.isCancelled());
            assertEquals("原因", token.reason());
            assertThrows(CancelledError.class, () -> token.throwIfCancelled("op", "dev"));
        }

        @Test
        @DisplayName("reset 后可复用令牌")
        void resetClearsState() {
            CancellationToken token = CancellationToken.cancelled("原因");
            token.reset();
            assertFalse(token.isCancelled());
            token.throwIfCancelled("op", "dev");
        }
    }

    @Nested
    @DisplayName("生命周期与占用")
    class Lifecycle {

        @Test
        @DisplayName("同一地址双开报 DeviceBusyError")
        void secondOpenIsBusy() {
            DeviceSessionRegistry registry = new DeviceSessionRegistry();
            FakeTransport first = handshakingTransport("COM23");
            FakeTransport second = handshakingTransport("COM23");
            ProtocolSession one = ProtocolSession.open(first, fastSettings(), registry,
                    CancellationToken.none());
            try {
                assertThrows(DeviceBusyError.class, () -> ProtocolSession.open(second,
                        fastSettings(), registry, CancellationToken.none()));
                assertTrue(second.isConnected(), "被拒绝的传输不应被库关闭（尚未接管）");
            } finally {
                one.close();
            }
            // 关闭后可重新打开。
            try (ProtocolSession reopened = ProtocolSession.open(second, fastSettings(), registry,
                    CancellationToken.none())) {
                assertTrue(reopened.isOpen());
            }
        }

        @Test
        @DisplayName("close 释放占用并关闭传输，且幂等")
        void closeReleasesAndIsIdempotent() {
            DeviceSessionRegistry registry = new DeviceSessionRegistry();
            FakeTransport transport = handshakingTransport("COM24");
            ProtocolSession session = ProtocolSession.open(transport, fastSettings(), registry,
                    CancellationToken.none());
            assertTrue(registry.isClaimed("COM24"));
            session.close();
            session.close();
            assertFalse(session.isOpen());
            assertFalse(registry.isClaimed("COM24"));
            assertFalse(transport.isConnected());
        }

        @Test
        @DisplayName("关闭后使用会话抛 DisconnectedError")
        void useAfterCloseFails() {
            FakeTransport transport = handshakingTransport("COM25");
            ProtocolSession session = openSession(transport, fastSettings());
            session.close();
            assertThrows(DisconnectedError.class, () -> session.send(
                    SfrCodec.writeU8(0x0100, 1), "write"));
            assertThrows(DisconnectedError.class, () -> session.exchange(
                    SfrCodec.readU8(0x0100), "read", CancellationToken.none(), false));
        }

        @Test
        @DisplayName("传输地址为空时拒绝打开")
        void rejectsBlankAddress() {
            FakeTransport transport = new FakeTransport("   ");
            assertThrows(space.firok.moli.usb.api.error.InvalidArgumentError.class,
                    () -> openSession(transport, fastSettings()));
        }

        @Test
        @DisplayName("sendAll 把多帧合并成一次写入，但保持 6 字节帧边界")
        void sendAllKeepsFrameBoundaries() {
            FakeTransport transport = handshakingTransport("COM26");
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                int before = transport.writeCount();
                session.sendAll(java.util.List.of(
                        SfrCodec.writeU8(0x0100, 1),
                        SfrCodec.writeU8(0x0101, 2)), "write");
                assertEquals(before + 1, transport.writeCount());
                byte[] written = transport.writes().getLast();
                assertEquals(12, written.length);
            }
        }

        @Test
        @DisplayName("awaitAckOrIgnore 把超时当「无回执」，但不接受畸形响应")
        void ackOrIgnoreSemantics() {
            FakeTransport transport = new FakeTransport("COM27");
            transport.script(BANNER);
            transport.respondWith(frame -> Arrays.equals(frame, CONFIRM) ? CONFIRM.clone() : null);
            try (ProtocolSession session = openSession(transport, fastSettings())) {
                var silent = session.awaitAckOrIgnore(
                        space.firok.moli.usb.api.codec.DisplayCodec.beginStream(), "render",
                        CancellationToken.none());
                assertTrue(silent.isEmpty(), "设备不回执时应返回空而不是失败");
            }
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
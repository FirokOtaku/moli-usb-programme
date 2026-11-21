package space.firok.moli.usb.serial.probe;

import space.firok.moli.usb.api.codec.HandshakeCodec;
import space.firok.moli.usb.api.codec.Hex;
import space.firok.moli.usb.api.transport.Transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * 分阶段握手探测：与 {@code ProtocolSession} 的握手语义一致，但把每一步的原始字节和
 * 失败原因单独暴露出来，供人工对照 {@code spec/msu2-wire-protocol.md}。
 *
 * <p>{@code ProtocolSession} 只在最终失败时汇报合并后的原因。实机调试需要知道到底卡在
 * 「没收到横幅」还是「横幅收到了但确认回显不对」，因此探测工具自己走一遍流程，而不
 * 复用会话层。正式客户端仍应使用 {@code ProtocolSession}。</p>
 *
 * <p>协议事实（横幅周期、超时值、两位数字含义）均来自 spec，其中不少仍标注为待实机
 * 确认；本类只如实执行，不引入新的判定。</p>
 */
public final class HandshakeProbe {

    /** 累积原始字节的上限，防止噪声流把内存吃满。 */
    static final int RAW_LIMIT = 8192;

    /** 单次底层读的阻塞粒度。 */
    private static final int READ_CHUNK_TIMEOUT_MS = 50;

    private HandshakeProbe() {
    }

    /** 失败原因。 */
    public enum Failure {
        /** 超时内没有任何 {@code \0MSN} 横幅。 */
        TIMEOUT_NO_BANNER,
        /** 收到横幅并发出挑战，但超时内没有确认回显。 */
        TIMEOUT_NO_ECHO,
        /** 收到 6 字节回显，但不是 {@code \0MSNCN}。 */
        ECHO_MISMATCH,
        /** 传输断开。 */
        DISCONNECTED
    }

    /**
     * 探测结果。
     *
     * @param protocolVersion 成功时的协议版本（横幅两位数字）；失败时为 {@code null}
     * @param banner          成功时匹配到的横幅字节；失败时为 {@code null}
     * @param failure         失败原因；成功时为 {@code null}
     * @param rawReceived     本次收到的全部原始字节（含横幅与回显）
     * @param echo            进入回显阶段后收到的字节；未进入该阶段时为空数组
     */
    public record Result(Integer protocolVersion,
                         byte[] banner,
                         Failure failure,
                         byte[] rawReceived,
                         byte[] echo) {

        /** 是否成功。 */
        public boolean ok() {
            return failure == null;
        }

        /** 人类可读的失败说明；成功时返回空串。 */
        public String failureText(int timeoutMs) {
            if (failure == null) {
                return "";
            }
            return switch (failure) {
                case TIMEOUT_NO_BANNER -> "超时未收到横幅：在 " + timeoutMs + " ms 内没有收到 "
                        + "\\0MSN + 两位数字的设备横幅";
                case TIMEOUT_NO_ECHO -> "超时未收到回显：已收到横幅并发送 \\0MSNCN，但在 "
                        + timeoutMs + " ms 内没有收到确认回显";
                case ECHO_MISMATCH -> "回显不匹配：期望 " + Hex.of(HandshakeCodec.CHALLENGE)
                        + "，实际 " + Hex.of(echo);
                case DISCONNECTED -> "传输断开：握手过程中端口关闭或设备拔出";
            };
        }
    }

    /**
     * 执行一次分阶段握手。
     *
     * @param transport 已打开的传输
     * @param timeoutMs 握手总超时（从等待横幅开始计算，覆盖回显阶段）
     * @throws IOException 底层读写故障
     */
    public static Result handshake(Transport transport, int timeoutMs) throws IOException {
        Objects.requireNonNull(transport, "transport");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

        // 阶段一：等待横幅。设备约每 100 ms 广播一次，允许在噪声里重新对齐。
        int bannerStart = -1;
        int version = -1;
        while (bannerStart < 0) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return new Result(null, null, Failure.TIMEOUT_NO_BANNER,
                        raw.toByteArray(), new byte[0]);
            }
            byte[] chunk = transport.read(256, (int) Math.min(remainingMs, READ_CHUNK_TIMEOUT_MS));
            if (chunk == null) {
                return new Result(null, null, Failure.DISCONNECTED,
                        raw.toByteArray(), new byte[0]);
            }
            if (chunk.length == 0) {
                continue;
            }
            appendLimited(raw, chunk);
            int[] found = findBanner(raw.toByteArray());
            bannerStart = found[0];
            version = found[1];
        }

        byte[] banner = new byte[6];
        System.arraycopy(raw.toByteArray(), bannerStart, banner, 0, 6);

        // 阶段二：发出挑战并等待 6 字节回显。
        transport.write(HandshakeCodec.encodeChallenge());
        ByteArrayOutputStream echo = new ByteArrayOutputStream();
        while (echo.size() < 6) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return new Result(null, banner, Failure.TIMEOUT_NO_ECHO,
                        raw.toByteArray(), echo.toByteArray());
            }
            byte[] chunk = transport.read(16, (int) Math.min(remainingMs, READ_CHUNK_TIMEOUT_MS));
            if (chunk == null) {
                return new Result(null, banner, Failure.DISCONNECTED,
                        raw.toByteArray(), echo.toByteArray());
            }
            if (chunk.length == 0) {
                continue;
            }
            appendLimited(raw, chunk);
            echo.write(chunk, 0, chunk.length);
        }

        byte[] echoBytes = echo.toByteArray();
        if (HandshakeCodec.isConfirm(echoBytes)) {
            return new Result(version, banner, null, raw.toByteArray(),
                    java.util.Arrays.copyOf(echoBytes, 6));
        }
        return new Result(null, banner, Failure.ECHO_MISMATCH, raw.toByteArray(),
                java.util.Arrays.copyOf(echoBytes, 6));
    }

    /**
     * 在字节流里寻找第一个完整横幅。
     *
     * @return {@code [起始下标, 协议版本]}；未找到时为 {@code [-1, -1]}
     */
    static int[] findBanner(byte[] bytes) {
        for (int i = 0; i + 6 <= bytes.length; i++) {
            if (!HandshakeCodec.hasMsnPrefix(bytes, i)) {
                continue;
            }
            int tens = bytes[i + 4] & 0xFF;
            int ones = bytes[i + 5] & 0xFF;
            if (tens >= 0x30 && tens <= 0x39 && ones >= 0x30 && ones <= 0x39) {
                return new int[]{i, (tens - 0x30) * 10 + (ones - 0x30)};
            }
        }
        return new int[]{-1, -1};
    }

    private static void appendLimited(ByteArrayOutputStream raw, byte[] chunk) {
        int room = RAW_LIMIT - raw.size();
        if (room <= 0) {
            return;
        }
        raw.write(chunk, 0, Math.min(room, chunk.length));
    }
}
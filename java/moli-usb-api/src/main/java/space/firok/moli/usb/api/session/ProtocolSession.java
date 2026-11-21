package space.firok.moli.usb.api.session;

import space.firok.moli.usb.api.codec.Frame;
import space.firok.moli.usb.api.codec.HandshakeCodec;
import space.firok.moli.usb.api.codec.Hex;
import space.firok.moli.usb.api.codec.ResponseCheck;
import space.firok.moli.usb.api.codec.ResponseOutcome;
import space.firok.moli.usb.api.codec.ResponseParser;
import space.firok.moli.usb.api.error.CancelledError;
import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.HandshakeFailedError;
import space.firok.moli.usb.api.error.MalformedResponseError;
import space.firok.moli.usb.api.error.ProtocolDesynchronizedError;
import space.firok.moli.usb.api.error.TimeoutError;
import space.firok.moli.usb.api.transport.Transport;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话层：一次与单个设备的独占协议会话。
 *
 * <p>职责（spec/architecture.md「会话约束」）：</p>
 *
 * <ul>
 *   <li>握手：发送 {@code \0MSNCN}、校验回显、解析横幅中的协议版本；</li>
 *   <li>串行化：设备没有请求 ID，所有需要响应的操作在会话内串行执行，避免响应错配；</li>
 *   <li>超时、取消、断线检测：每次读取都有总超时，取消在一个轮询粒度内生效；</li>
 *   <li>失步恢复：显式声明可安全重试的操作在确认失步后清空输入并重发一次；</li>
 *   <li>生命周期：打开时占用传输地址、关闭时释放，同一设备禁止双开。</li>
 * </ul>
 *
 * <p>本类只依赖 {@link Transport} 与编解码层，不依赖任何具体串口实现，也不向上层暴露
 * 传输对象。</p>
 */
public final class ProtocolSession implements AutoCloseable {

    private final Transport transport;
    private final String deviceKey;
    private final SessionSettings settings;
    private final DeviceSessionRegistry registry;
    private final ReentrantLock operationLock = new ReentrantLock(true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Inbox inbox = new Inbox();

    private volatile int protocolVersion = -1;

    private ProtocolSession(Transport transport, String deviceKey, SessionSettings settings,
                            DeviceSessionRegistry registry) {
        this.transport = transport;
        this.deviceKey = deviceKey;
        this.settings = settings;
        this.registry = registry;
    }

    /**
     * 打开会话：占用传输地址、完成握手。
     *
     * <p>握手失败时本方法会关闭传输并释放占用，避免端口泄漏；调用方不需要再关闭
     * 已经失败的那个传输。</p>
     *
     * @throws space.firok.moli.usb.api.error.DeviceBusyError   同一设备已有其它会话
     * @throws HandshakeFailedError                    握手未能完成
     * @throws DisconnectedError                       握手期间连接断开
     * @throws CancelledError                          握手期间被取消
     */
    public static ProtocolSession open(Transport transport, SessionSettings settings,
                                       CancellationToken token) {
        return open(transport, settings, DeviceSessionRegistry.global(), token);
    }

    /** 使用指定占用登记表打开会话（测试或宿主应用注入独立登记表时使用）。 */
    public static ProtocolSession open(Transport transport, SessionSettings settings,
                                       DeviceSessionRegistry registry, CancellationToken token) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(registry, "registry");
        CancellationToken cancellationToken = CancellationToken.requireNonNull(token);

        String key = transport.address();
        if (key == null || key.isBlank()) {
            throw new space.firok.moli.usb.api.error.InvalidArgumentError("open", null,
                    "传输层必须提供非空地址，用于设备占用与错误信息");
        }
        registry.claim(key, transport);

        ProtocolSession session = new ProtocolSession(transport, key, settings, registry);
        boolean established = false;
        try {
            session.performHandshake(cancellationToken);
            established = true;
            return session;
        } finally {
            if (!established) {
                session.releaseClaim();
                session.closeTransportQuietly();
            }
        }
    }

    /** 设备横幅报告的协议版本；仅在握手完成后可用。 */
    public int protocolVersion() {
        return protocolVersion;
    }

    /** 传输地址（临时标识，仅用于错误信息与占用登记）。 */
    public String deviceKey() {
        return deviceKey;
    }

    public SessionSettings settings() {
        return settings;
    }

    /** 会话是否仍然可用。 */
    public boolean isOpen() {
        return !closed.get() && transport.isConnected();
    }

    /**
     * 发送一帧并等待 6 字节响应。
     *
     * @param request   请求帧
     * @param operation 操作名，用于错误信息
     * @param token     取消信号
     * @param retrySafe 失步后是否可以安全重发（读类操作可为 true；写类操作必须为 false）
     * @throws TimeoutError             未在超时内收齐响应
     * @throws DisconnectedError        传输断开
     * @throws MalformedResponseError   响应残缺或内容自相矛盾
     * @throws ProtocolDesynchronizedError 响应与请求不对应且无法恢复
     * @throws CancelledError           被取消
     */
    public ResponseCheck exchange(Frame request, String operation,
                                  CancellationToken token, boolean retrySafe) {
        Objects.requireNonNull(request, "request");
        requireOpen(operation);
        CancellationToken cancellationToken = CancellationToken.requireNonNull(token);

        operationLock.lock();
        try {
            int recoveries = 0;
            while (true) {
                cancellationToken.throwIfCancelled(operation, deviceKey);
                if (inbox.size() > 0) {
                    // 发送前接收缓冲有残留：上一轮的响应还没被取走，响应必然错配。
                    if (retrySafe && recoveries < settings.desyncRecoveryLimit()) {
                        recoveries++;
                        recoverFromDesync(operation, cancellationToken);
                        continue;
                    }
                    throw new ProtocolDesynchronizedError(operation, deviceKey,
                            "发送前接收缓冲残留 " + inbox.size() + " 字节："
                                    + Hex.of(inbox.peek(inbox.size())));
                }

                byte[] raw = transmitAndReceive(request, operation, cancellationToken);
                ResponseCheck check = ResponseParser.check(request, raw);
                if (check.isOk()) {
                    return check;
                }
                if (retrySafe && recoveries < settings.desyncRecoveryLimit()) {
                    recoveries++;
                    recoverFromDesync(operation, cancellationToken);
                    continue;
                }
                // 不可安全重试：清掉残留，保证后续操作不会继续错配，然后报告失步。
                clearInboxOnly();
                if (check.outcome() == ResponseOutcome.MALFORMED) {
                    throw new MalformedResponseError(operation, deviceKey, check.detail(), raw);
                }
                throw new ProtocolDesynchronizedError(operation, deviceKey, check.detail());
            }
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 发送一帧但不等待响应。
     *
     * <p>用于暂存区填充、批量像素写入这类设备不回显的帧。写失败一律视为断开。</p>
     */
    public void send(Frame frame, String operation) {
        Objects.requireNonNull(frame, "frame");
        requireOpen(operation);
        operationLock.lock();
        try {
            writeBytes(frame.toWire(), operation);
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 一次写入多个完整帧。
     *
     * <p>帧边界不变（每个帧仍是 6 字节），只是合并成一次传输写，减少 syscall。</p>
     */
    public void sendAll(List<Frame> frames, String operation) {
        Objects.requireNonNull(frames, "frames");
        if (frames.isEmpty()) {
            return;
        }
        requireOpen(operation);
        byte[] payload = new byte[frames.size() * Frame.LENGTH];
        int offset = 0;
        for (Frame frame : frames) {
            byte[] wire = frame.toWire();
            System.arraycopy(wire, 0, payload, offset, Frame.LENGTH);
            offset += Frame.LENGTH;
        }
        operationLock.lock();
        try {
            writeBytes(payload, operation);
        } finally {
            operationLock.unlock();
        }
    }

    /** 主动清空接收缓冲（丢在途与残留字节）。 */
    public void clearInput(String operation) {
        requireOpen(operation);
        operationLock.lock();
        try {
            clearTransportInput(operation);
            clearInboxOnly();
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 尽力等待设备的确认响应，但把超时视为「设备没有回执」而不是失败。
     *
     * <p>仅用于规范尚未确认回执语义的操作（显示动作、像素流提交）。它仍然<b>不会</b>
     * 把任意非空数据当成成功：只要收到字节，就必须构成完整且与请求对应的 6 字节帧，
     * 否则抛 {@code MalformedResponseError} / {@code ProtocolDesynchronizedError}。
     * 断线照常抛 {@code DisconnectedError}。</p>
     *
     * <p>规范确认回执规则后，调用点应改用 {@link #exchange} 并删除本方法。</p>
     *
     * @return 收到的完整响应帧；超时未收到时为空
     */
    public java.util.Optional<ResponseCheck> awaitAckOrIgnore(Frame request, String operation,
                                                              CancellationToken token) {
        Objects.requireNonNull(request, "request");
        requireOpen(operation);
        CancellationToken cancellationToken = CancellationToken.requireNonNull(token);
        operationLock.lock();
        try {
            byte[] raw;
            try {
                raw = transmitAndReceive(request, operation, cancellationToken);
            } catch (TimeoutError noAck) {
                clearInboxOnly();
                return java.util.Optional.empty();
            }
            ResponseCheck check = ResponseParser.check(request, raw);
            if (!check.isOk()) {
                clearInboxOnly();
                if (check.outcome() == ResponseOutcome.MALFORMED) {
                    throw new MalformedResponseError(operation, deviceKey, check.detail(), raw);
                }
                throw new ProtocolDesynchronizedError(operation, deviceKey, check.detail());
            }
            return java.util.Optional.of(check);
        } finally {
            operationLock.unlock();
        }
    }

    /** 关闭会话：释放占用并关闭传输，幂等。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        releaseClaim();
        clearInboxOnly();
        closeTransportQuietly();
    }

    // ---------------------------------------------------------------- 握手

    private void performHandshake(CancellationToken token) {
        HandshakeFailure lastFailure = HandshakeFailure.NO_BANNER;
        String lastDetail = null;

        for (int attempt = 1; attempt <= settings.handshakeAttempts(); attempt++) {
            token.throwIfCancelled("handshake", deviceKey);
            if (attempt > 1) {
                // 重试前丢掉上一轮可能残留的横幅或半帧。
                clearTransportInputKeepingState("handshake");
            }
            long deadline = System.nanoTime() + settings.handshakeTimeout().toNanos();
            try {
                OptionalInt banner = awaitBanner(deadline, token);
                if (banner.isEmpty()) {
                    lastFailure = HandshakeFailure.NO_BANNER;
                    lastDetail = "在 " + settings.handshakeTimeout().toMillis()
                            + " ms 内没有收到 \\0MSN 横幅";
                    continue;
                }
                writeBytes(HandshakeCodec.encodeChallenge(), "handshake");
                ConfirmResult confirm = awaitConfirm(deadline, token);
                if (confirm.confirmed()) {
                    protocolVersion = banner.getAsInt();
                    return;
                }
                lastFailure = confirm.failure();
                lastDetail = confirm.detail();
            } catch (DisconnectedError disconnected) {
                throw disconnected;
            }
        }

        throw new HandshakeFailedError("handshake", deviceKey,
                "握手失败（" + settings.handshakeAttempts() + " 次尝试）："
                        + lastFailure + (lastDetail == null ? "" : " — " + lastDetail));
    }

    /** 在截止时间内等待横幅；返回协议版本。 */
    private OptionalInt awaitBanner(long deadlineNanos, CancellationToken token) {
        while (true) {
            token.throwIfCancelled("handshake", deviceKey);
            int found = inbox.findBanner();
            if (found >= 0) {
                byte[] banner = inbox.peek(found + 6);
                byte[] slice = new byte[6];
                System.arraycopy(banner, found, slice, 0, 6);
                inbox.consume(found + 6);
                OptionalInt version = HandshakeCodec.tryParseBanner(slice);
                if (version.isPresent()) {
                    return version;
                }
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return OptionalInt.empty();
            }
            byte[] chunk = readOnce(remainingNanos, token, "handshake", true);
            if (chunk == null) {
                throw new DisconnectedError("handshake", deviceKey,
                        "握手期间传输断开（未收到横幅）");
            }
            if (chunk.length > 0) {
                inbox.append(chunk);
            }
        }
    }

    /** 在截止时间内等待 {@code \0MSNCN} 确认帧；广播横幅不算失败。 */
    private ConfirmResult awaitConfirm(long deadlineNanos, CancellationToken token) {
        while (true) {
            token.throwIfCancelled("handshake", deviceKey);
            if (inbox.size() >= 6) {
                byte[] head = inbox.peek(6);
                if (HandshakeCodec.isConfirm(head)) {
                    inbox.consume(6);
                    return ConfirmResult.ok();
                }
                if (HandshakeCodec.isBanner(head)) {
                    // 设备仍在广播，继续等确认帧。
                    inbox.consume(6);
                    continue;
                }
                inbox.consume(6);
                return ConfirmResult.failed(HandshakeFailure.WRONG_CONFIRM,
                        "收到非握手响应 " + Hex.of(head, 0, 6));
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return ConfirmResult.failed(HandshakeFailure.NO_CONFIRM,
                        "发送 \\0MSNCN 后未在超时内收到确认");
            }
            byte[] chunk = readOnce(remainingNanos, token, "handshake", true);
            if (chunk == null) {
                throw new DisconnectedError("handshake", deviceKey,
                        "握手期间传输断开（未收到 \\0MSNCN 确认）");
            }
            if (chunk.length > 0) {
                inbox.append(chunk);
            }
        }
    }

    private record ConfirmResult(boolean confirmed, HandshakeFailure failure, String detail) {

        static ConfirmResult ok() {
            return new ConfirmResult(true, null, null);
        }

        static ConfirmResult failed(HandshakeFailure failure, String detail) {
            return new ConfirmResult(false, failure, detail);
        }
    }

    // ------------------------------------------------------------ 请求-响应

    private byte[] transmitAndReceive(Frame request, String operation, CancellationToken token) {
        writeBytes(request.toWire(), operation);

        long deadline = System.nanoTime() + settings.commandTimeout().toNanos();
        while (inbox.size() < Frame.LENGTH) {
            token.throwIfCancelled(operation, deviceKey);
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                int partial = inbox.size();
                if (partial == 0) {
                    throw new TimeoutError(operation, deviceKey,
                            "在 " + settings.commandTimeout().toMillis() + " ms 内未收到响应"
                                    + "（请求 " + request.hex() + "）");
                }
                byte[] raw = inbox.takeAll();
                return raw;
            }
            int wanted = Frame.LENGTH - inbox.size();
            byte[] chunk = readOnce(remainingNanos, token, operation, false);
            if (chunk == null) {
                throw new DisconnectedError(operation, deviceKey,
                        "等待响应时传输断开（请求 " + request.hex() + "）");
            }
            if (chunk.length > 0) {
                inbox.append(chunk);
            }
        }

        byte[] frame = inbox.take(Frame.LENGTH);

        // 多出来的字节说明本轮响应后面还跟着别的数据：上一轮残留或设备主动推送。
        byte[] residue = readOnce(0, token, operation, true);
        if (residue != null && residue.length > 0) {
            byte[] combined = new byte[frame.length + residue.length];
            System.arraycopy(frame, 0, combined, 0, frame.length);
            System.arraycopy(residue, 0, combined, frame.length, residue.length);
            return combined;
        }
        return frame;
    }

    /** 读取一次。返回 null 表示断开；返回空数组表示本次没读到数据（超时或立即返回）。 */
    private byte[] readOnce(long timeoutNanos, CancellationToken token,
                            String operation, boolean allowZeroTimeout) {
        token.throwIfCancelled(operation, deviceKey);
        int maxBytes = Math.max(1, Frame.LENGTH);
        int timeoutMs;
        if (timeoutNanos <= 0) {
            if (!allowZeroTimeout) {
                return new byte[0];
            }
            timeoutMs = 0;
        } else {
            long millis = Math.max(1, (timeoutNanos + 999_999) / 1_000_000);
            timeoutMs = (int) Math.min(Integer.MAX_VALUE, millis);
            timeoutMs = Math.min(timeoutMs, settings.effectivePollMillis());
        }
        try {
            return transport.read(maxBytes, timeoutMs);
        } catch (IOException e) {
            throw new DisconnectedError(operation, deviceKey, "传输读失败: " + e.getMessage(), e);
        }
    }

    private void writeBytes(byte[] payload, String operation) {
        if (!transport.isConnected()) {
            throw new DisconnectedError(operation, deviceKey, "传输已断开，无法写出");
        }
        try {
            transport.write(payload);
        } catch (IOException e) {
            throw new DisconnectedError(operation, deviceKey, "传输写失败: " + e.getMessage(), e);
        }
    }

    private void clearTransportInput(String operation) {
        try {
            transport.clearInput();
        } catch (IOException e) {
            throw new DisconnectedError(operation, deviceKey, "清空输入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 失步恢复：清空传输缓冲与本层残留，并尽力排空在途字节。
     *
     * <p>不睡眠、不无期限等待：排空循环最多持续一个命令超时的四分之一，且每次读都
     * 受取消信号约束。</p>
     */
    private void recoverFromDesync(String operation, CancellationToken token) {
        clearTransportInput(operation);
        clearInboxOnly();

        long deadline = System.nanoTime()
                + Math.max(1, settings.commandTimeout().toNanos() / 4);
        int iterations = 0;
        while (iterations++ < 32) {
            token.throwIfCancelled(operation, deviceKey);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            byte[] drained = readOnce(remaining, token, operation, true);
            if (drained == null) {
                throw new DisconnectedError(operation, deviceKey, "失步恢复期间传输断开");
            }
            if (drained.length == 0) {
                break;
            }
        }
        clearInboxOnly();
    }

    private void clearTransportInputKeepingState(String operation) {
        try {
            transport.clearInput();
        } catch (IOException e) {
            throw new DisconnectedError(operation, deviceKey,
                    "清空输入失败: " + e.getMessage(), e);
        }
        clearInboxOnly();
    }

    private void requireOpen(String operation) {
        if (closed.get()) {
            throw new DisconnectedError(operation, deviceKey, "会话已关闭");
        }
        if (!transport.isConnected()) {
            throw new DisconnectedError(operation, deviceKey, "传输已断开");
        }
    }

    private void clearInboxOnly() {
        inbox.clear();
    }

    private void closeTransportQuietly() {
        try {
            transport.close();
        } catch (IOException ignored) {
            // 关闭失败不再掩盖原始错误。
        }
    }

    private void releaseClaim() {
        registry.release(deviceKey, transport);
    }

    /**
     * 会话内部的接收缓冲。
     *
     * <p>作用是把 {@link Transport#read(int, int)} 返回的任意长度片段拼成 6 字节帧，
     * 并保留跨操作残留，使「响应多字节 / 少字节」能被显式判定，而不是被静默丢弃。</p>
     */
    private static final class Inbox {

        private byte[] buffer = new byte[128];
        private int start;
        private int end;

        int size() {
            return end - start;
        }

        void append(byte[] bytes) {
            ensureCapacity(size() + bytes.length);
            System.arraycopy(bytes, 0, buffer, end, bytes.length);
            end += bytes.length;
        }

        void clear() {
            start = 0;
            end = 0;
        }

        byte[] peek(int count) {
            int available = Math.min(count, size());
            byte[] out = new byte[available];
            System.arraycopy(buffer, start, out, 0, available);
            return out;
        }

        void consume(int count) {
            start = Math.min(end, start + Math.max(0, count));
            if (start == end) {
                start = 0;
                end = 0;
            }
        }

        byte[] take(int count) {
            byte[] out = peek(count);
            consume(count);
            return out;
        }

        byte[] takeAll() {
            return take(size());
        }

        /** 查找第一个完整横幅的起始下标；未找到返回 -1。 */
        int findBanner() {
            int limit = size() - 6;
            for (int i = 0; i <= limit; i++) {
                int base = start + i;
                if (buffer[base] != HandshakeCodec.MSN_PREFIX[0]
                        || buffer[base + 1] != HandshakeCodec.MSN_PREFIX[1]
                        || buffer[base + 2] != HandshakeCodec.MSN_PREFIX[2]
                        || buffer[base + 3] != HandshakeCodec.MSN_PREFIX[3]) {
                    continue;
                }
                int tens = buffer[base + 4] & 0xFF;
                int ones = buffer[base + 5] & 0xFF;
                if (tens >= 0x30 && tens <= 0x39 && ones >= 0x30 && ones <= 0x39) {
                    return i;
                }
            }
            return -1;
        }

        private void ensureCapacity(int required) {
            if (required <= buffer.length) {
                return;
            }
            if (start > 0) {
                int live = size();
                byte[] compacted = new byte[Math.max(required, live * 2)];
                System.arraycopy(buffer, start, compacted, 0, live);
                buffer = compacted;
                start = 0;
                end = live;
                return;
            }
            byte[] grown = new byte[Math.max(required, buffer.length * 2)];
            System.arraycopy(buffer, 0, grown, 0, end);
            buffer = grown;
        }
    }
}
package space.firok.moli.usb.api.session;

import java.time.Duration;
import java.util.Objects;

/**
 * 会话层：一次会话的超时与节奏配置。
 *
 * <p>默认值刻意偏保守：规范只记录设备约每 100 ms 广播一次握手横幅，命令响应时间没有
 * 实测数据（spec/verification.md）。等待时间必须可配置，而不是散落在各方法里。</p>
 *
 * @param handshakeTimeout     握手总超时（含等待横幅与确认回显）
 * @param handshakeAttempts    握手重试次数（含首次）
 * @param commandTimeout       单条命令等待响应的超时
 * @param pollIntervalMs       等待期间单次读阻塞的粒度，过大会降低取消响应速度
 * @param desyncRecoveryLimit  每次操作允许的失步恢复次数（超出即报错）
 */
public record SessionSettings(
        Duration handshakeTimeout,
        int handshakeAttempts,
        Duration commandTimeout,
        int pollIntervalMs,
        int desyncRecoveryLimit) {

    /** 保守默认值。 */
    public static SessionSettings defaults() {
        return new SessionSettings(
                Duration.ofSeconds(2),
                3,
                Duration.ofMillis(800),
                25,
                1);
    }

    public SessionSettings {
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (handshakeTimeout.isNegative() || handshakeTimeout.isZero()) {
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        }
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        if (handshakeAttempts <= 0) {
            throw new IllegalArgumentException("handshakeAttempts must be positive");
        }
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive");
        }
        if (desyncRecoveryLimit < 0) {
            throw new IllegalArgumentException("desyncRecoveryLimit must not be negative");
        }
    }

    /** 单次命令读阻塞粒度的毫秒数，且不超过命令超时。 */
    public int effectivePollMillis() {
        return (int) Math.min(pollIntervalMs, Math.max(1, commandTimeout.toMillis()));
    }

    public SessionSettings withCommandTimeout(Duration timeout) {
        return new SessionSettings(handshakeTimeout, handshakeAttempts, timeout,
                pollIntervalMs, desyncRecoveryLimit);
    }

    public SessionSettings withHandshakeAttempts(int attempts) {
        return new SessionSettings(handshakeTimeout, attempts, commandTimeout,
                pollIntervalMs, desyncRecoveryLimit);
    }

    public SessionSettings withPollIntervalMs(int millis) {
        return new SessionSettings(handshakeTimeout, handshakeAttempts, commandTimeout,
                millis, desyncRecoveryLimit);
    }
}
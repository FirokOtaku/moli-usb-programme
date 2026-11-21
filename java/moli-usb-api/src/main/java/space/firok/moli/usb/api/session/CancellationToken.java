package space.firok.moli.usb.api.session;

import space.firok.moli.usb.api.error.CancelledError;

import java.util.Objects;

/**
 * 会话层：协作式取消信号。
 *
 * <p>阻塞读的单次粒度受 {@link SessionSettings#pollIntervalMs()} 约束，因此取消
 * 最迟在一个轮询粒度后被观察到。取消是一次性的：一旦置位，当前会话的后续操作立即
 * 以 {@link CancelledError} 失败，直到调用方明确 {@link #reset()}。</p>
 */
public final class CancellationToken {

    private volatile boolean cancelled;
    private volatile String reason;

    /** 永不取消的令牌。 */
    public static CancellationToken none() {
        return new CancellationToken();
    }

    /** 已置位的令牌，用于「立即取消」场景与测试。 */
    public static CancellationToken cancelled(String reason) {
        CancellationToken token = new CancellationToken();
        token.cancel(reason);
        return token;
    }

    /** 置位取消；重复调用保留首次原因。 */
    public void cancel(String reason) {
        if (!cancelled) {
            this.reason = reason == null ? "调用方取消" : reason;
            this.cancelled = true;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** 取消原因；未取消时为 null。 */
    public String reason() {
        return reason;
    }

    /** 清除取消状态，供会话复用。 */
    public void reset() {
        cancelled = false;
        reason = null;
    }

    /** 若已取消则抛出 {@link CancelledError}。 */
    public void throwIfCancelled(String operation, String deviceId) {
        if (cancelled) {
            throw new CancelledError(operation, deviceId,
                    "操作已取消" + (reason == null ? "" : "：" + reason));
        }
    }

    static CancellationToken requireNonNull(CancellationToken token) {
        return Objects.requireNonNull(token, "cancellationToken");
    }
}
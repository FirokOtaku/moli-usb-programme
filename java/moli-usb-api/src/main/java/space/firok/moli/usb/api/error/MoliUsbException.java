package space.firok.moli.usb.api.error;

import java.util.Objects;
import java.util.Optional;

/**
 * 统一错误基类：spec/architecture.md 错误分类的 Java 投影。
 *
 * <p>错误保留操作名、设备 ID 和底层原因，但不要求调用方解析异常字符串：
 * 分类用 {@code instanceof} 判定，结构化字段用访问器读取。</p>
 *
 * <p>错误类型名即 spec 中的分类名（PascalCase 对应 Java 的类名约定）：
 * DeviceNotFound、DeviceBusy、Disconnected、HandshakeFailed、ProtocolMismatch、
 * ProtocolDesynchronized、Timeout、Cancelled、UnsupportedCapability、
 * InvalidArgument、OutOfBounds、StorageCapacityExceeded、StorageOperationFailed、
 * MalformedResponse。</p>
 */
public abstract class MoliUsbException extends RuntimeException {

    private final String operation;
    private final String deviceId;
    private final Throwable cause;

    protected MoliUsbException(String operation, String deviceId, String message, Throwable cause) {
        super(message, cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.deviceId = deviceId;
        this.cause = cause;
    }

    /** 产生错误的领域操作名，如 "openDevice"、"fillRect"。 */
    public String operation() {
        return operation;
    }

    /** 目标设备 ID（若会话已建立）；设备未打开时可能为 null。 */
    public Optional<String> deviceId() {
        return Optional.ofNullable(deviceId);
    }

    @Override
    public synchronized Throwable getCause() {
        return cause;
    }
}

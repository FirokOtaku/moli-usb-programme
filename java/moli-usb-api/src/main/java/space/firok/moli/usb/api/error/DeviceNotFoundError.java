package space.firok.moli.usb.api.error;

import java.util.Objects;

/**
 * 设备未找到。enum 分类 {@code DeviceNotFound}：枚举或打开目标设备失败。
 */
public class DeviceNotFoundError extends MoliUsbException {

    public DeviceNotFoundError(String operation, String deviceId, String message, Throwable cause) {
        super(operation, deviceId, message, cause);
    }

    public DeviceNotFoundError(String operation, String deviceId, String message) {
        this(operation, deviceId, message, null);
    }

    public DeviceNotFoundError(String operation, String deviceId, Throwable cause) {
        this(operation, deviceId, messageFor(cause), cause);
    }

    private static String messageFor(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        return "设备未找到：" + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
    }
}

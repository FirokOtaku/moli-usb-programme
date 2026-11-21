package space.firok.moli.usb.api.error;

/**
 * 超时。enum 分类 {@code Timeout}：读取未在总超时内完成。
 */
public class TimeoutError extends MoliUsbException {

    public TimeoutError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public TimeoutError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "操作超时：" + cause.getClass().getSimpleName(), cause);
    }
}

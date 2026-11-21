package space.firok.moli.usb.api.error;

/**
 * 操作被取消。enum 分类 {@code Cancelled}：调用方通过取消信号中止操作。
 */
public class CancelledError extends MoliUsbException {

    public CancelledError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public CancelledError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "操作被取消：" + cause.getClass().getSimpleName(), cause);
    }
}

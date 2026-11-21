package space.firok.moli.usb.api.error;

/**
 * 设备忙：另一个操作正在占用同一设备会话。enum 分类 {@code DeviceBusy}。
 */
public class DeviceBusyError extends MoliUsbException {

    public DeviceBusyError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public DeviceBusyError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "设备忙：" + cause.getClass().getSimpleName(), cause);
    }
}

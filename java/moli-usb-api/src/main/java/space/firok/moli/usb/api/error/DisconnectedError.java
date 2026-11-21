package space.firok.moli.usb.api.error;

/**
 * 设备已断开。enum 分类 {@code Disconnected}：传输层连接丢失。
 */
public class DisconnectedError extends MoliUsbException {

    public DisconnectedError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public DisconnectedError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "设备已断开：" + cause.getClass().getSimpleName(), cause);
    }

    /** 保留可读原因同时带上底层故障，便于上层区分「超时后断开」与「写失败」。 */
    public DisconnectedError(String operation, String deviceId, String message, Throwable cause) {
        super(operation, deviceId, message, cause);
    }
}

package space.firok.moli.usb.api.error;

/**
 * 协议失步。enum 分类 {@code ProtocolDesynchronized}：响应与预期命令不对应，
 * 多字节响应未收齐等。
 */
public class ProtocolDesynchronizedError extends MoliUsbException {

    public ProtocolDesynchronizedError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public ProtocolDesynchronizedError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "协议失步：" + cause.getClass().getSimpleName(), cause);
    }
}

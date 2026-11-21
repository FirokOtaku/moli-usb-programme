package space.firok.moli.usb.api.error;

/**
 * 握手失败。enum 分类 {@code HandshakeFailed}：发送 {@code \0MSNCN} 后未收到
 * 对应的 {@code \0MSNCN} 响应。
 */
public class HandshakeFailedError extends MoliUsbException {

    public HandshakeFailedError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public HandshakeFailedError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "握手失败：" + cause.getClass().getSimpleName(), cause);
    }
}

package space.firok.moli.usb.api.error;

/**
 * 协议不匹配。enum 分类 {@code ProtocolMismatch}：设备报告的协议版本/能力与
 * 库预期不兼容。
 */
public class ProtocolMismatchError extends MoliUsbException {

    public ProtocolMismatchError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public ProtocolMismatchError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "协议不匹配：" + cause.getClass().getSimpleName(), cause);
    }
}

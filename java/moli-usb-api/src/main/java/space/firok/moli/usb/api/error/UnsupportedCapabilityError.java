package space.firok.moli.usb.api.error;

/**
 * 不支持的能力。enum 分类 {@code UnsupportedCapability}：设备不声明对应能力。
 */
public class UnsupportedCapabilityError extends MoliUsbException {

    public UnsupportedCapabilityError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public UnsupportedCapabilityError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "不支持的能力：" + cause.getClass().getSimpleName(), cause);
    }
}

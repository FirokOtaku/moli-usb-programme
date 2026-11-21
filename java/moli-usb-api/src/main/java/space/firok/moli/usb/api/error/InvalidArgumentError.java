package space.firok.moli.usb.api.error;

/**
 * 无效参数。enum 分类 {@code InvalidArgument}：调用方提供的参数不合法。
 */
public class InvalidArgumentError extends MoliUsbException {

    public InvalidArgumentError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public InvalidArgumentError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "无效参数：" + cause.getClass().getSimpleName(), cause);
    }
}

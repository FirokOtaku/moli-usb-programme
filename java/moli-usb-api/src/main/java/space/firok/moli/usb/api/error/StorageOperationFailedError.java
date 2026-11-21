package space.firok.moli.usb.api.error;

/**
 * 存储操作失败。enum 分类 {@code StorageOperationFailed}：Flash 擦除/写入/读取在
 * 设备上返回失败或响应异常。
 */
public class StorageOperationFailedError extends MoliUsbException {

    public StorageOperationFailedError(String operation, String deviceId, String message) {
        super(operation, deviceId, message, null);
    }

    public StorageOperationFailedError(String operation, String deviceId, Throwable cause) {
        super(operation, deviceId, "存储操作失败：" + cause.getClass().getSimpleName(), cause);
    }
}

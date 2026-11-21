package space.firok.moli.usb.api.error;

/**
 * 存储容量不足。enum 分类 {@code StorageCapacityExceeded}：目标数据大于可用容量。
 */
public class StorageCapacityExceededError extends MoliUsbException {

    private final long neededBytes;
    private final long availableBytes;

    public StorageCapacityExceededError(String operation, String deviceId, String message,
                                        long neededBytes, long availableBytes) {
        super(operation, deviceId, message, null);
        this.neededBytes = neededBytes;
        this.availableBytes = availableBytes;
    }

    public StorageCapacityExceededError(String operation, String deviceId, String message,
                                        long neededBytes, long availableBytes, Throwable cause) {
        super(operation, deviceId, message, cause);
        this.neededBytes = neededBytes;
        this.availableBytes = availableBytes;
    }

    public long neededBytes() {
        return neededBytes;
    }

    public long availableBytes() {
        return availableBytes;
    }
}

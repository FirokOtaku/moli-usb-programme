package space.firok.moli.usb.api.error;

/**
 * 越界。enum 分类 {@code OutOfBounds}：地址/页超出设备容量或存储可写范围。
 */
public class OutOfBoundsError extends MoliUsbException {

    private final long startPage;
    private final long pageCount;
    private final long totalPages;

    public OutOfBoundsError(String operation, String deviceId, String message,
                            long startPage, long pageCount, long totalPages) {
        super(operation, deviceId, message, null);
        this.startPage = startPage;
        this.pageCount = pageCount;
        this.totalPages = totalPages;
    }

    public OutOfBoundsError(String operation, String deviceId, String message,
                            long startPage, long pageCount, long totalPages, Throwable cause) {
        super(operation, deviceId, message, cause);
        this.startPage = startPage;
        this.pageCount = pageCount;
        this.totalPages = totalPages;
    }

    public long startPage() {
        return startPage;
    }

    public long pageCount() {
        return pageCount;
    }

    public long totalPages() {
        return totalPages;
    }
}

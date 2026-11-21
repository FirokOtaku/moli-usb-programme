package space.firok.moli.usb.api.domain;

/**
 * 领域层：外部存储页区间越界。
 *
 * <p>领域层的值类型错误，属于 spec/architecture.md 错误分类中的
 * {@code OutOfBounds} 的数据面投影；公共 API 抛出的统一错误类型见
 * {@code space.firok.moli.usb.api.error.OutOfBoundsError}。</p>
 */
public class OutOfBoundsException extends RuntimeException {

    private final StorageGeometry geometry;
    private final long startPage;
    private final long pageCount;

    public OutOfBoundsException(StorageGeometry geometry, long startPage, long pageCount) {
        super("page range [" + startPage + ", " + (startPage + pageCount) + ") exceeds total pages "
                + geometry.totalPages());
        this.geometry = geometry;
        this.startPage = startPage;
        this.pageCount = pageCount;
    }

    public StorageGeometry geometry() {
        return geometry;
    }

    public long startPage() {
        return startPage;
    }

    public long pageCount() {
        return pageCount;
    }
}

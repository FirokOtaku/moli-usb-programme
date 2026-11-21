package space.firok.moli.usb.api.domain;

import java.util.Objects;

/**
 * 领域层：外部存储几何。
 *
 * <p>对应 spec/architecture.md 中的 {@code StorageGeometry}：存储类型、容量、
 * 页大小、擦除粒度和可写范围。用于存储写入前的容量与地址范围检查。</p>
 *
 * <p>默认值来自开发指南对 P25D80 串行 Flash 的记录（1024 KiB、256 字节页、
 * 4096 页）；实际以设备 {@code Flash_Info} 自描述为准。</p>
 */
public record StorageGeometry(
        Type type,
        long capacityBytes,
        int pageSize,
        long eraseGranularityBytes,
        long totalPages) {

    /** 默认串行 Flash 几何：P25D80，1024 KiB，页 256 B，擦除粒度 4 KiB。 */
    public static StorageGeometry defaultSerialFlash() {
        return new StorageGeometry(Type.SERIAL_FLASH, 1024 * 1024, 256, 4 * 1024, 4096);
    }

    public StorageGeometry {
        Objects.requireNonNull(type, "type");
        if (capacityBytes <= 0 || pageSize <= 0 || eraseGranularityBytes <= 0 || totalPages <= 0) {
            throw new IllegalArgumentException("storage geometry must be positive: " + this);
        }
        if (capacityBytes != pageSize * totalPages) {
            throw new IllegalArgumentException(
                    "capacity must equal pageSize * totalPages: " + this);
        }
    }

    /** 存储类型。 */
    public enum Type {
        SERIAL_FLASH,
        NAND
    }

    /** 由字节数换算页数，非整数页向上取整。 */
    public long pagesFor(long byteLength) {
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must be non-negative");
        }
        return (byteLength + pageSize - 1) / pageSize;
    }

    /** 检查页区间是否在容量范围内。 */
    public void checkPageRange(long startPage, long pageCount) {
        if (startPage < 0 || pageCount <= 0) {
            throw new IllegalArgumentException(
                    "invalid page range: start=" + startPage + ", count=" + pageCount);
        }
        if (startPage + pageCount > totalPages) {
            throw new OutOfBoundsException(this, startPage, pageCount);
        }
    }
}

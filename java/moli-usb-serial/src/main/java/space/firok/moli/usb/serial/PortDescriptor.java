package space.firok.moli.usb.serial;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 串口枚举结果：只含元数据，不含任何打开的端口句柄。
 *
 * <p>对应 spec/architecture.md 的「枚举只读元数据，不打开端口」与 spec/verification.md
 * 的 P0 项「记录 USB VID/PID/描述符并验证能否在不打开所有串口的情况下筛出候选设备」。</p>
 *
 * <p>VID/PID 是<b>待实机确认</b>的事实：本仓库的 spec 目前没有记录 MINI/LITE/PRO 的
 * USB 标识，因此本类型只如实搬运 jSerialComm 读到的值，缺省用 {@link Optional#empty()}，
 * 绝不填猜测值。</p>
 *
 * @param portName     系统端口名（Windows 上形如 {@code COM3}），兼作传输层地址
 * @param description  端口描述（可能为空字符串）
 * @param manufacturer 厂商字符串（可能为空字符串）
 * @param serialNumber USB 序列号（可能为空字符串）
 * @param vendorId     USB VID，未识别时为 {@link Optional#empty()}
 * @param productId    USB PID，未识别时为 {@link Optional#empty()}
 */
public record PortDescriptor(
        String portName,
        String description,
        String manufacturer,
        String serialNumber,
        Optional<Integer> vendorId,
        Optional<Integer> productId) {

    public PortDescriptor {
        Objects.requireNonNull(portName, "portName");
        if (portName.isBlank()) {
            throw new IllegalArgumentException("portName must not be blank");
        }
        description = description == null ? "" : description;
        manufacturer = manufacturer == null ? "" : manufacturer;
        serialNumber = serialNumber == null ? "" : serialNumber;
        Objects.requireNonNull(vendorId, "vendorId");
        Objects.requireNonNull(productId, "productId");
    }

    /** 只有端口名的描述（测试与「无元数据」场景使用）。 */
    public static PortDescriptor ofName(String portName) {
        return new PortDescriptor(portName, "", "", "",
                Optional.empty(), Optional.empty());
    }

    /**
     * VID/PID 的可读形式，如 {@code 1A86:7523}；任一缺失时为 {@code —}。
     *
     * <p>十六进制大写、零填充到 4 位，与 USB 习惯一致；用于人工对照
     * {@code spec/verification.md} 的实机记录。</p>
     */
    public String usbIdText() {
        if (vendorId.isEmpty() || productId.isEmpty()) {
            return "—";
        }
        return String.format(Locale.ROOT, "%04X:%04X", vendorId.get(), productId.get());
    }

    /** VID 的十六进制文本；缺失时为 {@code —}。 */
    public String vendorIdText() {
        return vendorId.map(value -> String.format(Locale.ROOT, "%04X", value)).orElse("—");
    }

    /** PID 的十六进制文本；缺失时为 {@code —}。 */
    public String productIdText() {
        return productId.map(value -> String.format(Locale.ROOT, "%04X", value)).orElse("—");
    }

    /** 描述缺失时的占位文本。 */
    public String descriptionText() {
        return description.isBlank() ? "（无描述）" : description;
    }
}
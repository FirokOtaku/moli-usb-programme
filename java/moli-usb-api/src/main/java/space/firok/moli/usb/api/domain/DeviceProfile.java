package space.firok.moli.usb.api.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 领域层：设备 profile。
 *
 * <p>MINI、LITE、PRO 是已知 profile，不是封闭枚举。未知型号只要能完成握手并
 * 报告必要能力，即可作为通用 MSU2 设备使用。profile 主要提供串口参数与显示
 * 页地址编码偏好的初始猜测，最终以设备握手和自描述结果为准。</p>
 *
 * <p>串口参数不可只由型号名称决定，应基于可配置 profile 或已确认的 USB/固件
 * 身份（spec/architecture.md 兼容策略）。因此 profile 不参与设备识别判定，
 * 仅在调用方明确选择或通过 USB/固件身份确认后提供串口配置。</p>
 */
public final class DeviceProfile {

    /** 已知 profile 的注册名。 */
    public static final String MINI = "MINI";
    public static final String LITE = "LITE";
    public static final String PRO = "PRO";
    /** 通用 MSU2 设备（未知型号但握手成功）。 */
    public static final String GENERIC = "GENERIC";

    /** 旧版演示串口参数：19200 baud，无显式流控。8N1 为 pyserial 默认推定。 */
    public static SerialSettings LEGACY_SERIAL = new SerialSettings(19200, false, false);

    /** LITE V1.2 / PRO V1.0 演示串口参数：921600 baud，rtscts=true，xonxoff=false。 */
    public static SerialSettings HIGH_SPEED_SERIAL = new SerialSettings(921600, true, false);

    private final String name;
    private final SerialSettings serial;
    private final Optional<DisplayGeometry> display;
    private final Optional<StorageGeometry> storage;
    private final DeviceCapabilities capabilities;

    private DeviceProfile(
            String name,
            SerialSettings serial,
            Optional<DisplayGeometry> display,
            Optional<StorageGeometry> storage,
            DeviceCapabilities capabilities) {
        this.name = Objects.requireNonNull(name, "name");
        this.serial = Objects.requireNonNull(serial, "serial");
        this.display = Objects.requireNonNull(display, "display");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** MINI profile：最小规格，带一个按键（ADC 9 适配）。 */
    public static DeviceProfile mini() {
        return new DeviceProfile(
                MINI,
                LEGACY_SERIAL,
                Optional.of(new DisplayGeometry(160, 80,
                        DisplayGeometry.Origin.TOP_LEFT,
                        List.of(DisplayGeometry.Orientation.NORMAL, DisplayGeometry.Orientation.ROTATE_180),
                        DisplayGeometry.PixelFormat.RGB565)),
                Optional.of(StorageGeometry.defaultSerialFlash()),
                DeviceCapabilities.of(true, true, false, false, false, true));
    }

    /** LITE profile：1.47 寸，无按键。 */
    public static DeviceProfile lite() {
        return new DeviceProfile(
                LITE,
                HIGH_SPEED_SERIAL,
                Optional.of(new DisplayGeometry(160, 80,
                        DisplayGeometry.Origin.TOP_LEFT,
                        List.of(DisplayGeometry.Orientation.NORMAL, DisplayGeometry.Orientation.ROTATE_180),
                        DisplayGeometry.PixelFormat.RGB565)),
                Optional.of(StorageGeometry.defaultSerialFlash()),
                DeviceCapabilities.of(true, true, true, false, false, false));
    }

    /** PRO profile：2.8 寸，无按键，含 NAND 与背光。 */
    public static DeviceProfile pro() {
        return new DeviceProfile(
                PRO,
                HIGH_SPEED_SERIAL,
                Optional.of(new DisplayGeometry(320, 172,
                        DisplayGeometry.Origin.TOP_LEFT,
                        List.of(DisplayGeometry.Orientation.NORMAL, DisplayGeometry.Orientation.ROTATE_180),
                        DisplayGeometry.PixelFormat.RGB565)),
                Optional.of(StorageGeometry.defaultSerialFlash()),
                DeviceCapabilities.of(true, true, true, true, true, false));
    }

    /**
     * 通用 profile：未知型号。串口参数未知（调用方必须自行确认），几何与存储
     * 依赖设备自描述报告。
     */
    public static DeviceProfile generic() {
        return new DeviceProfile(
                GENERIC,
                LEGACY_SERIAL,
                Optional.empty(),
                Optional.empty(),
                DeviceCapabilities.of(true, true, false, false, false, false));
    }

    /**
     * 由已知注册名获取 profile；未知名返回 {@link #generic()}。
     * 名称大小写不敏感。
     */
    public static DeviceProfile byName(String name) {
        Objects.requireNonNull(name, "name");
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case MINI -> mini();
            case LITE -> lite();
            case PRO -> pro();
            default -> generic();
        };
    }

    public String name() {
        return name;
    }

    public SerialSettings serial() {
        return serial;
    }

    public Optional<DisplayGeometry> display() {
        return display;
    }

    public Optional<StorageGeometry> storage() {
        return storage;
    }

    public DeviceCapabilities capabilities() {
        return capabilities;
    }

    /**
     * 显示彩色素材时，Flash 页地址在 6 字节 action 帧中的字节顺序偏好。
     * 旧源码/LITE 使用 16 位页地址（最后一字节置零）；PRO 使用“中、低、高”顺序。
     * 语言实现必须通过设备 profile 选择编码，不能只按一个通用整数写出。
     */
    public ColorPageAddressOrder colorPageAddressOrder() {
        return switch (name) {
            case PRO -> ColorPageAddressOrder.MID_LOW_HIGH;
            default -> ColorPageAddressOrder.LOW_HIGH_ZERO;
        };
    }

    /** 彩色素材页地址编码顺序。 */
    public enum ColorPageAddressOrder {
        /** {@code page_mid, page_low, 0}：16 位页地址，高、低在前，末字节置零。 */
        LOW_HIGH_ZERO,
        /** {@code page_mid, page_low, page_high}：PRO 的 24 位“中、低、高”布局。 */
        MID_LOW_HIGH
    }

    /** 串口参数快照。数据位/停止位/校验位按 pyserial 默认推定为 8N1。 */
    public record SerialSettings(int baudRate, boolean rtscts, boolean xonxoff) {

        public SerialSettings {
            if (baudRate <= 0) {
                throw new IllegalArgumentException("baudRate must be positive: " + baudRate);
            }
        }
    }

    /** 选择器：用于在枚举结果中按条件挑选设备。 */
    public interface Selector extends Predicate<DeviceCandidate> {
    }
}

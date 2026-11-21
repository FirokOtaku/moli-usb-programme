package space.firok.moli.usb.serial;

import com.fazecast.jSerialComm.SerialPort;
import space.firok.moli.usb.api.DeviceProvider;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.error.DeviceNotFoundError;
import space.firok.moli.usb.api.transport.Transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link DeviceProvider} 的串口实现：枚举系统串口并打开选中的端口。
 *
 * <p><b>设备识别规则（spec/verification.md P0）：</b>不能仅凭端口名判定一台串口属于
 * MSU2。本实现只用 USB 元数据（VID/PID）做筛选，端口名只作为传输地址与显示用途。</p>
 *
 * <p><b>VID/PID 尚未确认：</b>本仓库 spec 目前没有记录 MINI/LITE/PRO 的 USB VID/PID。
 * 因此 {@link #VID_PID_UNCONFIRMED} 默认为空列表，含义是「不过滤」：枚举结果如实列出全部
 * 串口并在输出里标注「未识别」，提醒使用者先按实机记录填好候选列表。确认后以
 * {@link UsbId} 列表传入构造函数即可启用筛选。本类不内置、不猜测任何 VID/PID 数值。</p>
 *
 * <p><b>禁止双开：</b>本类用一个进程级集合占用端口名，重复打开同一端口抛
 * {@link DeviceBusyError}，并在返回的传输关闭时释放。api 模块的
 * {@code DeviceSessionRegistry} 只在会话层登记，无法阻止「provider 直接打开两次」，
 * 所以占用必须在这里先做。</p>
 *
 * <p><b>串口参数：</b>{@link DeviceProvider} 接口只接受候选设备、不接受串口参数，因此
 * provider 需要携带一份默认参数。构造时请传入与目标 profile 一致的
 * {@link DeviceProfile#serial()} 映射结果；波特率不匹配时握手不会成功。</p>
 */
public final class SerialDeviceProvider implements DeviceProvider {

    /**
     * 待实机确认的空候选列表：表示「不按 VID/PID 过滤」。
     *
     * <p>不在此填写任何数值。确认某机型 VID/PID 后由调用方构造 {@link UsbId} 传入。</p>
     */
    public static final List<UsbId> VID_PID_UNCONFIRMED = List.of();

    /** 进程级端口占用集合，键为系统端口名。 */
    private static final Set<String> OPEN_PORTS = ConcurrentHashMap.newKeySet();

    private final List<UsbId> acceptedUsbIds;
    private final Supplier<List<PortDescriptor>> enumerator;
    private final SerialPortConfig defaultConfig;
    private final Consumer<String> traceSink;

    /**
     * 默认实例：不过滤 VID/PID、使用 {@link DeviceProfile#LEGACY_SERIAL} 的串口参数、
     * 不打印传输层字节。
     */
    public SerialDeviceProvider() {
        this(VID_PID_UNCONFIRMED);
    }

    /** 以给定 VID/PID 候选列表构造；空列表表示不过滤。 */
    public SerialDeviceProvider(List<UsbId> acceptedUsbIds) {
        this(acceptedUsbIds,
                SerialDeviceProvider::enumerateSystemPorts,
                SerialPortConfig.from(DeviceProfile.LEGACY_SERIAL),
                null);
    }

    private SerialDeviceProvider(List<UsbId> acceptedUsbIds,
                                 Supplier<List<PortDescriptor>> enumerator,
                                 SerialPortConfig defaultConfig,
                                 Consumer<String> traceSink) {
        this.acceptedUsbIds = List.copyOf(Objects.requireNonNull(acceptedUsbIds, "acceptedUsbIds"));
        this.enumerator = Objects.requireNonNull(enumerator, "enumerator");
        this.defaultConfig = Objects.requireNonNull(defaultConfig, "defaultConfig");
        this.traceSink = traceSink;
    }

    /**
     * 测试与自定义枚举用构造函数：注入假的端口元数据来源。
     *
     * <p>这使 VID/PID 过滤、候选构造与排序逻辑可以在没有真实串口的环境下验证。</p>
     */
    public static SerialDeviceProvider over(Supplier<List<PortDescriptor>> enumerator,
                                           List<UsbId> acceptedUsbIds,
                                           SerialPortConfig defaultConfig) {
        return new SerialDeviceProvider(acceptedUsbIds, enumerator, defaultConfig, null);
    }

    /** 返回一个在每次传输读写时把原始字节以十六进制回调出去的副本。 */
    public SerialDeviceProvider withTrace(Consumer<String> sink) {
        Objects.requireNonNull(sink, "sink");
        return new SerialDeviceProvider(acceptedUsbIds, enumerator, defaultConfig, sink);
    }

    /** 返回一个使用不同默认串口参数的副本。 */
    public SerialDeviceProvider withDefaultConfig(SerialPortConfig config) {
        Objects.requireNonNull(config, "config");
        return new SerialDeviceProvider(acceptedUsbIds, enumerator, config, traceSink);
    }

    /** 当前配置的 VID/PID 候选列表（空表示不过滤）。 */
    public List<UsbId> acceptedUsbIds() {
        return acceptedUsbIds;
    }

    /** 当前是否配置了可用的 VID/PID 候选。 */
    public boolean hasUsbIdCandidates() {
        return !acceptedUsbIds.isEmpty();
    }

    /** 默认串口参数。 */
    public SerialPortConfig defaultConfig() {
        return defaultConfig;
    }

    /**
     * 枚举全部系统串口（不打开端口）。
     *
     * <p>jSerialComm 的 {@code getCommPorts()} 只读取平台串口清单与 USB 元数据，不会
     * 打开句柄，符合 {@link DeviceProvider} 的「扫描不得靠打开端口」要求。</p>
     */
    public List<PortDescriptor> descriptors() {
        return List.copyOf(enumerator.get());
    }

    @Override
    public List<DeviceCandidate> listCandidates() {
        List<DeviceCandidate> candidates = new ArrayList<>();
        for (PortDescriptor descriptor : descriptors()) {
            if (matches(descriptor, acceptedUsbIds)) {
                candidates.add(toCandidate(descriptor));
            }
        }
        return List.copyOf(candidates);
    }

    @Override
    public Transport open(DeviceCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return open(candidate.id().transportKey(), defaultConfig);
    }

    /**
     * 按端口名与参数打开：命令行工具直接使用（它已由人工给出端口与波特率）。
     *
     * @throws DeviceBusyError     该端口已被本进程内的另一个传输占用
     * @throws DeviceNotFoundError 端口不存在或打开失败
     */
    public Transport open(String portName, SerialPortConfig config) {
        Objects.requireNonNull(portName, "portName");
        Objects.requireNonNull(config, "config");
        claimPort(portName);
        SerialTransport transport = null;
        try {
            transport = SerialTransport.open(PortDescriptor.ofName(portName), config);
            Transport delegated = traceSink == null
                    ? transport
                    : new TracingTransport(transport, traceSink);
            return new ReleasingTransport(delegated, portName);
        } catch (RuntimeException failed) {
            OPEN_PORTS.remove(portName);
            if (transport != null) {
                closeQuietly(transport);
            }
            throw failed;
        }
    }

    /**
     * 占用端口，已被占用时抛 {@link DeviceBusyError}。
     *
     * <p>与打开句柄分开，使「禁止双开」的规则可以在没有真实串口的环境下验证。</p>
     */
    static void claimPort(String portName) {
        if (!OPEN_PORTS.add(portName)) {
            throw new DeviceBusyError("open", portName,
                    "端口 " + portName + " 已被本进程内的另一个传输占用；同一端口不允许双开");
        }
    }

    /** 端口是否被本进程占用。 */
    public static boolean isPortClaimed(String portName) {
        return portName != null && OPEN_PORTS.contains(portName);
    }

    /** 当前被本进程占用的端口数量，供诊断与测试清理断言使用。 */
    public static int claimedPortCount() {
        return OPEN_PORTS.size();
    }

    /** 释放一个端口占用（仅供异常恢复与测试使用；正常路径走传输关闭）。 */
    static void releasePortClaim(String portName) {
        if (portName != null) {
            OPEN_PORTS.remove(portName);
        }
    }

    /**
     * 判断一个端口是否匹配候选 VID/PID 列表。
     *
     * <p>规则：候选列表为空时一律返回 {@code true}（不过滤）；否则端口必须同时具备
     * 已知的 VID 与 PID 且成对命中。端口名不参与判断。</p>
     */
    public static boolean matches(PortDescriptor descriptor, List<UsbId> acceptedUsbIds) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(acceptedUsbIds, "acceptedUsbIds");
        if (acceptedUsbIds.isEmpty()) {
            return true;
        }
        if (descriptor.vendorId().isEmpty() || descriptor.productId().isEmpty()) {
            return false;
        }
        int vendor = descriptor.vendorId().get();
        int product = descriptor.productId().get();
        return acceptedUsbIds.stream()
                .anyMatch(id -> id.vendorId() == vendor && id.productId() == product);
    }

    public DeviceCandidate toCandidate(PortDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        String uid = descriptor.serialNumber().isBlank()
                ? descriptor.portName()
                : descriptor.serialNumber();
        String displayName = descriptor.description().isBlank()
                ? descriptor.portName()
                : descriptor.portName() + " " + descriptor.description();
        return new DeviceCandidate(
                new DeviceId(uid, descriptor.portName()),
                displayName,
                new DeviceCandidate.OptionalInt(descriptor.vendorId()),
                new DeviceCandidate.OptionalInt(descriptor.productId()),
                Optional.of(descriptor.serialNumber()).filter(value -> !value.isBlank()),
                true);
    }

    /** 枚举系统串口并读取 USB 元数据。 */
    public static List<PortDescriptor> enumerateSystemPorts() {
        SerialPort[] ports;
        try {
            ports = SerialPort.getCommPorts();
        } catch (RuntimeException unsupported) {
            // 平台不支持或原生库加载失败时返回空列表：list 命令应输出提示而不是崩溃。
            return List.of();
        }
        return Arrays.stream(ports).map(SerialDeviceProvider::toDescriptor).toList();
    }

    private static PortDescriptor toDescriptor(SerialPort port) {
        return new PortDescriptor(
                port.getSystemPortName(),
                safe(port.getPortDescription()),
                safe(port.getManufacturer()),
                safe(port.getSerialNumber()),
                optionalId(port.getVendorID()),
                optionalId(port.getProductID()));
    }

    private static Optional<Integer> optionalId(int value) {
        // jSerialComm 用 -1 表示读不到该元数据。
        return value < 0 ? Optional.empty() : Optional.of(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietly(Transport transport) {
        try {
            transport.close();
        } catch (java.io.IOException ignored) {
            // 打开失败后的清理不应掩盖原始失败。
        }
    }

    /**
     * 在关闭时释放端口占用的传输装饰器。
     *
     * <p>占用与释放必须成对，且释放发生的时机就是调用方关闭传输（库在会话关闭时调用）。</p>
     */
    private record ReleasingTransport(Transport delegate, String portName)
            implements Transport {

        @Override
        public byte[] read(int maxBytes, int timeoutMs) throws java.io.IOException {
            return delegate.read(maxBytes, timeoutMs);
        }

        @Override
        public void write(byte[] data) throws java.io.IOException {
            delegate.write(data);
        }

        @Override
        public void clearInput() throws java.io.IOException {
            delegate.clearInput();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public void close() throws java.io.IOException {
            try {
                delegate.close();
            } finally {
                releasePortClaim(portName);
            }
        }

        @Override
        public String address() {
            return delegate.address();
        }
    }
}
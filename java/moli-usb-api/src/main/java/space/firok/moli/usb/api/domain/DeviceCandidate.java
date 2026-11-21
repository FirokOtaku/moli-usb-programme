package space.firok.moli.usb.api.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 领域层：枚举出的候选设备。
 *
 * <p>对应 spec/architecture.md 中的 {@code DeviceCandidate}：传输层地址、
 * USB/串口元数据和当前是否可打开。扫描不打开所有端口、不只凭明文横幅认设备；
 * 候选收集的判定信息由传输层提供（如 USB VID/PID、接口、串口元数据）。</p>
 */
public record DeviceCandidate(
        DeviceId id,
        String displayName,
        OptionalInt usbVendorId,
        OptionalInt usbProductId,
        Optional<String> usbSerialNumber,
        boolean available) {

    /**
     * 值语义的可选整数，用于 USB VID/PID 等元数据。
     * JDK 的 java.util.OptionalInt 是 final 类，无法作为 record 组件直接用在其上，
     * 故做一层薄包装。
     */
    public record OptionalInt(Optional<Integer> value) {

        public static OptionalInt of(int v) {
            return new OptionalInt(Optional.of(v));
        }

        public static OptionalInt empty() {
            return new OptionalInt(Optional.empty());
        }

        public boolean isPresent() {
            return value.isPresent();
        }

        public int asInt() {
            return value.orElseThrow();
        }
    }

    public DeviceCandidate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(usbVendorId, "usbVendorId");
        Objects.requireNonNull(usbProductId, "usbProductId");
        Objects.requireNonNull(usbSerialNumber, "usbSerialNumber");
    }
}

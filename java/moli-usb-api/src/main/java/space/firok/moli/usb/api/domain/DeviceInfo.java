package space.firok.moli.usb.api.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 领域层：设备信息。
 *
 * <p>对应 spec/architecture.md 中的 {@code DeviceInfo}：协议版本、UID、屏幕宽高、
 * 存储信息和设备报告的原始属性。均来自握手横幅与 SFR 自描述表，不在库内硬编码
 * 型号专属地址。</p>
 */
public record DeviceInfo(
        int protocolVersion,
        String uid,
        int screenWidth,
        int screenHeight,
        Optional<StorageGeometry> storage,
        Map<String, SfrEntry> reportedEntries) {

    public DeviceInfo {
        Objects.requireNonNull(uid, "uid");
        Objects.requireNonNull(storage, "storage");
        reportedEntries = Map.copyOf(reportedEntries);
    }

    /**
     * 设备 SFR 自描述表中的单项。
     */
    public record SfrEntry(String name, String unit, int family, List<Integer> data) {
    }

    /**
     * 按名称查询自描述表原始属性。
     */
    public Optional<SfrEntry> findEntry(String name) {
        return Optional.ofNullable(reportedEntries.get(name));
    }
}

package space.firok.moli.usb.gui;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 一次系统状态读数。空的百分比表示该项不可用，不是失败的整次采样。
 *
 * @param failures 本拍里抛异常的项目说明；没有电池不算失败
 */
record SystemSample(
        OptionalDouble cpuPercent,
        OptionalDouble memoryUsedPercent,
        OptionalDouble diskUsedPercent,
        BatteryReading battery,
        List<String> failures) {

    SystemSample {
        Objects.requireNonNull(cpuPercent, "cpuPercent");
        Objects.requireNonNull(memoryUsedPercent, "memoryUsedPercent");
        Objects.requireNonNull(diskUsedPercent, "diskUsedPercent");
        Objects.requireNonNull(battery, "battery");
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    static SystemSample unavailable() {
        return new SystemSample(
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                BatteryReading.unavailable(),
                List.of());
    }
}

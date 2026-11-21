package space.firok.moli.usb.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * 把各项读数收成一份样本。某一项抛异常或缺失时只把该项标成不可用，其它项照常返回。
 */
final class SystemSamples {

    @FunctionalInterface
    interface PercentProbe {
        OptionalDouble read();
    }

    private SystemSamples() {
    }

    /** 钳制到 {@code 0..100}。非有限数不是百分比。 */
    static double clampPercent(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("百分比必须是有限数");
        }
        return Math.clamp(value, 0.0, 100.0);
    }

    static SystemSample collect(PercentProbe cpu, PercentProbe memory, PercentProbe disk,
                                Supplier<BatteryReading> battery) {
        Objects.requireNonNull(cpu, "cpu");
        Objects.requireNonNull(memory, "memory");
        Objects.requireNonNull(disk, "disk");
        Objects.requireNonNull(battery, "battery");
        List<String> failures = new ArrayList<>();
        OptionalDouble cpuPercent = read("CPU", cpu, failures);
        OptionalDouble memoryPercent = read("内存", memory, failures);
        OptionalDouble diskPercent = read("磁盘", disk, failures);
        BatteryReading batteryReading = readBattery(battery, failures);
        return new SystemSample(cpuPercent, memoryPercent, diskPercent, batteryReading, failures);
    }

    private static OptionalDouble read(String name, PercentProbe probe, List<String> failures) {
        try {
            OptionalDouble value = probe.read();
            if (value == null || value.isEmpty()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(clampPercent(value.getAsDouble()));
        } catch (RuntimeException error) {
            failures.add(name + "：" + message(error));
            return OptionalDouble.empty();
        }
    }

    private static BatteryReading readBattery(Supplier<BatteryReading> battery, List<String> failures) {
        try {
            BatteryReading reading = battery.get();
            if (reading == null) {
                failures.add("电池：空结果");
                return BatteryReading.unavailable();
            }
            return reading;
        } catch (RuntimeException error) {
            failures.add("电池：" + message(error));
            return BatteryReading.unavailable();
        }
    }

    private static String message(RuntimeException error) {
        String text = error.getMessage();
        if (text == null || text.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + "：" + text;
    }
}

package space.firok.moli.usb.gui;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * 电池读数。没有电池是正常情况，不是采样失败。
 */
record BatteryReading(BatteryKind kind, OptionalDouble percent) {

    BatteryReading {
        Objects.requireNonNull(kind, "kind");
        percent = percent == null ? OptionalDouble.empty() : percent;
        if (kind != BatteryKind.PRESENT && percent.isPresent()) {
            throw new IllegalArgumentException("只有存在电池时才有电量");
        }
    }

    static BatteryReading present(double percent) {
        return new BatteryReading(BatteryKind.PRESENT, OptionalDouble.of(SystemSamples.clampPercent(percent)));
    }

    static BatteryReading absent() {
        return new BatteryReading(BatteryKind.ABSENT, OptionalDouble.empty());
    }

    static BatteryReading unavailable() {
        return new BatteryReading(BatteryKind.UNAVAILABLE, OptionalDouble.empty());
    }

    boolean missing() {
        return kind == BatteryKind.ABSENT;
    }
}

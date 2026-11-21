package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

/**
 * 系统状态的百分比、缺电池和系统盘选择。不启动 JavaFX，不打开串口，也不扫描磁盘。
 */
class SystemSamplesTest {

    @Test
    @DisplayName("百分比钳制到 0 到 100，采样结果也走同一钳制")
    void clampsPercent() {
        assertEquals(0, SystemSamples.clampPercent(-1));
        assertEquals(0, SystemSamples.clampPercent(-0.01));
        assertEquals(0, SystemSamples.clampPercent(0));
        assertEquals(37.5, SystemSamples.clampPercent(37.5));
        assertEquals(100, SystemSamples.clampPercent(100));
        assertEquals(100, SystemSamples.clampPercent(100.01));
        assertEquals(100, SystemSamples.clampPercent(1000));
        assertThrows(IllegalArgumentException.class, () -> SystemSamples.clampPercent(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> SystemSamples.clampPercent(Double.POSITIVE_INFINITY));

        SystemSample sample = SystemSamples.collect(
                () -> OptionalDouble.of(-5),
                () -> OptionalDouble.of(150),
                () -> OptionalDouble.of(40),
                () -> BatteryReading.present(180));
        assertEquals(0, sample.cpuPercent().orElseThrow());
        assertEquals(100, sample.memoryUsedPercent().orElseThrow());
        assertEquals(100, sample.battery().percent().orElseThrow());
        assertTrue(sample.failures().isEmpty());
    }

    @Test
    @DisplayName("没有电池时样本仍然成功，其它指标不受影响")
    void missingBatteryDoesNotFailSample() {
        SystemSample sample = SystemSamples.collect(
                () -> OptionalDouble.of(12.5),
                () -> OptionalDouble.of(40),
                () -> OptionalDouble.of(55),
                BatteryReading::absent);
        assertEquals(12.5, sample.cpuPercent().orElseThrow());
        assertEquals(40, sample.memoryUsedPercent().orElseThrow());
        assertEquals(55, sample.diskUsedPercent().orElseThrow());
        assertTrue(sample.battery().missing());
        assertTrue(sample.battery().percent().isEmpty());
        assertTrue(sample.failures().isEmpty());

        SystemSample batteryFailed = SystemSamples.collect(
                () -> OptionalDouble.of(8),
                () -> OptionalDouble.of(9),
                () -> OptionalDouble.of(10),
                () -> {
                    throw new IllegalStateException("no power api");
                });
        assertEquals(8, batteryFailed.cpuPercent().orElseThrow());
        assertEquals(9, batteryFailed.memoryUsedPercent().orElseThrow());
        assertEquals(10, batteryFailed.diskUsedPercent().orElseThrow());
        assertEquals(BatteryKind.UNAVAILABLE, batteryFailed.battery().kind());
        assertFalse(batteryFailed.failures().isEmpty());
    }

    @Test
    @DisplayName("某一项失败时只把该项标成不可用")
    void failedMetricDoesNotFailOthers() {
        SystemSample sample = SystemSamples.collect(
                () -> {
                    throw new IllegalStateException("cpu");
                },
                () -> OptionalDouble.of(22),
                () -> OptionalDouble.empty(),
                BatteryReading::absent);
        assertTrue(sample.cpuPercent().isEmpty());
        assertEquals(22, sample.memoryUsedPercent().orElseThrow());
        assertTrue(sample.diskUsedPercent().isEmpty());
        assertTrue(sample.battery().missing());
        assertEquals(1, sample.failures().size());
    }

    @Test
    @DisplayName("系统盘只取 user.dir 盘符，不扫描磁盘")
    void systemDriveSelectionDoesNotScanDisks() {
        assertEquals("D:\\", SystemDrive.rootOf("D:\\projects\\moli-usb-programme"));
        assertEquals("E:\\", SystemDrive.rootOf("E:/work/app"));
        assertEquals("Z:\\", SystemDrive.rootOf("Z:\\not\\a\\mounted\\volume"));
        assertEquals("C:\\", SystemDrive.rootOf("\\\\server\\share\\folder"));
        assertEquals("C:\\", SystemDrive.rootOf(null));
        assertEquals("C:\\", SystemDrive.rootOf(""));
        assertEquals("C:\\", SystemDrive.rootOf("   "));
        assertEquals("C:\\", SystemDrive.rootOf("relative\\path"));
        assertEquals(25.0, SystemDrive.usedPercent(400, 300));
        assertEquals(0.0, SystemDrive.usedPercent(100, 250));
        assertEquals(100.0, SystemDrive.usedPercent(80, 0));
    }

    @Test
    @DisplayName("宽或高为 0 时系统状态和桌面镜像都不发送")
    void zeroPanelDoesNotSend() {
        assertFalse(spec(ContentMode.STATS, 0, 13).canSend());
        assertFalse(spec(ContentMode.MIRROR, 17, 0).canSend());
        assertTrue(spec(ContentMode.STATS, 17, 13).canSend());
        assertTrue(spec(ContentMode.MIRROR, 17, 13).canSend());
    }

    @Test
    @DisplayName("系统状态画面使用传入尺寸，并且只有两种颜色")
    void statsRasterUsesRequestedSize() {
        SystemSample sample = SystemSamples.collect(
                () -> OptionalDouble.of(10),
                () -> OptionalDouble.of(20),
                () -> OptionalDouble.of(30),
                BatteryReading::absent);
        Rgb565Image image = StatsRaster.render(17, 13, sample, 0xFFFF, 0x0000);
        assertEquals(17, image.width());
        assertEquals(13, image.height());
        assertEquals(17 * 13, image.pixels().length);
        for (int pixel : image.pixels()) {
            assertTrue(pixel == 0xFFFF || pixel == 0x0000);
        }
    }

    private static ContentSpec spec(ContentMode mode, int width, int height) {
        return new ContentSpec(mode, width, height, 0, 0, "", null, false, 16, 1000, 1);
    }
}

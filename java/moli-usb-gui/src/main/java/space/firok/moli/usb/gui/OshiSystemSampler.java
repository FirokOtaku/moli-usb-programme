package space.firok.moli.usb.gui;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.PowerSource;

import java.util.List;
import java.util.OptionalDouble;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 用 OSHI 读 Windows 的 CPU、物理内存和电池。系统盘不走 OSHI 的文件存储枚举，
 * 只查询 {@link SystemDrive} 选出的那一个根路径，避免网络盘或可移动盘卡住。
 *
 * <p>第一次 CPU 采样没有上一拍，该项显示不可用，不因此失败。应在 JavaFX 线程之外调用。</p>
 */
final class OshiSystemSampler implements SystemSampler {

    private static final Logger LOG = Logger.getLogger(OshiSystemSampler.class.getName());

    private SystemInfo systemInfo;
    private long[] previousTicks;
    private List<PowerSource> powerSources;

    @Override
    public SystemSample sample() {
        return SystemSamples.collect(
                () -> guarded("CPU", this::readCpu),
                () -> guarded("内存", this::readMemory),
                () -> guarded("磁盘", this::readDisk),
                this::readBattery);
    }

    private OptionalDouble guarded(String name, SystemSamples.PercentProbe probe) {
        try {
            return probe.read();
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "读取" + name + "失败", error);
            throw error;
        }
    }

    private OptionalDouble readCpu() {
        CentralProcessor processor = info().getHardware().getProcessor();
        long[] previous = previousTicks;
        if (previous == null) {
            previousTicks = processor.getSystemCpuLoadTicks();
            return OptionalDouble.empty();
        }
        double load = processor.getSystemCpuLoadBetweenTicks(previous);
        previousTicks = processor.getSystemCpuLoadTicks();
        if (!Double.isFinite(load)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(load * 100.0);
    }

    private OptionalDouble readMemory() {
        GlobalMemory memory = info().getHardware().getMemory();
        long total = memory.getTotal();
        if (total <= 0) {
            return OptionalDouble.empty();
        }
        long available = Math.max(0, memory.getAvailable());
        long used = available >= total ? 0 : total - available;
        return OptionalDouble.of(used * 100.0 / total);
    }

    private OptionalDouble readDisk() {
        String root = SystemDrive.rootOf(System.getProperty("user.dir"));
        return OptionalDouble.of(SystemDrive.queryUsedPercent(root));
    }

    private BatteryReading readBattery() {
        try {
            if (powerSources == null) {
                List<PowerSource> found = info().getHardware().getPowerSources();
                powerSources = found == null ? List.of() : List.copyOf(found);
            } else {
                for (PowerSource source : powerSources) {
                    source.updateAttributes();
                }
            }
            if (powerSources.isEmpty()) {
                return BatteryReading.absent();
            }
            double fraction = powerSources.get(0).getRemainingCapacityPercent();
            if (!Double.isFinite(fraction) || fraction < 0) {
                return BatteryReading.unavailable();
            }
            return BatteryReading.present(fraction * 100.0);
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "读取电池失败", error);
            throw error;
        }
    }

    private SystemInfo info() {
        if (systemInfo == null) {
            systemInfo = new SystemInfo();
        }
        return systemInfo;
    }
}

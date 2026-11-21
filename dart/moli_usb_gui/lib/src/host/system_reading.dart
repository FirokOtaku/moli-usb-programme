/// One host metric, or unavailable when that call failed.
///
/// [percent] is 0..100 when present. Callers clamp before constructing.
final class PercentMetric {
  const PercentMetric(this.percent);

  const PercentMetric.unavailable() : percent = null;

  final int? percent;

  bool get available => percent != null;
}

enum BatteryPresence {
  /// A battery was read.
  present,

  /// The machine has no battery. This is not a failure of the stats mode.
  absent,

  /// The power API failed or returned an unknown percent.
  unavailable,
}

final class BatteryMetric {
  const BatteryMetric.absent()
    : presence = BatteryPresence.absent,
      percent = null,
      charging = null;

  const BatteryMetric.unavailable()
    : presence = BatteryPresence.unavailable,
      percent = null,
      charging = null;

  const BatteryMetric.present(this.percent, {this.charging})
    : presence = BatteryPresence.present;

  final BatteryPresence presence;
  final int? percent;
  final bool? charging;
}

/// Idle, kernel and user times from `GetSystemTimes`, in 100ns units.
///
/// Kernel time includes idle time, matching the Win32 definition.
final class SystemTimesSample {
  const SystemTimesSample({
    required this.idle,
    required this.kernel,
    required this.user,
  });

  final int idle;
  final int kernel;
  final int user;
}

/// Raw numbers from one sample. A null field is that metric's failure.
///
/// Kept free of Win32 types so tests can inject readings without a desktop.
final class RawMetricSnapshot {
  const RawMetricSnapshot({
    this.times,
    this.memoryTotalBytes,
    this.memoryAvailBytes,
    this.diskTotalBytes,
    this.diskFreeBytes,
    this.diskLabel,
    required this.powerOk,
    required this.batteryFlag,
    required this.batteryPercent,
    required this.acLineStatus,
  });

  final SystemTimesSample? times;
  final int? memoryTotalBytes;
  final int? memoryAvailBytes;
  final int? diskTotalBytes;
  final int? diskFreeBytes;
  final String? diskLabel;
  final bool powerOk;
  final int batteryFlag;
  final int batteryPercent;
  final int acLineStatus;
}

final class SystemReading {
  const SystemReading({
    required this.cpu,
    required this.memory,
    required this.disk,
    required this.battery,
    this.diskLabel,
  });

  final PercentMetric cpu;
  final PercentMetric memory;
  final PercentMetric disk;
  final BatteryMetric battery;
  final String? diskLabel;
}

final class StatsRow {
  const StatsRow({required this.label, this.percent});

  final String label;
  final int? percent;
}

/// Win32 `BATTERY_FLAG_NO_BATTERY`.
const int kBatteryFlagNoBattery = 128;

/// Win32 `BATTERY_FLAG_CHARGING`.
const int kBatteryFlagCharging = 8;

/// Win32 unknown battery flag or life percent.
const int kBatteryStatusUnknown = 255;

/// Clamps a percent into 0..100. Non-finite values become 0.
int clampPercent(num value) {
  if (value.isNaN || value.isInfinite) return 0;
  if (value <= 0) return 0;
  if (value >= 100) return 100;
  return value.round();
}

/// CPU percent from two `GetSystemTimes` samples.
///
/// Returns null when the elapsed total is not positive. Values outside 0..100
/// are clamped; a wild delta must not fail the rest of the reading.
int? cpuPercentFromTimes(
  SystemTimesSample previous,
  SystemTimesSample current,
) {
  final idle = current.idle - previous.idle;
  final kernel = current.kernel - previous.kernel;
  final user = current.user - previous.user;
  final total = kernel + user;
  if (total <= 0) return null;
  return clampPercent((total - idle) * 100 / total);
}

/// Interprets `GetSystemPowerStatus`.
///
/// Flag 255 also has the no-battery bit set, so unknown is checked first.
/// No battery is [BatteryPresence.absent], not a failed sample.
BatteryMetric batteryFromPowerStatus({
  required bool callSucceeded,
  required int batteryFlag,
  required int lifePercent,
  required int acLineStatus,
}) {
  if (!callSucceeded || batteryFlag == kBatteryStatusUnknown) {
    return const BatteryMetric.unavailable();
  }
  if ((batteryFlag & kBatteryFlagNoBattery) != 0) {
    return const BatteryMetric.absent();
  }
  if (lifePercent == kBatteryStatusUnknown || lifePercent < 0) {
    return const BatteryMetric.unavailable();
  }
  return BatteryMetric.present(
    clampPercent(lifePercent),
    charging:
        (batteryFlag & kBatteryFlagCharging) != 0 || acLineStatus == 1,
  );
}

PercentMetric usedPercent(int? total, int? freeOrAvailable) {
  if (total == null || freeOrAvailable == null || total <= 0) {
    return const PercentMetric.unavailable();
  }
  final used = total - freeOrAvailable;
  return PercentMetric(clampPercent(used * 100 / total));
}

SystemReading interpretRawMetrics(
  RawMetricSnapshot raw, {
  SystemTimesSample? previous,
}) {
  final times = raw.times;
  final cpu = previous == null || times == null
      ? null
      : cpuPercentFromTimes(previous, times);
  return SystemReading(
    cpu: cpu == null ? const PercentMetric.unavailable() : PercentMetric(cpu),
    memory: usedPercent(raw.memoryTotalBytes, raw.memoryAvailBytes),
    disk: usedPercent(raw.diskTotalBytes, raw.diskFreeBytes),
    battery: batteryFromPowerStatus(
      callSucceeded: raw.powerOk,
      batteryFlag: raw.batteryFlag,
      lifePercent: raw.batteryPercent,
      acLineStatus: raw.acLineStatus,
    ),
    diskLabel: raw.diskLabel,
  );
}

String percentLabel(String name, PercentMetric metric) {
  if (!metric.available) return '$name 不可用';
  return '$name ${metric.percent}%';
}

String batteryStatusLabel(BatteryMetric battery) {
  return switch (battery.presence) {
    BatteryPresence.absent => '无电池',
    BatteryPresence.unavailable => '不可用',
    BatteryPresence.present => _presentBattery(battery),
  };
}

String _presentBattery(BatteryMetric battery) {
  final percent = battery.percent ?? 0;
  if (battery.charging == true) return '$percent% 充电';
  return '$percent%';
}

List<StatsRow> statsRows(SystemReading reading) {
  final diskName = reading.diskLabel == null
      ? '磁盘'
      : '磁盘 ${reading.diskLabel}';
  return <StatsRow>[
    StatsRow(
      label: percentLabel('CPU', reading.cpu),
      percent: reading.cpu.percent,
    ),
    StatsRow(
      label: percentLabel('内存', reading.memory),
      percent: reading.memory.percent,
    ),
    StatsRow(
      label: percentLabel(diskName, reading.disk),
      percent: reading.disk.percent,
    ),
    StatsRow(
      label: '电池 ${batteryStatusLabel(reading.battery)}',
      percent: reading.battery.percent,
    ),
  ];
}

/// Drive root of [path], or null for a UNC / non-drive path.
///
/// Callers query this one root. They must not enumerate volumes.
String? driveRootOf(String path) {
  var value = path.trim();
  if (value.isEmpty) return null;
  if (value.startsWith(r'\\?\')) {
    value = value.substring(4);
  }
  if (value.startsWith(r'\\') || value.startsWith('//')) return null;
  final match = RegExp(r'^([A-Za-z]):').firstMatch(value);
  if (match == null) return null;
  return '${match.group(1)}:\\';
}

String? diskLabelOf(String? root) {
  if (root == null || root.length < 2) return null;
  return root.substring(0, 2);
}

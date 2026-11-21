import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:space_firok_moli_usb_gui/src/content/image_fit.dart';
import 'package:space_firok_moli_usb_gui/src/host/host_api.dart';
import 'package:space_firok_moli_usb_gui/src/host/host_metrics_unsupported.dart';
import 'package:space_firok_moli_usb_gui/src/host/host_metrics_web.dart';
import 'package:space_firok_moli_usb_gui/src/host/mirror_scale.dart';
import 'package:space_firok_moli_usb_gui/src/host/system_reading.dart';

void main() {
  group('percent clamping', () {
    test('values outside 0..100 clamp, and non-finite values become 0', () {
      expect(clampPercent(140), 100);
      expect(clampPercent(100), 100);
      expect(clampPercent(0), 0);
      expect(clampPercent(-4), 0);
      expect(clampPercent(42.4), 42);
      expect(clampPercent(double.nan), 0);
      expect(clampPercent(double.infinity), 0);
    });

    test('cpu delta clamps a wild sample and rejects a zero elapsed total', () {
      final previous = SystemTimesSample(idle: 100, kernel: 200, user: 50);
      final current = SystemTimesSample(idle: 150, kernel: 400, user: 150);
      expect(cpuPercentFromTimes(previous, current), 83);

      final overflow = cpuPercentFromTimes(
        const SystemTimesSample(idle: 0, kernel: 10, user: 0),
        const SystemTimesSample(idle: 0, kernel: 10, user: 1000),
      );
      expect(overflow, 100);

      expect(
        cpuPercentFromTimes(
          const SystemTimesSample(idle: 0, kernel: 100, user: 0),
          const SystemTimesSample(idle: 500, kernel: 100, user: 0),
        ),
        isNull,
      );
    });
  });

  group('missing battery', () {
    test('no battery is 无电池 and does not drop the other metrics', () {
      final battery = batteryFromPowerStatus(
        callSucceeded: true,
        batteryFlag: kBatteryFlagNoBattery,
        lifePercent: kBatteryStatusUnknown,
        acLineStatus: 1,
      );
      expect(battery.presence, BatteryPresence.absent);
      expect(batteryStatusLabel(battery), '无电池');

      const reading = SystemReading(
        cpu: PercentMetric(12),
        memory: PercentMetric(34),
        disk: PercentMetric.unavailable(),
        battery: BatteryMetric.absent(),
        diskLabel: 'C:',
      );
      expect(reading.cpu.percent, 12);
      expect(reading.memory.available, isTrue);
      expect(reading.disk.available, isFalse);
      expect(percentLabel('磁盘 C:', reading.disk), '磁盘 C: 不可用');
      expect(statsRows(reading).last.label, '电池 无电池');
      expect(statsRows(reading).first.percent, 12);
    });

    test('a failed power call is unavailable, not 无电池', () {
      final failed = batteryFromPowerStatus(
        callSucceeded: false,
        batteryFlag: 0,
        lifePercent: 0,
        acLineStatus: 0,
      );
      expect(failed.presence, BatteryPresence.unavailable);
      expect(batteryStatusLabel(failed), '不可用');
      expect(batteryStatusLabel(failed), isNot('无电池'));

      final unknown = batteryFromPowerStatus(
        callSucceeded: true,
        batteryFlag: kBatteryStatusUnknown,
        lifePercent: kBatteryStatusUnknown,
        acLineStatus: 255,
      );
      expect(unknown.presence, BatteryPresence.unavailable);
      expect(batteryStatusLabel(unknown), isNot('无电池'));
    });

    test('injected readings do not need a desktop', () async {
      final sampler = _FakeSampler(
        const SystemReading(
          cpu: PercentMetric(7),
          memory: PercentMetric(8),
          disk: PercentMetric(9),
          battery: BatteryMetric.absent(),
        ),
      );
      final reading = await sampler.sample();
      expect(sampler.supported, isTrue);
      expect(sampler.unsupportedReason, isNull);
      expect(reading?.cpu.percent, 7);
      expect(batteryStatusLabel(reading!.battery), '无电池');
      expect(reading.memory.available, isTrue);
    });
  });

  group('unsupported host', () {
    test('web and non-Windows reason is the browser sentence', () {
      final metrics = unsupportedHostMetrics();
      expect(kHostMetricsUnsupportedReason, isNotEmpty);
      expect(metrics.supported, isFalse);
      expect(metrics.unsupportedReason, kHostMetricsUnsupportedReason);
      expect(metrics.sampler.unsupportedReason, isNotEmpty);
      expect(metrics.capturer.unsupportedReason, isNotEmpty);
      expect(
        metrics.unsupportedReason,
        contains('浏览器不能读取本机 CPU、内存、磁盘和电池'),
      );

      final web = createHostMetricsImpl();
      expect(web.supported, isFalse);
      expect(web.unsupportedReason, isNotEmpty);
      expect(web.unsupportedReason, kHostMetricsUnsupportedReason);
    });

    test('unsupported sampler does not pretend to read the host', () {
      expect(
        unsupportedHostMetrics().sampler.sample(),
        throwsA(isA<UnsupportedError>()),
      );
      expect(
        unsupportedHostMetrics().capturer.capture(),
        throwsA(isA<UnsupportedError>()),
      );
    });
  });

  group('mirror scaling', () {
    test('defaults to contain and letterboxes a wide capture', () {
      final placed = placeMirror(
        captureWidth: 200,
        captureHeight: 100,
        panelWidth: 100,
        panelHeight: 100,
      );
      final contain = placeImage(
        imageWidth: 200,
        imageHeight: 100,
        panelWidth: 100,
        panelHeight: 100,
        fit: ImageFit.contain,
      );
      expect(placed.destX, contain.destX);
      expect(placed.destY, contain.destY);
      expect(placed.destWidth, contain.destWidth);
      expect(placed.destHeight, contain.destHeight);
      expect(placed.destHeight, 50);
      expect(placed.destY, 25);

      final rgba = Uint8List(4 * 2 * 4);
      for (var i = 0; i < rgba.length; i += 4) {
        rgba[i] = 255;
        rgba[i + 3] = 255;
      }
      final scaled = scaleMirrorCapture(
        rgba: rgba,
        captureWidth: 4,
        captureHeight: 2,
        panelWidth: 4,
        panelHeight: 4,
      );
      expect(scaled, hasLength(16));
      expect(scaled[0], 0x0000);
      expect(scaled[3], 0x0000);
      expect(scaled[4], isNot(0x0000));
      expect(scaled[12], 0x0000);
    });
  });

  test('current-directory drive is one root, never a UNC path', () {
    expect(driveRootOf(r'C:\projects\moli'), r'C:\');
    expect(driveRootOf('D:/temp'), r'D:\');
    expect(driveRootOf(r'\\server\share\file'), isNull);
    expect(driveRootOf(r'\\?\E:\data'), r'E:\');
    expect(diskLabelOf(r'C:\'), 'C:');
  });
}

final class _FakeSampler implements SystemSampler {
  _FakeSampler(this.reading);

  final SystemReading reading;

  @override
  bool get supported => true;

  @override
  String? get unsupportedReason => null;

  @override
  Future<SystemReading?> sample() async => reading;
}

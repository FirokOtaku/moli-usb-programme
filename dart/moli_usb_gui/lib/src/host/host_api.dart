import 'desktop_capture.dart';
import 'system_reading.dart';

/// Shown on Web and any non-Windows target. Do not replace this with a
/// success path: those hosts cannot read the machine or the desktop.
const String kHostMetricsUnsupportedReason =
    '浏览器不能读取本机 CPU、内存、磁盘和电池，也不能截取桌面。请使用 Windows 版。';

/// Failure that should be shown and must not freeze the page.
final class HostMetricException implements Exception {
  const HostMetricException(this.message);

  final String message;

  @override
  String toString() => message;
}

abstract interface class SystemSampler {
  bool get supported;

  String? get unsupportedReason;

  /// Null when a sample is already running. Callers drop that tick.
  Future<SystemReading?> sample();
}

abstract interface class DesktopCapturer {
  bool get supported;

  String? get unsupportedReason;

  /// Null when a capture is already running. Callers drop that tick.
  Future<DesktopCapture?> capture();
}

final class HostMetrics {
  const HostMetrics({required this.sampler, required this.capturer});

  final SystemSampler sampler;
  final DesktopCapturer capturer;

  bool get supported => sampler.supported && capturer.supported;

  String? get unsupportedReason =>
      sampler.unsupportedReason ?? capturer.unsupportedReason;
}

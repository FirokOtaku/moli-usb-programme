import 'desktop_capture.dart';
import 'host_api.dart';
import 'system_reading.dart';

/// Web and non-Windows stand-in. Construction does not touch Win32.
HostMetrics unsupportedHostMetrics() {
  return const HostMetrics(
    sampler: UnsupportedSystemSampler(),
    capturer: UnsupportedDesktopCapturer(),
  );
}

final class UnsupportedSystemSampler implements SystemSampler {
  const UnsupportedSystemSampler();

  @override
  bool get supported => false;

  @override
  String? get unsupportedReason => kHostMetricsUnsupportedReason;

  @override
  Future<SystemReading?> sample() async {
    throw UnsupportedError(kHostMetricsUnsupportedReason);
  }
}

final class UnsupportedDesktopCapturer implements DesktopCapturer {
  const UnsupportedDesktopCapturer();

  @override
  bool get supported => false;

  @override
  String? get unsupportedReason => kHostMetricsUnsupportedReason;

  @override
  Future<DesktopCapture?> capture() async {
    throw UnsupportedError(kHostMetricsUnsupportedReason);
  }
}

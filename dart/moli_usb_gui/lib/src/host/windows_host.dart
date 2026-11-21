import 'dart:io';
import 'dart:isolate';

import 'desktop_capture.dart';
import 'host_api.dart';
import 'system_reading.dart';
import 'windows_ffi.dart';

HostMetrics createWindowsHostMetrics() {
  return HostMetrics(
    sampler: WindowsSystemSampler(),
    capturer: WindowsDesktopCapturer(),
  );
}

/// Samples off the UI isolate. `GetDiskFreeSpaceEx` can block.
final class WindowsSystemSampler implements SystemSampler {
  SystemTimesSample? _previous;
  bool _busy = false;

  @override
  bool get supported => true;

  @override
  String? get unsupportedReason => null;

  @override
  Future<SystemReading?> sample() async {
    if (_busy) return null;
    _busy = true;
    try {
      final root = driveRootOf(Directory.current.path);
      final raw = await Isolate.run(() => readWindowsMetrics(root));
      final reading = interpretRawMetrics(raw, previous: _previous);
      if (raw.times != null) _previous = raw.times;
      return reading;
    } finally {
      _busy = false;
    }
  }
}

/// Primary-screen BitBlt off the UI isolate. A second call while one is
/// running returns null so the caller drops the tick instead of queueing.
final class WindowsDesktopCapturer implements DesktopCapturer {
  bool _busy = false;

  @override
  bool get supported => true;

  @override
  String? get unsupportedReason => null;

  @override
  Future<DesktopCapture?> capture() async {
    if (_busy) return null;
    _busy = true;
    try {
      return await Isolate.run(capturePrimaryScreen);
    } finally {
      _busy = false;
    }
  }
}

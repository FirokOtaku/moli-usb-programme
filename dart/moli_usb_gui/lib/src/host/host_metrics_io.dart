import 'dart:io';

import 'host_api.dart';
import 'host_metrics_unsupported.dart';
import 'windows_host.dart';

/// Non-Windows VM targets get the same refusal as Web and do not call Win32.
HostMetrics createHostMetricsImpl() {
  if (!Platform.isWindows) return unsupportedHostMetrics();
  return createWindowsHostMetrics();
}

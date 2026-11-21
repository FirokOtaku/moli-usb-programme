import 'host_api.dart';
import 'host_metrics_unsupported.dart';

/// Browser host. Must not import `win32`, `dart:ffi`, or attempt a capture.
HostMetrics createHostMetricsImpl() => unsupportedHostMetrics();

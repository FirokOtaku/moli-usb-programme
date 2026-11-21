import 'host_api.dart';
import 'host_metrics_unsupported.dart';

/// Default conditional-import target. No Win32, no capture.
HostMetrics createHostMetricsImpl() => unsupportedHostMetrics();

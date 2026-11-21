import 'host_api.dart';
import 'host_metrics_stub.dart'
    if (dart.library.io) 'host_metrics_io.dart'
    if (dart.library.js_interop) 'host_metrics_web.dart';

export 'host_api.dart';

/// Windows VM resolves the io library. Web has no `dart:io`, so it resolves
/// the web library and never imports `package:win32`.
HostMetrics createHostMetrics() => createHostMetricsImpl();

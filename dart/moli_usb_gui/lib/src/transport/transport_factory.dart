import 'host_binding.dart';
import 'transport_factory_stub.dart'
    if (dart.library.io) 'transport_factory_io.dart'
    if (dart.library.js_interop) 'transport_factory_web.dart';

export 'host_binding.dart';

/// Windows VM (including tests that import this file) resolves the io library
/// first, so they never import `webserial`. Web builds do not have `dart:io`,
/// so they resolve the web library and never import `flutter_libserialport`.
HostTransportBinding createHostTransport() => createHostTransportBinding();

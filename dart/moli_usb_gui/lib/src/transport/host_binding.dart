import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// Platform transport plus the host-only hooks the protocol interface lacks.
///
/// Web cannot enumerate every serial port, so [requestGrantedPort] is the
/// user-gesture entry that calls `requestWebSerialPort`. It must be invoked
/// directly from a button handler, never from a timer.
final class HostTransportBinding {
  const HostTransportBinding({
    required this.provider,
    required this.pollWhileDisconnected,
    this.requestGrantedPort,
    this.portEvents,
    this.grantedPortsHint,
  });

  final TransportProvider provider;

  /// Windows polls COM ports while disconnected. Web must not.
  final bool pollWhileDisconnected;

  /// Present only on Web. The implementation calls `requestWebSerialPort`
  /// before any other await.
  final Future<void> Function()? requestGrantedPort;

  /// Granted-port list changed (browser connect/disconnect).
  final Stream<void>? portEvents;

  /// Explains that the browser only lists previously authorized ports.
  final String? grantedPortsHint;

  bool get canRequestPort => requestGrantedPort != null;
}

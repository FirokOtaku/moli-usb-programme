// Candidate device, as returned by enumeration.

import '../transport/serial_settings.dart';
import '../transport/transport.dart';
import 'device_id.dart';

/// One selectable device.
///
/// `spec/architecture.md`: enumerating MUST NOT open every port just to read a
/// `MSN` banner. A candidate therefore only carries transport metadata plus
/// [isOpenable], which the provider computes from OS metadata.
final class DeviceCandidate {
  const DeviceCandidate({
    required this.id,
    required this.transport,
    this.profileHint,
    this.isOpenable = true,
    this.isBusy = false,
  });

  /// Provisional id until the device reports `MSN_UID`.
  final DeviceId id;

  /// The port descriptor this candidate came from.
  final TransportPortDescriptor transport;

  /// Serial parameters already confirmed for this candidate, if any.
  SerialSettings? get confirmedSettings => transport.confirmedSettings;

  /// Model name hint from USB product string or the caller's configuration.
  ///
  /// Never trusted as a capability source: the session still handshakes and
  /// prefers device-reported facts.
  final String? profileHint;

  /// Whether the provider believes this port can be opened right now.
  final bool isOpenable;

  /// Whether another client (or this library) holds the port.
  final bool isBusy;

  @override
  String toString() =>
      'DeviceCandidate($id, ${transport.displayName}, hint=$profileHint)';
}
// Device information gathered after a successful handshake.
//
// `spec/architecture.md`: `DeviceInfo` carries the protocol version, the UID,
// the panel size, storage facts and the raw self-description attributes.

import '../codec/flash.dart';
import '../codec/frame.dart';
import 'device_capabilities.dart';
import 'device_id.dart';
import 'device_profile.dart';
import 'display_geometry.dart';

/// Everything the session learned about one device.
///
/// [rawAttributes] keeps the self-description values verbatim, so callers can
/// inspect facts this library does not model yet without waiting for a new
/// release.
final class DeviceInfo {
  const DeviceInfo({
    required this.id,
    required this.profile,
    required this.protocolVersion,
    required this.capabilities,
    required this.display,
    this.firmwareVersion,
    this.storage,
    this.rawAttributes = const {},
  });

  final DeviceId id;

  /// The profile that was used to open the device. [DeviceProfile.generic] for
  /// unknown models.
  final DeviceProfile profile;

  /// Decimal protocol version from the `\0MSNxx` banner (for example 1).
  final int protocolVersion;

  /// Firmware version, when the self-description table reports one.
  final String? firmwareVersion;

  final DeviceCapabilities capabilities;

  final DisplayGeometry display;

  /// Storage geometry, when the device has external storage.
  final StorageGeometry? storage;

  /// Raw self-description attributes (name -> value).
  final Map<String, String> rawAttributes;

  /// Builds a copy with the confirmed values merged in.
  DeviceInfo withConfirmed({
    DeviceId? id,
    DisplayGeometry? display,
    DeviceCapabilities? capabilities,
    StorageGeometry? storage,
    String? firmwareVersion,
    Map<String, String>? rawAttributes,
  }) {
    return DeviceInfo(
      id: id ?? this.id,
      profile: profile,
      protocolVersion: protocolVersion,
      firmwareVersion: firmwareVersion ?? this.firmwareVersion,
      capabilities: capabilities ?? this.capabilities,
      display: display ?? this.display,
      storage: storage ?? this.storage,
      rawAttributes: rawAttributes ?? this.rawAttributes,
    );
  }

  @override
  String toString() => 'DeviceInfo($id, ${profile.name}, '
      'protocol=$protocolVersion, $display)';
}

/// A 6-byte response frame as delivered by the session layer.
///
/// Kept as a type alias target so higher layers never touch raw bytes.
typedef ResponseFrame = Frame;
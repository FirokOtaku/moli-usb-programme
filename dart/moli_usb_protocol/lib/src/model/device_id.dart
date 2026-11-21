// Stable device identity.
//
// `spec/architecture.md`: the preferred identity is the `MSN_UID` reported by
// the device after handshake. The transport address (COMx / port key) is only
// a temporary identifier for one enumeration and must never be persisted as a
// device identity.

/// Identity of one device.
///
/// Construct with [DeviceId] for a device-reported UID (for example `MSN_UID`),
/// or with [DeviceId.transport] while the UID is not known yet.
final class DeviceId {
  const DeviceId(this.value);

  /// Provisional id derived from a transport port key.
  ///
  /// Only valid for the current enumeration. The session replaces it with the
  /// reported `MSN_UID` as soon as the self-description table provided one.
  factory DeviceId.transport(String transportKey) =>
      DeviceId('transport:$transportKey');

  final String value;

  /// Whether this id is the provisional transport-derived one.
  bool get isProvisional => value.startsWith('transport:');

  @override
  bool operator ==(Object other) => other is DeviceId && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value;
}
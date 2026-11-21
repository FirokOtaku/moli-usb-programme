// Error taxonomy shared by every layer.
//
// The kinds mirror the error classification in `spec/architecture.md`. Callers
// must be able to branch on `kind` (or on the named constructors) without
// parsing messages.

/// Classification of a protocol-library failure.
enum MoliErrorKind {
  /// No candidate matched the requested device id / selector.
  deviceNotFound('DeviceNotFound'),

  /// The device is already owned by another session.
  deviceBusy('DeviceBusy'),

  /// The underlying transport reported a closed link or the stream ended.
  disconnected('Disconnected'),

  /// The `\0MSNCN` handshake did not complete.
  handshakeFailed('HandshakeFailed'),

  /// The peer answered, but not with a plausible MSU2 protocol banner.
  protocolMismatch('ProtocolMismatch'),

  /// The received bytes could not be aligned to the 6-byte frame boundary.
  protocolDesynchronized('ProtocolDesynchronized'),

  /// A read exceeded its total timeout.
  timeout('Timeout'),

  /// The caller cancelled the operation through a cancellation token.
  cancelled('Cancelled'),

  /// The device (or the active profile) does not provide this capability.
  unsupportedCapability('UnsupportedCapability'),

  /// An argument was structurally invalid (negative size, bad range, ...).
  invalidArgument('InvalidArgument'),

  /// A display/geometry coordinate fell outside the addressable area.
  outOfBounds('OutOfBounds'),

  /// A storage request exceeded the reported medium capacity.
  storageCapacityExceeded('StorageCapacityExceeded'),

  /// The storage medium rejected or failed the operation.
  storageOperationFailed('StorageOperationFailed'),

  /// A response could not be decoded into the expected shape.
  malformedResponse('MalformedResponse');

  const MoliErrorKind(this.label);

  /// Stable, human readable name (matches the spec wording).
  final String label;
}

/// The single exception type raised by this library.
///
/// [operation] names the failing domain/session operation (for example
/// `fillRect`), [deviceId] carries the resolved device id when one is known and
/// [cause] keeps the underlying reason (usually another exception).
class MoliException implements Exception {
  const MoliException(
    this.kind,
    this.message, {
    this.operation,
    this.deviceId,
    this.cause,
    this.causeStackTrace,
  });

  const MoliException.deviceNotFound(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.deviceNotFound,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.deviceBusy(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.deviceBusy,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.disconnected(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.disconnected,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.handshakeFailed(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.handshakeFailed,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.protocolMismatch(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.protocolMismatch,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.protocolDesynchronized(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.protocolDesynchronized,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.timeout(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.timeout,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.cancelled(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.cancelled,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.unsupportedCapability(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.unsupportedCapability,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.invalidArgument(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.invalidArgument,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.outOfBounds(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.outOfBounds,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.storageCapacityExceeded(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.storageCapacityExceeded,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.storageOperationFailed(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.storageOperationFailed,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  const MoliException.malformedResponse(
    String message, {
    String? operation,
    String? deviceId,
    Object? cause,
  }) : this(
          MoliErrorKind.malformedResponse,
          message,
          operation: operation,
          deviceId: deviceId,
          cause: cause,
        );

  /// What went wrong, at the granularity of [MoliErrorKind].
  final MoliErrorKind kind;

  /// Human readable explanation. Never needs to be parsed by the caller.
  final String message;

  /// Name of the failing operation, for example `handshake` or `fillRect`.
  final String? operation;

  /// Device id the operation was addressed to, when known.
  final String? deviceId;

  /// Underlying reason (transport error, parser error, ...).
  final Object? cause;

  /// Stack trace that belongs to [cause], when captured.
  final StackTrace? causeStackTrace;

  /// Whether retrying the same operation could plausibly succeed.
  bool get isRetryable => switch (kind) {
        MoliErrorKind.timeout ||
        MoliErrorKind.deviceBusy ||
        MoliErrorKind.protocolDesynchronized =>
          true,
        _ => false,
      };

  /// Returns a copy that fills in missing context (operation / device id).
  MoliException withContext({String? operation, String? deviceId}) {
    return MoliException(
      kind,
      message,
      operation: this.operation ?? operation,
      deviceId: this.deviceId ?? deviceId,
      cause: cause,
      causeStackTrace: causeStackTrace,
    );
  }

  @override
  String toString() {
    final buffer = StringBuffer('${kind.label}: $message');
    if (operation != null) buffer.write(' [operation=$operation]');
    if (deviceId != null) buffer.write(' [device=$deviceId]');
    if (cause != null) buffer.write(' [cause=$cause]');
    return buffer.toString();
  }
}

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// Whether a failed send means the session should be closed.
///
/// Branches on [MoliException.kind] only. Timeout and desynchronization close
/// the session only when the transport is already gone.
bool shouldCloseForLinkLoss(
  MoliException error, {
  required bool portClosed,
  required bool sessionConnected,
}) {
  switch (error.kind) {
    case MoliErrorKind.disconnected:
      return true;
    case MoliErrorKind.timeout:
    case MoliErrorKind.protocolDesynchronized:
      if (!sessionConnected || portClosed) return true;
      final cause = error.cause;
      return cause is MoliException &&
          cause.kind == MoliErrorKind.disconnected;
    case MoliErrorKind.deviceNotFound:
    case MoliErrorKind.deviceBusy:
    case MoliErrorKind.handshakeFailed:
    case MoliErrorKind.protocolMismatch:
    case MoliErrorKind.cancelled:
    case MoliErrorKind.unsupportedCapability:
    case MoliErrorKind.invalidArgument:
    case MoliErrorKind.outOfBounds:
    case MoliErrorKind.storageCapacityExceeded:
    case MoliErrorKind.storageOperationFailed:
    case MoliErrorKind.malformedResponse:
      return false;
  }
}

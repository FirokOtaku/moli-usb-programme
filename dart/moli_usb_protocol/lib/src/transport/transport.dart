// Transport layer abstractions.
//
// This is the ONLY layer that touches bytes. The library never imports
// dart:ffi, dart:html or dart:js_interop; concrete transports (Web Serial,
// Web USB, native serial, tests) are injected by the host application through
// [TransportPort] and [TransportProvider].

import 'dart:async';
import 'dart:typed_data';

import '../errors.dart';
import 'serial_settings.dart';

/// Cancellation token consulted by long running transport/session operations.
///
/// Repeated [ensureNotCancelled] calls abort with a [MoliErrorKind.cancelled]
/// error as soon as the token is triggered.
class CancelToken {
  bool _cancelled = false;
  String? _reason;

  /// Whether [cancel] has been called.
  bool get isCancelled => _cancelled;

  /// Why the token was cancelled, when provided.
  String? get reason => _reason;

  /// Request cancellation of the operations observing this token.
  void cancel([String? reason]) {
    _cancelled = true;
    _reason = reason;
  }

  /// Throws [MoliException] with kind [MoliErrorKind.cancelled] when cancelled.
  void ensureNotCancelled({String? operation}) {
    if (_cancelled) {
      throw MoliException.cancelled(
        _reason ?? 'operation cancelled',
        operation: operation,
      );
    }
  }
}

/// How the host platform identified a candidate port.
///
/// USB metadata is optional because a transport may only know a raw port name
/// (for example a COMx name). The session layer uses these hints to narrow the
/// candidate list before ever opening a port, as required by
/// `spec/architecture.md` (never scan-and-open every port just to read banners).
class TransportUsbMetadata {
  const TransportUsbMetadata({
    this.vendorId,
    this.productId,
    this.usbClass,
    this.usbSubclass,
    this.usbProtocol,
    this.manufacturer,
    this.product,
    this.serialNumber,
  });

  final int? vendorId;
  final int? productId;
  final int? usbClass;
  final int? usbSubclass;
  final int? usbProtocol;
  final String? manufacturer;
  final String? product;
  final String? serialNumber;

  @override
  String toString() {
    final parts = <String>[
      if (vendorId != null) 'vid=0x${vendorId!.toRadixString(16).padLeft(4, '0')}',
      if (productId != null)
        'pid=0x${productId!.toRadixString(16).padLeft(4, '0')}',
      if (usbClass != null) 'class=0x${usbClass!.toRadixString(16)}',
      if (manufacturer != null) 'mfr=$manufacturer',
      if (product != null) 'product=$product',
      if (serialNumber != null) 'serial=$serialNumber',
    ];
    return 'TransportUsbMetadata(${parts.join(', ')})';
  }
}

/// Static description of one port offered by a [TransportProvider].
///
/// This is the transport-layer half of the domain's `DeviceCandidate`; the
/// domain layer wraps it and keeps a stable MSN_UID based id once known.
class TransportPortDescriptor {
  const TransportPortDescriptor({
    required this.transportKey,
    required this.displayName,
    required this.kind,
    this.usb,
    this.suggestedSettings,
    this.confirmedSettings,
  });

  /// Opaque per-enumeration identifier. Only meaningful to the provider that
  /// produced it; never persisted as a device identity.
  final String transportKey;

  /// Human readable label for UIs ("COM7", "USB Serial Device").
  final String displayName;

  /// The transport family this port belongs to.
  final String kind;

  /// USB metadata, when the platform provides it.
  final TransportUsbMetadata? usb;

  /// Parameters the caller configured or that the platform recommends. Used by
  /// the session when [confirmedSettings] is absent.
  final SerialSettings? suggestedSettings;

  /// Parameters that the platform adapter has already confirmed for this
  /// specific device (matched VID/PID or an earlier successful handshake on
  /// this port). Preferred over [suggestedSettings] when present.
  final SerialSettings? confirmedSettings;

  TransportPortDescriptor withConfirmedSettings(SerialSettings settings) {
    return TransportPortDescriptor(
      transportKey: transportKey,
      displayName: displayName,
      kind: kind,
      usb: usb,
      suggestedSettings: suggestedSettings,
      confirmedSettings: settings,
    );
  }

  @override
  String toString() => 'TransportPortDescriptor($transportKey, $displayName)';
}

/// An opened, byte-level connection to one device.
///
/// Implementations MUST:
/// - honor [readTimeout] and [writeTimeout] per call (or per queued read),
/// - reject concurrent opens of the same port through the provider,
/// - surface link loss as [MoliErrorKind.disconnected] errors,
/// - report every failure as a [MoliException] so the session can classify it
///   without parsing messages (a raw exception is a programming error and is
///   propagated unchanged).
abstract interface class TransportPort {
  /// The descriptor this port was opened from.
  TransportPortDescriptor get descriptor;

  /// The settings actually applied.
  SerialSettings get settings;

  /// Reads up to [maxBytes]. Completes as soon as at least one byte arrived.
  ///
  /// Completes with a [MoliErrorKind.timeout] error when no byte arrives in
  /// time, with [MoliErrorKind.cancelled] when [token] fired, and with a
  /// [MoliErrorKind.disconnected] error once the link is gone.
  Future<Uint8List> read(
    int maxBytes, {
    Duration? readTimeout,
    CancelToken? token,
  });

  /// Writes all [bytes]. May split into multiple physical writes but must not
  /// return before everything is handed to the OS / driver.
  Future<void> write(
    List<int> bytes, {
    Duration? writeTimeout,
    CancelToken? token,
  });

  /// Waits until [count] bytes are available (bounded by [timeout]).
  Future<void> waitForAvailable(
    int count, {
    Duration? timeout,
    CancelToken? token,
  });

  /// Drops everything currently buffered from the device.
  Future<void> discardInput();

  /// Reads everything currently buffered without waiting.
  ///
  /// Returns an empty list when nothing is buffered. Implementations that
  /// cannot poll non-blockingly may return an empty list as a best effort.
  Future<Uint8List> readAvailable();

  /// Closes the port. Idempotent.
  Future<void> close();

  /// Whether [close] has completed.
  bool get isClosed;
}

/// Enumerates, opens and closes transport ports.
///
/// The host application implements this once per platform:
///  - Web: Web Serial (preferred) or Web USB adapter,
///  - Windows/native: serial port adapter,
///  - Tests: fake in-memory device.
///
/// Implementations MUST NOT open a port during [listPorts]; enumeration must
/// rely on OS metadata only.
abstract interface class TransportProvider {
  /// The transport family id (used to match [TransportPortDescriptor.kind]).
  String get kind;

  /// Lists currently visible ports without opening them.
  Future<List<TransportPortDescriptor>> listPorts();

  /// Opens [descriptor]. Throws [MoliErrorKind.deviceNotFound] when the port
  /// disappeared and [MoliErrorKind.deviceBusy] when it is already owned (by
  /// this or another client).
  Future<TransportPort> open(
    TransportPortDescriptor descriptor, {
    SerialSettings? settingsOverride,
  });
}

/// Convenience helpers shared by transport implementations.
extension TransportPortExtension on TransportPort {
  /// Reads until the buffer contains [count] bytes or the timeout elapses.
  ///
  /// The returned list has exactly [count] bytes unless an error is thrown.
  Future<Uint8List> readExact(
    int count, {
    Duration? overallTimeout,
    CancelToken? token,
  }) async {
    if (count < 0) {
      throw MoliException.invalidArgument(
        'count must be non-negative (got $count)',
        operation: 'readExact',
      );
    }
    if (count == 0) return Uint8List(0);
    final collected = ByteAccumulator();
    var remaining = count;
    // Slices are short so a cancelled token is observed promptly even when the
    // transport would otherwise block for the whole operation timeout.
    const maxSlice = Duration(milliseconds: 100);
    final singleReadTimeout =
        overallTimeout != null && overallTimeout < maxSlice
            ? overallTimeout
            : maxSlice;
    final deadline =
        overallTimeout == null ? null : DateTime.now().add(overallTimeout);
    while (remaining > 0) {
      token?.ensureNotCancelled(operation: 'readExact');
      var sliceTimeout = singleReadTimeout;
      if (deadline != null) {
        final left = deadline.difference(DateTime.now());
        if (left <= Duration.zero) {
          throw MoliException.timeout(
            'timed out after collecting ${collected.length}/$count bytes',
            operation: 'readExact',
          );
        }
        if (left < sliceTimeout) sliceTimeout = left;
      }
      final Uint8List chunk;
      try {
        chunk = await read(remaining, readTimeout: sliceTimeout, token: token);
      } on MoliException catch (error) {
        // An inner slice timeout is not the operation's timeout.
        if (error.kind == MoliErrorKind.timeout) continue;
        rethrow;
      }
      if (chunk.isEmpty) continue;
      collected.add(chunk);
      remaining -= chunk.length;
      if (remaining < 0) {
        // A port that returns more than `maxBytes` violates the contract; the
        // extra bytes cannot be pushed back, so fail loudly instead of
        // silently desynchronizing the frame stream.
        throw MoliException(
          MoliErrorKind.protocolDesynchronized,
          'port returned ${-remaining} bytes beyond the requested amount',
          operation: 'readExact',
        );
      }
    }
    return collected.toBytes();
  }
}

/// Growable byte accumulator that copies on [toBytes] (no dart:io dependency).
///
/// Named differently from `dart:typed_data`'s `BytesBuilder` on purpose: the
/// two APIs are unrelated and mixing them up is a common bug.
class ByteAccumulator {
  final List<Uint8List> _chunks = [];
  int _length = 0;

  void add(List<int> bytes) {
    _chunks.add(bytes is Uint8List ? bytes : Uint8List.fromList(bytes));
    _length += bytes.length;
  }

  int get length => _length;

  bool get isEmpty => _length == 0;

  bool get isNotEmpty => _length != 0;

  Uint8List toBytes() {
    if (_chunks.length == 1) {
      final only = _chunks.single;
      return only.length == _length ? only : Uint8List.fromList(only);
    }
    final out = Uint8List(_length);
    var offset = 0;
    for (final chunk in _chunks) {
      out.setAll(offset, chunk);
      offset += chunk.length;
    }
    return out;
  }

  void clear() {
    _chunks.clear();
    _length = 0;
  }
}
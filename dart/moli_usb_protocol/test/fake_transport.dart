// Scripted in-memory MSU2 device for tests.
//
// Implements the injected [TransportPort] contract: no dart:io, no real
// hardware. Behaviour knobs cover the cases the session layer must handle:
// normal request/response, silence (timeout), cancellation and link loss.

import 'dart:async';
import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// Default banner the vendor demo broadcasts: `\0MSN01`.
const List<int> defaultBanner = [0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31];

/// A fake MSU2 device behind a [TransportPort].
final class FakeMsu2Port implements TransportPort {
  FakeMsu2Port({
    TransportPortDescriptor? descriptor,
    SerialSettings settings = const SerialSettings.eightN1(baudRate: 19200),
    List<int>? banner = defaultBanner,
    this.responseByte5 = 0,
    this.responseByte4,
    this.autoRespond = true,
  })  : _descriptor = descriptor ??
            const TransportPortDescriptor(
              transportKey: 'fake-1',
              displayName: 'Fake MSU2',
              kind: 'fake',
            ),
        _settings = settings {
    if (banner != null) _pending.addAll(banner);
  }

  final TransportPortDescriptor _descriptor;
  final SerialSettings _settings;

  final List<int> _pending = <int>[];

  /// Every write the session performed, in order.
  final List<Uint8List> writes = <Uint8List>[];

  /// Byte 5 of the response for ordinary command frames.
  int responseByte5;

  /// Bytes byte 4 of the response for ordinary command frames.
  int? responseByte4;

  /// Counts write calls, including the ones rejected after a simulated unplug.
  int writeAttempts = 0;

  /// When false, command frames are consumed without any response.
  bool autoRespond;

  /// When false, the `\0MSNCN` challenge is not echoed.
  bool echoChallenge = true;

  /// When true, link loss is reported on the next read or write.
  bool disconnected = false;

  /// Extra latency before a response becomes readable.
  Duration responseDelay = Duration.zero;

  /// Overrides the whole response body (used to test desync detection).
  ///
  /// May be longer than 6 bytes. A 7-byte or 11-byte body is a 6-byte echo
  /// plus a `\0MSNxx` banner prefix, which is what a live MINI returned.
  List<int>? forcedResponse;

  /// Appended after every ordinary command echo. Empty by default.
  List<int> echoTail = const [];

  /// u8 SFR values keyed by address. Used when the session reads the
  /// self-description table and then `Lcd_X` / `Lcd_Y`.
  final Map<int, int> sfrU8 = <int, int>{};

  /// u16 SFR values keyed by the 8-bit address (`00 30 20 addr 00 00`).
  ///
  /// MINI firmware V1.2 stores `Lcd_X` at address 0. Address 0 must be readable.
  final Map<int, int> sfrU16 = <int, int>{};

  /// When true, a banner prefix in [echoTail] is completed a few milliseconds
  /// later, the way the rest of an in-flight `\0MSNxx` arrives on the wire.
  bool completeBannerTail = false;

  Future<void> Function()? beforeBulkWrite;

  bool _isClosed = false;

  @override
  TransportPortDescriptor get descriptor => _descriptor;

  @override
  SerialSettings get settings => _settings;

  @override
  bool get isClosed => _isClosed;

  @override
  Future<Uint8List> read(
    int maxBytes, {
    Duration? readTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'fake.read');
    final deadline =
        readTimeout == null ? null : DateTime.now().add(readTimeout);
    while (true) {
      if (disconnected) {
        throw MoliException.disconnected(
          'fake link lost',
          operation: 'fake.read',
        );
      }
      if (_pending.isNotEmpty) {
        final take = maxBytes < _pending.length ? maxBytes : _pending.length;
        final out = Uint8List.fromList(_pending.sublist(0, take));
        _pending.removeRange(0, take);
        return out;
      }
      final now = DateTime.now();
      if (deadline == null || !now.isBefore(deadline)) {
        throw MoliException.timeout(
          'fake device has nothing to send',
          operation: 'fake.read',
        );
      }
      await Future<void>.delayed(const Duration(milliseconds: 1));
      token?.ensureNotCancelled(operation: 'fake.read');
    }
  }

  @override
  Future<void> write(
    List<int> bytes, {
    Duration? writeTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'fake.write');
    writeAttempts++;
    final gate = beforeBulkWrite;
    if (gate != null && bytes.length > kFrameLength) await gate();
    if (disconnected) {
      throw MoliException.disconnected(
        'fake link lost',
        operation: 'fake.write',
      );
    }
    final copy = Uint8List.fromList(bytes);
    writes.add(copy);
    _handleWrite(copy);
  }

  @override
  Future<void> waitForAvailable(
    int count, {
    Duration? timeout,
    CancelToken? token,
  }) async {
    final deadline =
        timeout == null ? null : DateTime.now().add(timeout);
    while (_pending.length < count) {
      token?.ensureNotCancelled(operation: 'fake.waitForAvailable');
      if (disconnected) {
        throw MoliException.disconnected(
          'fake link lost',
          operation: 'fake.waitForAvailable',
        );
      }
      if (deadline != null && !DateTime.now().isBefore(deadline)) {
        throw MoliException.timeout(
          'fake device has only ${_pending.length}/$count bytes',
          operation: 'fake.waitForAvailable',
        );
      }
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
  }

  @override
  Future<void> discardInput() async => _pending.clear();

  @override
  Future<Uint8List> readAvailable() async {
    final out = Uint8List.fromList(_pending);
    _pending.clear();
    return out;
  }

  @override
  Future<void> close() async {
    _isClosed = true;
    disconnected = true;
  }

  /// Instantly marks the link dead, as an unplug would.
  void unplug() {
    disconnected = true;
  }

  /// Raw pending bytes, for assertions.
  List<int> get pendingBytes => List.unmodifiable(_pending);

  /// Pushes bytes the device has already transmitted but the host has not read.
  void inject(List<int> bytes) {
    _pending.addAll(bytes);
  }

  void _handleWrite(Uint8List frame) {
    if (frame.length != kFrameLength) return;

    if (_isChallenge(frame)) {
      if (echoChallenge) _pending.addAll(kHandshakeChallenge);
      return;
    }
    if (!autoRespond) return;
    // Write_LCD_Screen_fast1 / show_PC_Screen do not read these. Echoing them
    // would hide a host that still waits out the command timeout.
    if (_isUnackedStream(frame)) return;

    final override = forcedResponse;
    if (override != null) {
      _enqueue(override);
      return;
    }
    var byte4 = responseByte4 ?? 0;
    var byte5 = responseByte5;
    if (frame[0] == 0x00 && frame[1] == 0x30 && frame[2] == 0x00) {
      final address = (frame[3] << 8) | frame[4];
      byte5 = sfrU8[address] ?? byte5;
    }
    if (frame[0] == 0x00 && frame[1] == 0x30 && frame[2] == 0x20) {
      final value = sfrU16[frame[3]];
      if (value != null) {
        byte4 = (value >> 8) & 0xFF;
        byte5 = value & 0xFF;
      }
    }
    _enqueue(<int>[
      frame[0],
      frame[1],
      frame[0] == 2 && frame[1] == 3 ? frame[2] : 0,
      0,
      byte4,
      byte5,
      ...echoTail,
    ]);
    _maybeCompleteBanner(echoTail);
  }

  void _maybeCompleteBanner(List<int> tail) {
    if (!completeBannerTail || tail.isEmpty || tail.length >= 6) return;
    const prefix = [0x00, 0x4D, 0x53, 0x4E];
    final limit = tail.length < prefix.length ? tail.length : prefix.length;
    for (var i = 0; i < limit; i++) {
      if (tail[i] != prefix[i]) return;
    }
    if (tail.length >= 5 && (tail[4] < 0x30 || tail[4] > 0x39)) return;
    final rest = defaultBanner.sublist(tail.length);
    unawaited(Future<void>.delayed(const Duration(milliseconds: 5), () {
      if (!disconnected) _pending.addAll(rest);
    }));
  }

  void _enqueue(List<int> bytes) {
    if (responseDelay == Duration.zero) {
      _pending.addAll(bytes);
      return;
    }
    unawaited(Future<void>.delayed(responseDelay, () {
      if (!disconnected) _pending.addAll(bytes);
    }));
  }

  static bool _isUnackedStream(List<int> frame) {
    if (frame[0] == 0x04 || frame[0] == 0x09) return true;
    return frame[0] == 0x02 &&
        frame[1] == 0x03 &&
        (frame[2] == 0x08 || frame[2] == 0x09);
  }

  static bool _isChallenge(List<int> frame) {
    const challenge = [0x00, 0x4D, 0x53, 0x4E, 0x43, 0x4E];
    if (frame.length != challenge.length) return false;
    for (var i = 0; i < challenge.length; i++) {
      if (frame[i] != challenge[i]) return false;
    }
    return true;
  }
}

/// A trivial [TransportProvider] serving one fake port.
final class FakeTransportProvider implements TransportProvider {
  FakeTransportProvider(this.portFactory)
      : _descriptor = const TransportPortDescriptor(
          transportKey: 'fake-1',
          displayName: 'Fake MSU2',
          kind: 'fake',
        );

  final FakeMsu2Port Function(TransportPortDescriptor descriptor) portFactory;
  final TransportPortDescriptor _descriptor;

  bool opened = false;
  int listCalls = 0;

  @override
  String get kind => 'fake';

  @override
  Future<List<TransportPortDescriptor>> listPorts() async {
    listCalls++;
    return [_descriptor];
  }

  @override
  Future<TransportPort> open(
    TransportPortDescriptor descriptor, {
    SerialSettings? settingsOverride,
  }) async {
    if (opened) {
      throw MoliException.deviceBusy(
        'fake port already open',
        operation: 'open',
      );
    }
    opened = true;
    return portFactory(descriptor);
  }
}

import 'dart:async';
import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// In-memory port for the connection state machine. Not a device protocol.
final class FakeHostPort implements TransportPort {
  FakeHostPort({
    required TransportPortDescriptor descriptor,
    required this.settings,
    this.failRead,
    this.holdReads = false,
  }) : _descriptor = descriptor;

  final TransportPortDescriptor _descriptor;
  final MoliException? failRead;
  final bool holdReads;
  final List<int> _pending = <int>[0x00, 0x4D, 0x53, 0x4E, 0x30, 0x31];
  final List<Uint8List> writes = <Uint8List>[];
  bool _closed = false;

  /// When set, the next write throws this and clears it.
  MoliException? failWrite;

  /// When set, the next [readAvailable] throws this and clears it.
  MoliException? failReadAvailable;

  @override
  TransportPortDescriptor get descriptor => _descriptor;

  @override
  final SerialSettings settings;

  @override
  bool get isClosed => _closed;

  @override
  Future<Uint8List> read(
    int maxBytes, {
    Duration? readTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'fake.read');
    final failure = failRead;
    if (failure != null) throw failure;
    if (holdReads) {
      while (true) {
        token?.ensureNotCancelled(operation: 'fake.read');
        await Future<void>.delayed(const Duration(milliseconds: 5));
      }
    }
    if (_pending.isEmpty) {
      throw const MoliException.timeout(
        'fake device has nothing to send',
        operation: 'fake.read',
      );
    }
    final take = maxBytes < _pending.length ? maxBytes : _pending.length;
    final out = Uint8List.fromList(_pending.sublist(0, take));
    _pending.removeRange(0, take);
    return out;
  }

  @override
  Future<void> write(
    List<int> bytes, {
    Duration? writeTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'fake.write');
    final failure = failWrite;
    if (failure != null) {
      failWrite = null;
      throw failure;
    }
    writes.add(Uint8List.fromList(bytes));
    if (bytes.length == kHandshakeChallenge.length &&
        _startsWith(bytes, kHandshakeChallenge)) {
      _pending.addAll(kHandshakeChallenge);
      return;
    }
    if (bytes.length >= 2) {
      _pending.addAll(<int>[bytes[0], bytes[1], bytes[0] == 2 ? bytes[2] : 0, 0, 0, 0]);
    }
  }

  @override
  Future<void> waitForAvailable(
    int count, {
    Duration? timeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'fake.waitForAvailable');
  }

  @override
  Future<void> discardInput() async {
    _pending.clear();
  }

  @override
  Future<Uint8List> readAvailable() async {
    final failure = failReadAvailable;
    if (failure != null) {
      failReadAvailable = null;
      throw failure;
    }
    return Uint8List(0);
  }

  @override
  Future<void> close() async {
    _closed = true;
  }
}

final class FakeHostProvider implements TransportProvider {
  FakeHostProvider({
    this.portKey = 'fake-1',
    this.displayName = 'Fake MSU2',
    this.failOpen,
    this.failRead,
    this.holdReads = false,
  });

  final String portKey;
  final String displayName;
  MoliException? failOpen;
  final MoliException? failRead;
  final bool holdReads;

  int listCalls = 0;
  int openCount = 0;
  SerialSettings? lastSettings;
  FakeHostPort? lastPort;

  @override
  String get kind => 'fake';

  TransportPortDescriptor get descriptor => TransportPortDescriptor(
        transportKey: portKey,
        displayName: displayName,
        kind: kind,
      );

  @override
  Future<List<TransportPortDescriptor>> listPorts() async {
    listCalls += 1;
    return <TransportPortDescriptor>[descriptor];
  }

  @override
  Future<TransportPort> open(
    TransportPortDescriptor descriptor, {
    SerialSettings? settingsOverride,
  }) async {
    final failure = failOpen;
    if (failure != null) throw failure;
    openCount += 1;
    lastSettings = settingsOverride;
    final port = FakeHostPort(
      descriptor: descriptor,
      settings: settingsOverride ??
          const SerialSettings.eightN1(baudRate: 19200),
      failRead: failRead,
      holdReads: holdReads,
    );
    lastPort = port;
    return port;
  }
}

bool _startsWith(List<int> bytes, List<int> prefix) {
  if (bytes.length < prefix.length) return false;
  for (var i = 0; i < prefix.length; i++) {
    if (bytes[i] != prefix[i]) return false;
  }
  return true;
}

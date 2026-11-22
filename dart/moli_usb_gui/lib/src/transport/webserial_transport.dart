import 'dart:async';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:web/web.dart' as web;
import 'package:webserial/webserial.dart';

/// Web Serial transport. The browser only returns ports the user has already
/// granted; [requestGrantedPort] is the user-gesture call to `requestPort`.
///
/// This file is only compiled for web builds. It uses `package:webserial` and
/// does not declare its own Web Serial bindings.
final class WebSerialTransportProvider implements TransportProvider {
  WebSerialTransportProvider() {
    _listen();
  }

  final StreamController<void> _changes = StreamController<void>.broadcast();
  final Expando<String> _keys = Expando<String>('moliWebSerialKey');
  final Map<String, JSSerialPort> _ports = <String, JSSerialPort>{};
  final Set<String> _open = <String>{};
  int _seq = 0;

  Stream<void> get changes => _changes.stream;

  @override
  String get kind => 'webserial';

  void _listen() {
    try {
      serial.onconnect = ((web.Event _) {
        _emit();
      }).toJS;
      serial.ondisconnect = ((web.Event _) {
        _emit();
      }).toJS;
    } catch (_) {
      // Unsupported browser or insecure context. listPorts reports it.
    }
  }

  void _emit() {
    if (!_changes.isClosed) _changes.add(null);
  }

  /// Must be called from a click handler with no prior await, so the browser
  /// still treats `requestPort` as a user gesture.
  Future<void> requestGrantedPort() async {
    final JSSerialPort? port;
    try {
      port = await requestWebSerialPort(null);
    } catch (error) {
      throw _mapWeb(error, operation: 'requestPort', userPicker: true);
    }
    if (port == null) {
      throw const MoliException.cancelled(
        '未选择串口',
        operation: 'requestPort',
      );
    }
    _ports[_keyFor(port)] = port;
    _emit();
  }

  @override
  Future<List<TransportPortDescriptor>> listPorts() async {
    final JSArray<JSSerialPort?> listed;
    try {
      listed = await serial.getPorts().toDart;
    } catch (error) {
      throw MoliException.disconnected(
        '浏览器无法列出已授权串口',
        operation: 'listPorts',
        cause: error,
      );
    }
    final seen = <String>{};
    final descriptors = <TransportPortDescriptor>[];
    for (final port in listed.toDart) {
      if (port == null) continue;
      final key = _keyFor(port);
      seen.add(key);
      _ports[key] = port;
      descriptors.add(_describe(port, key, descriptors.length));
    }
    _ports.removeWhere((key, _) => !seen.contains(key) && !_open.contains(key));
    return descriptors;
  }

  @override
  Future<TransportPort> open(
    TransportPortDescriptor descriptor, {
    SerialSettings? settingsOverride,
  }) async {
    final key = descriptor.transportKey;
    final jsPort = _ports[key];
    if (jsPort == null) {
      throw MoliException.deviceNotFound(
        '已授权串口 $key 不在列表中',
        operation: 'open',
      );
    }
    if (_open.contains(key)) {
      throw MoliException.deviceBusy(
        '串口 ${descriptor.displayName} 已被本程序打开',
        operation: 'open',
      );
    }
    final settings = settingsOverride ??
        descriptor.confirmedSettings ??
        descriptor.suggestedSettings ??
        const SerialSettings.eightN1(baudRate: 19200);
    final opened = _WebSerialPort(
      jsPort: jsPort,
      descriptor: descriptor,
      settings: settings,
      onClosed: () {
        _open.remove(key);
        _emit();
      },
      onLost: _emit,
    );
    _open.add(key);
    try {
      await opened.open();
      return opened;
    } catch (error) {
      _open.remove(key);
      if (error is MoliException) rethrow;
      throw _mapWeb(error, operation: 'open');
    }
  }

  String _keyFor(JSSerialPort port) {
    final existing = _keys[port];
    if (existing != null) return existing;
    final key = 'webserial-$_seq';
    _seq += 1;
    _keys[port] = key;
    return key;
  }

  TransportPortDescriptor _describe(JSSerialPort port, String key, int index) {
    int? vendorId;
    int? productId;
    try {
      final info = port.getInfo();
      vendorId = info.usbVendorId;
      productId = info.usbProductId;
    } catch (_) {
      vendorId = null;
      productId = null;
    }
    final hasUsb = (vendorId != null && vendorId != 0) ||
        (productId != null && productId != 0);
    final label = hasUsb
        ? '已授权串口 ${_hex(vendorId)}:${_hex(productId)}'
        : '已授权串口 ${index + 1}';
    return TransportPortDescriptor(
      transportKey: key,
      displayName: label,
      kind: kind,
      usb: hasUsb
          ? TransportUsbMetadata(vendorId: vendorId, productId: productId)
          : null,
    );
  }
}

String _hex(int? value) => (value ?? 0).toRadixString(16).padLeft(4, '0');

final class _WebSerialPort implements TransportPort {
  _WebSerialPort({
    required JSSerialPort jsPort,
    required this.descriptor,
    required this.settings,
    required void Function() onClosed,
    required void Function() onLost,
  })  : _jsPort = jsPort,
        _onClosed = onClosed,
        _onLost = onLost;

  final JSSerialPort _jsPort;
  final void Function() _onClosed;
  final void Function() _onLost;

  @override
  final TransportPortDescriptor descriptor;

  @override
  final SerialSettings settings;

  web.ReadableStreamDefaultReader? _reader;
  web.WritableStreamDefaultWriter? _writer;
  final List<int> _incoming = <int>[];
  Completer<void>? _arrived;
  bool _closed = false;
  bool _lost = false;
  Object? _lostCause;

  @override
  bool get isClosed => _closed;

  Future<void> open() async {
    try {
      await _jsPort
          .open(
            JSSerialOptions(
              baudRate: settings.baudRate,
              dataBits: settings.dataBits,
              stopBits: settings.stopBits == SerialStopBits.two ? 2 : 1,
              parity: _parity(settings.parity),
              bufferSize: 4096,
              flowControl: settings.rtscts ? 'hardware' : 'none',
            ),
          )
          .toDart;
    } catch (error) {
      throw _mapWeb(error, operation: 'open');
    }
    final readable = _jsPort.readable;
    final writable = _jsPort.writable;
    if (readable == null || writable == null) {
      throw const MoliException.disconnected(
        'Web Serial 未提供读写流',
        operation: 'open',
      );
    }
    _reader = readable.getReader() as web.ReadableStreamDefaultReader;
    _writer = writable.getWriter();
    _jsPort.ondisconnect = ((web.Event _) {
      _markLost(const MoliException.disconnected(
        '浏览器报告串口已断开',
        operation: 'disconnect',
      ));
    }).toJS;
    unawaited(_pump());
  }

  Future<void> _pump() async {
    final reader = _reader;
    if (reader == null) return;
    while (!_closed) {
      try {
        final result = await reader.read().toDart;
        if (result.done) {
          _markLost(const MoliException.disconnected(
            'Web Serial 读流已结束',
            operation: 'read',
          ));
          return;
        }
        final bytes = _bytesOf(result.value);
        if (bytes == null || bytes.isEmpty) continue;
        _incoming.addAll(bytes);
        _signal();
      } catch (error) {
        if (_closed) return;
        _markLost(_mapWeb(error, operation: 'read'));
        return;
      }
    }
  }

  @override
  Future<Uint8List> read(
    int maxBytes, {
    Duration? readTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'read');
    _throwIfGone(operation: 'read');
    if (maxBytes <= 0) return Uint8List(0);
    if (_incoming.isNotEmpty) return _take(maxBytes);
    final deadline = readTimeout == null ? null : DateTime.now().add(readTimeout);
    while (_incoming.isEmpty) {
      token?.ensureNotCancelled(operation: 'read');
      _throwIfGone(operation: 'read');
      final left = deadline?.difference(DateTime.now());
      if (left != null && left <= Duration.zero) {
        throw const MoliException.timeout('读取超时', operation: 'read');
      }
      final slice = left == null || left > const Duration(milliseconds: 50)
          ? const Duration(milliseconds: 50)
          : left;
      final waiter = _arrived ??= Completer<void>();
      try {
        await waiter.future.timeout(slice);
      } on TimeoutException {
        // Check cancel, deadline, and any bytes the pump just stored.
      }
    }
    return _take(maxBytes);
  }

  @override
  Future<void> write(
    List<int> bytes, {
    Duration? writeTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'write');
    _throwIfGone(operation: 'write');
    if (bytes.isEmpty) return;
    final writer = _writer;
    if (writer == null) {
      throw const MoliException.disconnected(
        'Web Serial 写流不可用',
        operation: 'write',
      );
    }
    final payload = Uint8List.fromList(bytes);
    try {
      await writer.write(payload.toJS).toDart.timeout(
            writeTimeout ?? const Duration(seconds: 2),
          );
    } on TimeoutException {
      throw const MoliException.disconnected('Web Serial 写入超时，无法确定已发送字节数', operation: 'write');
    } catch (error) {
      if (error is MoliException) rethrow;
      throw _mapWeb(error, operation: 'write');
    }
  }

  @override
  Future<void> waitForAvailable(
    int count, {
    Duration? timeout,
    CancelToken? token,
  }) async {
    if (count <= 0) return;
    final deadline = timeout == null ? null : DateTime.now().add(timeout);
    while (_incoming.length < count) {
      token?.ensureNotCancelled(operation: 'waitForAvailable');
      _throwIfGone(operation: 'waitForAvailable');
      if (deadline != null && !DateTime.now().isBefore(deadline)) {
        throw MoliException.timeout(
          '等待数据超时（已有 ${_incoming.length}/$count 字节）',
          operation: 'waitForAvailable',
        );
      }
      final waiter = _arrived ??= Completer<void>();
      try {
        await waiter.future.timeout(const Duration(milliseconds: 50));
      } on TimeoutException {
        continue;
      }
    }
  }

  @override
  Future<void> discardInput() async {
    _incoming.clear();
  }

  @override
  Future<Uint8List> readAvailable() async {
    _throwIfGone(operation: 'readAvailable');
    if (_incoming.isEmpty) return Uint8List(0);
    return _take(_incoming.length);
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _signal();
    final reader = _reader;
    final writer = _writer;
    _reader = null;
    _writer = null;
    try {
      await reader?.cancel().toDart;
    } catch (_) {}
    try {
      writer?.releaseLock();
    } catch (_) {}
    try {
      await _jsPort.close().toDart;
    } catch (_) {}
    _onClosed();
  }

  Uint8List _take(int maxBytes) {
    final count = maxBytes < _incoming.length ? maxBytes : _incoming.length;
    final out = Uint8List.fromList(_incoming.sublist(0, count));
    _incoming.removeRange(0, count);
    return out;
  }

  void _signal() {
    final waiter = _arrived;
    _arrived = null;
    if (waiter != null && !waiter.isCompleted) waiter.complete();
  }

  void _markLost(Object cause) {
    if (_lost || _closed) return;
    _lost = true;
    _lostCause = cause;
    _signal();
    _onLost();
  }

  void _throwIfGone({required String operation}) {
    if (_closed || _lost) {
      final cause = _lostCause;
      if (cause is MoliException) {
        throw cause.withContext(operation: operation);
      }
      throw MoliException.disconnected(
        '串口已断开',
        operation: operation,
        cause: cause,
      );
    }
  }
}

String _parity(SerialParity parity) {
  return switch (parity) {
    SerialParity.none => 'none',
    SerialParity.even => 'even',
    SerialParity.odd => 'odd',
  };
}

Uint8List? _bytesOf(JSAny? value) {
  if (value == null) return null;
  if (value.isA<JSUint8Array>()) {
    return (value as JSUint8Array).toDart;
  }
  final dartified = value.dartify();
  if (dartified is Uint8List) return dartified;
  if (dartified is List) {
    return Uint8List.fromList(dartified.cast<int>());
  }
  return null;
}

/// DOMException name is the platform error code. The serial API itself comes
/// from `package:webserial`; this only classifies the rejection reason.
extension type _DomException(JSObject _) implements JSObject {
  external String get name;
  external String get message;
}

MoliException _mapWeb(
  Object error, {
  required String operation,
  bool userPicker = false,
}) {
  String? name;
  var message = '$error';
  // `is` is not a valid runtime check for JS interop types. `isA` is the
  // platform-independent check, and only then is the cast safe.
  if (error.isA<JSObject>()) {
    try {
      final dom = _DomException(error as JSObject);
      name = dom.name;
      if (dom.message.isNotEmpty) message = dom.message;
    } catch (_) {
      // Keep the Dart string form when the rejection is not a DOMException.
    }
  }
  final kind = switch (name) {
    'NotFoundError' when userPicker => MoliErrorKind.cancelled,
    'NotFoundError' => MoliErrorKind.deviceNotFound,
    'InvalidStateError' => MoliErrorKind.deviceBusy,
    'AbortError' => MoliErrorKind.cancelled,
    'NetworkError' => MoliErrorKind.disconnected,
    _ => MoliErrorKind.disconnected,
  };
  return MoliException(
    kind,
    message.isEmpty ? 'Web Serial 失败' : message,
    operation: operation,
    cause: error,
  );
}

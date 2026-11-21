import 'dart:async';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:flutter_libserialport/flutter_libserialport.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import 'libserialport_worker.dart';

/// Satisfies [LibserialportWorkerRequest.reply] for requests that [_rpc]
/// copies onto a fresh [ReceivePort]. Never sent to.
final SendPort _unusedReply = _closedSendPort();

SendPort _closedSendPort() {
  final inbox = ReceivePort();
  final send = inbox.sendPort;
  inbox.close();
  return send;
}

/// Windows transport. Enumeration uses [SerialPort.availablePorts] and never
/// opens a port. Reads and writes run on a worker isolate because
/// libserialport's positive-timeout calls block the calling thread.
///
/// Hardware flow control is [SerialPortFlowControl.rtsCts] via
/// [SerialPortConfig.setFlowControl]. The package can set RTS/CTS, so this
/// provider does not fall back to `serial_port_win32`.
final class LibserialportTransportProvider implements TransportProvider {
  final Set<String> _owned = <String>{};

  @override
  String get kind => 'libserialport';

  @override
  Future<List<TransportPortDescriptor>> listPorts() async {
    final List<String> names;
    try {
      names = SerialPort.availablePorts;
    } on SerialPortError catch (error) {
      throw MoliException.disconnected(
        '枚举串口失败: ${error.message}',
        operation: 'listPorts',
        cause: error,
      );
    }
    final descriptors = <TransportPortDescriptor>[];
    for (final name in names) {
      descriptors.add(_describe(name));
    }
    return descriptors;
  }

  TransportPortDescriptor _describe(String name) {
    SerialPort? probe;
    try {
      // sp_get_port_by_name allocates a struct. It does not open the device.
      probe = SerialPort(name);
      final description = probe.description;
      final product = probe.productName;
      final label = _label(name, description, product);
      return TransportPortDescriptor(
        transportKey: name,
        displayName: label,
        kind: kind,
        usb: _usb(probe),
      );
    } catch (error) {
      return TransportPortDescriptor(
        transportKey: name,
        displayName: name,
        kind: kind,
      );
    } finally {
      probe?.dispose();
    }
  }

  @override
  Future<TransportPort> open(
    TransportPortDescriptor descriptor, {
    SerialSettings? settingsOverride,
  }) async {
    final name = descriptor.transportKey;
    if (_owned.contains(name)) {
      throw MoliException.deviceBusy(
        '串口 $name 已被本程序打开',
        operation: 'open',
      );
    }
    if (!SerialPort.availablePorts.contains(name)) {
      throw MoliException.deviceNotFound(
        '串口 $name 不存在',
        operation: 'open',
      );
    }
    final settings = settingsOverride ??
        descriptor.confirmedSettings ??
        descriptor.suggestedSettings ??
        const SerialSettings.eightN1(baudRate: 19200);
    _owned.add(name);
    try {
      final port = await _LibserialportPort.open(
        descriptor: descriptor,
        settings: settings,
        onClosed: () => _owned.remove(name),
      );
      return port;
    } catch (error) {
      _owned.remove(name);
      if (error is MoliException) rethrow;
      throw MoliException.disconnected(
        '打开串口 $name 失败',
        operation: 'open',
        cause: error,
      );
    }
  }
}

String _label(String name, String? description, String? product) {
  final parts = <String>[name];
  if (description != null && description.isNotEmpty && description != name) {
    parts.add(description);
  } else if (product != null && product.isNotEmpty) {
    parts.add(product);
  }
  return parts.join(' · ');
}

TransportUsbMetadata? _usb(SerialPort probe) {
  final vendorId = probe.vendorId;
  final productId = probe.productId;
  final manufacturer = probe.manufacturer;
  final product = probe.productName;
  final serialNumber = probe.serialNumber;
  if (vendorId == null &&
      productId == null &&
      (manufacturer == null || manufacturer.isEmpty) &&
      (product == null || product.isEmpty) &&
      (serialNumber == null || serialNumber.isEmpty)) {
    return null;
  }
  return TransportUsbMetadata(
    vendorId: vendorId,
    productId: productId,
    manufacturer: manufacturer,
    product: product,
    serialNumber: serialNumber,
  );
}

final class _LibserialportPort implements TransportPort {
  _LibserialportPort({
    required this.descriptor,
    required this.settings,
    required SendPort worker,
    required Isolate isolate,
    required void Function() onClosed,
  })  : _worker = worker,
        _isolate = isolate,
        _onClosed = onClosed;

  static Future<_LibserialportPort> open({
    required TransportPortDescriptor descriptor,
    required SerialSettings settings,
    required void Function() onClosed,
  }) async {
    final ready = ReceivePort();
    final Isolate isolate;
    try {
      isolate = await Isolate.spawn(
        libserialportWorker,
        ready.sendPort,
        debugName: 'libserialport-${descriptor.transportKey}',
      );
    } catch (error) {
      ready.close();
      throw MoliException.disconnected(
        '无法启动串口工作隔离',
        operation: 'open',
        cause: error,
      );
    }
    final worker = await ready.first as SendPort;
    ready.close();
    final port = _LibserialportPort(
      descriptor: descriptor,
      settings: settings,
      worker: worker,
      isolate: isolate,
      onClosed: onClosed,
    );
    try {
      await port._rpc(
        LibserialportWorkerRequest(
          op: LibserialportWorkerRequest.opOpen,
          reply: _unusedReply,
          portName: descriptor.transportKey,
          baudRate: settings.baudRate,
          dataBits: settings.dataBits,
          parity: _parity(settings.parity),
          stopBits: settings.stopBits == SerialStopBits.two ? 2 : 1,
          rtscts: settings.rtscts,
          xonxoff: settings.xonxoff,
        ),
        timeout: const Duration(seconds: 5),
      );
    } catch (error) {
      port._isolate.kill(priority: Isolate.immediate);
      rethrow;
    }
    return port;
  }

  @override
  final TransportPortDescriptor descriptor;

  @override
  final SerialSettings settings;

  final SendPort _worker;
  final Isolate _isolate;
  final void Function() _onClosed;
  bool _closed = false;

  @override
  bool get isClosed => _closed;

  @override
  Future<Uint8List> read(
    int maxBytes, {
    Duration? readTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'read');
    _ensureOpen();
    if (maxBytes <= 0) return Uint8List(0);
    final deadline = readTimeout == null ? null : DateTime.now().add(readTimeout);
    const slice = Duration(milliseconds: 50);
    while (true) {
      token?.ensureNotCancelled(operation: 'read');
      _ensureOpen();
      var sliceMs = slice.inMilliseconds;
      if (deadline != null) {
        final left = deadline.difference(DateTime.now());
        if (left <= Duration.zero) {
          throw const MoliException.timeout(
            '读取超时',
            operation: 'read',
          );
        }
        if (left < slice) {
          sliceMs = left.inMilliseconds.clamp(1, slice.inMilliseconds);
        }
      }
      final result = await _rpc(
        LibserialportWorkerRequest(
          op: LibserialportWorkerRequest.opRead,
          reply: _unusedReply,
          maxBytes: maxBytes,
          timeoutMs: sliceMs,
        ),
        timeout: Duration(milliseconds: sliceMs + 1000),
      );
      final bytes = result.bytes;
      if (bytes != null && bytes.isNotEmpty) return bytes;
    }
  }

  @override
  Future<void> write(
    List<int> bytes, {
    Duration? writeTimeout,
    CancelToken? token,
  }) async {
    token?.ensureNotCancelled(operation: 'write');
    _ensureOpen();
    if (bytes.isEmpty) return;
    final data = bytes is Uint8List ? bytes : Uint8List.fromList(bytes);
    final deadline = DateTime.now().add(writeTimeout ?? const Duration(seconds: 2));
    var offset = 0;
    while (offset < data.length) {
      token?.ensureNotCancelled(operation: 'write');
      _ensureOpen();
      final left = deadline.difference(DateTime.now());
      if (left <= Duration.zero) {
        throw const MoliException.timeout(
          '写入超时',
          operation: 'write',
        );
      }
      final sliceMs = left.inMilliseconds.clamp(1, 50);
      final end = offset + 256 < data.length ? offset + 256 : data.length;
      final result = await _rpc(
        LibserialportWorkerRequest(
          op: LibserialportWorkerRequest.opWrite,
          reply: _unusedReply,
          timeoutMs: sliceMs,
          bytes: Uint8List.sublistView(data, offset, end),
        ),
        timeout: Duration(milliseconds: sliceMs + 1000),
      );
      final written = result.intValue ?? 0;
      if (written < 0) {
        throw const MoliException.disconnected(
          '写入失败',
          operation: 'write',
        );
      }
      if (written == 0) continue;
      offset += written;
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
    while (true) {
      token?.ensureNotCancelled(operation: 'waitForAvailable');
      _ensureOpen();
      if (deadline != null && !DateTime.now().isBefore(deadline)) {
        throw const MoliException.timeout(
          '等待数据超时',
          operation: 'waitForAvailable',
        );
      }
      final waiting = await _rpc(
        LibserialportWorkerRequest(
          op: LibserialportWorkerRequest.opWaiting,
          reply: _unusedReply,
        ),
        timeout: const Duration(seconds: 2),
      );
      if ((waiting.intValue ?? 0) >= count) return;
      await Future<void>.delayed(const Duration(milliseconds: 20));
    }
  }

  @override
  Future<void> discardInput() async {
    if (_closed) return;
    await _rpc(
      LibserialportWorkerRequest(
        op: LibserialportWorkerRequest.opDiscard,
        reply: _unusedReply,
      ),
      timeout: const Duration(seconds: 2),
    );
  }

  @override
  Future<Uint8List> readAvailable() async {
    _ensureOpen();
    final result = await _rpc(
      LibserialportWorkerRequest(
        op: LibserialportWorkerRequest.opAvailable,
        reply: _unusedReply,
      ),
      timeout: const Duration(seconds: 2),
    );
    return result.bytes ?? Uint8List(0);
  }

  @override
  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _onClosed();
    try {
      await _rpc(
        LibserialportWorkerRequest(
          op: LibserialportWorkerRequest.opClose,
          reply: _unusedReply,
        ),
        timeout: const Duration(seconds: 2),
      );
    } catch (_) {
      // The link is already gone. close() stays idempotent.
    } finally {
      _isolate.kill(priority: Isolate.immediate);
    }
  }

  void _ensureOpen() {
    if (_closed) {
      throw const MoliException.disconnected(
        '串口已关闭',
        operation: 'port',
      );
    }
  }

  Future<LibserialportWorkerResult> _rpc(
    LibserialportWorkerRequest request, {
    required Duration timeout,
  }) async {
    final inbox = ReceivePort();
    final sent = LibserialportWorkerRequest(
      op: request.op,
      reply: inbox.sendPort,
      portName: request.portName,
      baudRate: request.baudRate,
      dataBits: request.dataBits,
      parity: request.parity,
      stopBits: request.stopBits,
      rtscts: request.rtscts,
      xonxoff: request.xonxoff,
      maxBytes: request.maxBytes,
      timeoutMs: request.timeoutMs,
      bytes: request.bytes,
    );
    try {
      _worker.send(sent);
      final response = await inbox.first.timeout(timeout);
      if (response is! LibserialportWorkerResult) {
        throw const MoliException.disconnected(
          '串口工作隔离返回了无法识别的结果',
          operation: 'port',
        );
      }
      final kind = response.errorKind;
      if (kind != null) {
        throw _asException(kind, response.errorMessage ?? '串口失败', request.op);
      }
      return response;
    } on TimeoutException {
      throw const MoliException.disconnected(
        '串口工作隔离无响应',
        operation: 'port',
      );
    } finally {
      inbox.close();
    }
  }
}

int _parity(SerialParity parity) {
  return switch (parity) {
    SerialParity.none => SerialPortParity.none,
    SerialParity.even => SerialPortParity.even,
    SerialParity.odd => SerialPortParity.odd,
  };
}

MoliException _asException(String kind, String message, String operation) {
  final errorKind = switch (kind) {
    'deviceNotFound' => MoliErrorKind.deviceNotFound,
    'deviceBusy' => MoliErrorKind.deviceBusy,
    'timeout' => MoliErrorKind.timeout,
    'cancelled' => MoliErrorKind.cancelled,
    'invalidArgument' => MoliErrorKind.invalidArgument,
    _ => MoliErrorKind.disconnected,
  };
  return MoliException(errorKind, message, operation: operation);
}

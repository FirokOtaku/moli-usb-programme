import 'dart:isolate';
import 'dart:typed_data';

import 'package:flutter_libserialport/flutter_libserialport.dart';

/// Cross-isolate command. Every field is sendable; the native port stays in
/// the worker so blocking libserialport calls never run on the UI isolate.
final class LibserialportWorkerRequest {
  const LibserialportWorkerRequest({
    required this.op,
    required this.reply,
    this.portName,
    this.baudRate = 0,
    this.dataBits = 8,
    this.parity = 0,
    this.stopBits = 1,
    this.rtscts = false,
    this.xonxoff = false,
    this.maxBytes = 0,
    this.timeoutMs = -1,
    this.bytes,
  });

  static const opOpen = 'open';
  static const opRead = 'read';
  static const opWrite = 'write';
  static const opAvailable = 'available';
  static const opWaiting = 'waiting';
  static const opDiscard = 'discard';
  static const opClose = 'close';

  final String op;
  final SendPort reply;
  final String? portName;
  final int baudRate;
  final int dataBits;
  final int parity;
  final int stopBits;
  final bool rtscts;
  final bool xonxoff;
  final int maxBytes;
  final int timeoutMs;
  final Uint8List? bytes;
}

/// Reply from the worker. [errorKind] is a [MoliErrorKind] name, not a message
/// to be parsed for control flow on the caller side.
final class LibserialportWorkerResult {
  const LibserialportWorkerResult.ok({this.bytes, this.intValue})
      : errorKind = null,
        errorMessage = null;

  const LibserialportWorkerResult.fail(this.errorKind, this.errorMessage)
      : bytes = null,
        intValue = null;

  final String? errorKind;
  final String? errorMessage;
  final Uint8List? bytes;
  final int? intValue;
}

/// Entry point. Owns the [SerialPort] for one open.
void libserialportWorker(SendPort toMain) {
  final inbox = ReceivePort();
  toMain.send(inbox.sendPort);
  SerialPort? port;
  inbox.listen((message) {
    if (message is! LibserialportWorkerRequest) return;
    try {
      final outcome = _handle(message, port);
      port = outcome.port;
      message.reply.send(outcome.result);
      if (message.op == LibserialportWorkerRequest.opClose) {
        inbox.close();
      }
    } catch (error) {
      message.reply.send(
        LibserialportWorkerResult.fail('disconnected', '$error'),
      );
    }
  });
}

final class _Outcome {
  const _Outcome(this.port, this.result);
  final SerialPort? port;
  final LibserialportWorkerResult result;
}

_Outcome _handle(LibserialportWorkerRequest request, SerialPort? port) {
  switch (request.op) {
    case LibserialportWorkerRequest.opOpen:
      return _open(request);
    case LibserialportWorkerRequest.opRead:
      return _Outcome(port, _read(port, request));
    case LibserialportWorkerRequest.opWrite:
      return _Outcome(port, _write(port, request));
    case LibserialportWorkerRequest.opAvailable:
      return _Outcome(port, _available(port));
    case LibserialportWorkerRequest.opWaiting:
      return _Outcome(port, _waiting(port));
    case LibserialportWorkerRequest.opDiscard:
      return _Outcome(port, _discard(port));
    case LibserialportWorkerRequest.opClose:
      return _Outcome(null, _close(port));
    default:
      return _Outcome(
        port,
        LibserialportWorkerResult.fail('invalidArgument', 'unknown op ${request.op}'),
      );
  }
}

_Outcome _open(LibserialportWorkerRequest request) {
  final name = request.portName;
  if (name == null || name.isEmpty) {
    return _Outcome(
      null,
      const LibserialportWorkerResult.fail('deviceNotFound', 'missing port name'),
    );
  }
  if (!SerialPort.availablePorts.contains(name)) {
    return _Outcome(
      null,
      LibserialportWorkerResult.fail('deviceNotFound', 'port $name is gone'),
    );
  }

  final SerialPort port;
  try {
    port = SerialPort(name);
  } on SerialPortError catch (error) {
    return _Outcome(null, _failOpen(error));
  }

  if (!port.openReadWrite()) {
    final error = SerialPort.lastError;
    port.dispose();
    return _Outcome(null, _failOpen(error, name: name));
  }

  final config = SerialPortConfig();
  try {
    config.baudRate = request.baudRate;
    config.bits = request.dataBits;
    config.parity = request.parity;
    config.stopBits = request.stopBits;
    // flutter_libserialport can set hardware flow control. LITE/PRO need
    // 921600 + RTS/CTS; setFlowControl(rtsCts) is that preset. Do not fall
    // back to another Windows package.
    if (request.rtscts) {
      config.setFlowControl(SerialPortFlowControl.rtsCts);
    } else if (request.xonxoff) {
      config.setFlowControl(SerialPortFlowControl.xonXoff);
    } else {
      config.setFlowControl(SerialPortFlowControl.none);
    }
    // pySerial's defaults assert DTR and, when RTS is not handshake-driven,
    // assert RTS. Do not inherit modem-line levels from a prior COM client.
    config.dtr = SerialPortDtr.on;
    if (!request.rtscts) config.rts = SerialPortRts.on;
    port.config = config;
  } on SerialPortError catch (error) {
    port.close();
    port.dispose();
    final kind = error.errorCode == -4 || error.errorCode == -1
        ? 'invalidArgument'
        : 'disconnected';
    return _Outcome(
      null,
      LibserialportWorkerResult.fail(kind, _errorText(error)),
    );
  }
  return _Outcome(port, const LibserialportWorkerResult.ok());
}

LibserialportWorkerResult _read(SerialPort? port, LibserialportWorkerRequest request) {
  if (port == null || !port.isOpen) {
    return const LibserialportWorkerResult.fail('disconnected', 'port is closed');
  }
  final maxBytes = request.maxBytes;
  if (maxBytes <= 0) return LibserialportWorkerResult.ok(bytes: Uint8List(0));
  // timeout < 0 is non-blocking in this package; 0 waits forever. A positive
  // timeout returns as soon as [count] bytes arrive, so count is 1.
  final timeoutMs = request.timeoutMs <= 0 ? 1 : request.timeoutMs;
  try {
    final first = port.read(1, timeout: timeoutMs);
    if (first.isEmpty) {
      return LibserialportWorkerResult.ok(bytes: Uint8List(0));
    }
    final waiting = port.bytesAvailable;
    if (waiting < 0) {
      return LibserialportWorkerResult.fail(
        'disconnected',
        'input waiting failed ($waiting)',
      );
    }
    final room = maxBytes - first.length;
    if (waiting == 0 || room <= 0) {
      return LibserialportWorkerResult.ok(bytes: first);
    }
    final want = waiting < room ? waiting : room;
    // timeout -1 is sp_nonblocking_read. The bytes are already buffered.
    final rest = port.read(want, timeout: -1);
    if (rest.isEmpty) return LibserialportWorkerResult.ok(bytes: first);
    final out = Uint8List(first.length + rest.length);
    out.setAll(0, first);
    out.setAll(first.length, rest);
    return LibserialportWorkerResult.ok(bytes: out);
  } on SerialPortError catch (error) {
    return LibserialportWorkerResult.fail('disconnected', _errorText(error));
  }
}

LibserialportWorkerResult _write(SerialPort? port, LibserialportWorkerRequest request) {
  if (port == null || !port.isOpen) {
    return const LibserialportWorkerResult.fail('disconnected', 'port is closed');
  }
  final bytes = request.bytes;
  if (bytes == null || bytes.isEmpty) {
    return const LibserialportWorkerResult.ok(intValue: 0);
  }
  final timeoutMs = request.timeoutMs <= 0 ? 1 : request.timeoutMs;
  try {
    final written = port.write(bytes, timeout: timeoutMs);
    return LibserialportWorkerResult.ok(intValue: written);
  } on SerialPortError catch (error) {
    return LibserialportWorkerResult.fail('disconnected', _errorText(error));
  }
}

LibserialportWorkerResult _available(SerialPort? port) {
  if (port == null || !port.isOpen) {
    return const LibserialportWorkerResult.fail('disconnected', 'port is closed');
  }
  final waiting = port.bytesAvailable;
  if (waiting < 0) {
    return LibserialportWorkerResult.fail(
      'disconnected',
      'input waiting failed ($waiting)',
    );
  }
  if (waiting == 0) {
    return LibserialportWorkerResult.ok(bytes: Uint8List(0));
  }
  try {
    final bytes = port.read(waiting, timeout: -1);
    return LibserialportWorkerResult.ok(bytes: bytes);
  } on SerialPortError catch (error) {
    return LibserialportWorkerResult.fail('disconnected', _errorText(error));
  }
}

LibserialportWorkerResult _waiting(SerialPort? port) {
  if (port == null || !port.isOpen) {
    return const LibserialportWorkerResult.fail('disconnected', 'port is closed');
  }
  final waiting = port.bytesAvailable;
  if (waiting < 0) {
    return LibserialportWorkerResult.fail(
      'disconnected',
      'input waiting failed ($waiting)',
    );
  }
  return LibserialportWorkerResult.ok(intValue: waiting);
}

LibserialportWorkerResult _discard(SerialPort? port) {
  if (port == null || !port.isOpen) {
    return const LibserialportWorkerResult.ok();
  }
  port.flush(SerialPortBuffer.input);
  return const LibserialportWorkerResult.ok();
}

LibserialportWorkerResult _close(SerialPort? port) {
  if (port == null) return const LibserialportWorkerResult.ok();
  try {
    if (port.isOpen) port.close();
  } on SerialPortError {
    // Closing a lost link is still success: the caller is done with the port.
  }
  port.dispose();
  return const LibserialportWorkerResult.ok();
}

LibserialportWorkerResult _failOpen(SerialPortError? error, {String? name}) {
  final code = error?.errorCode;
  final kind = switch (code) {
    2 || 3 || 433 || 1167 => 'deviceNotFound',
    5 || 32 => 'deviceBusy',
    _ => 'deviceBusy',
  };
  final where = name == null ? 'open failed' : 'open $name failed';
  final detail = error == null ? where : '$where: ${_errorText(error)}';
  return LibserialportWorkerResult.fail(kind, detail);
}

String _errorText(SerialPortError error) {
  final message = error.message.trim();
  if (message.isEmpty) return 'code ${error.errorCode}';
  return '$message (code ${error.errorCode})';
}

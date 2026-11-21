// 6-byte command frame encoder / response matcher.
//
// Frame layout (spec/msu2-wire-protocol.md):
//   byte 0     byte 1       byte 2       byte 3       byte 4       byte 5
//   command    subcommand   data0        data1        data2        data3
//
// There is no sync header, no length, no checksum and no request id. Multiple
// complete frames may be concatenated in one transport write. Multi-byte
// integers are big-endian (high byte first).

import 'dart:typed_data';

import '../errors.dart';
import 'binary.dart';

/// The well known 6-byte frame length.
const int kFrameLength = 6;

/// Builds one 6-byte command frame.
class Frame {
  const Frame._(this.bytes);

  factory Frame({
    required int command,
    int subcommand = 0,
    int data0 = 0,
    int data1 = 0,
    int data2 = 0,
    int data3 = 0,
  }) {
    _checkByte('command', command);
    _checkByte('subcommand', subcommand);
    _checkByte('data0', data0);
    _checkByte('data1', data1);
    _checkByte('data2', data2);
    _checkByte('data3', data3);
    final bytes = Uint8List(kFrameLength);
    bytes[0] = command;
    bytes[1] = subcommand;
    bytes[2] = data0;
    bytes[3] = data1;
    bytes[4] = data2;
    bytes[5] = data3;
    return Frame._(bytes);
  }

  /// Wraps already-validated [bytes] from the device into a frame.
  ///
  /// Used by the session layer, which has just read exactly [kFrameLength]
  /// bytes and must not re-run the range validation of the builder.
  factory Frame.fromBytes(List<int> bytes) {
    if (bytes.length != kFrameLength) {
      throw MoliException.malformedResponse(
        'frame must be exactly $kFrameLength bytes (got ${bytes.length})',
        operation: 'Frame.fromBytes',
      );
    }
    for (var i = 0; i < kFrameLength; i++) {
      final b = bytes[i];
      if (b < 0 || b > 0xFF) {
        throw MoliException.malformedResponse(
          'frame byte $i out of range: $b',
          operation: 'Frame.fromBytes',
        );
      }
    }
    return Frame._(Uint8List.fromList(bytes));
  }

  static void _checkByte(String name, int value) {
    if (value < 0 || value > 0xFF) {
      throw MoliException.invalidArgument(
        '$name must fit in one byte (got $value)',
        operation: 'Frame',
      );
    }
  }

  final Uint8List bytes;

  int get command => bytes[0];
  int get subcommand => bytes[1];

  /// u16 at data0 (big-endian).
  int get data0AsU16 => (bytes[2] << 8) | bytes[3];

  /// u16 at data2 (big-endian).
  int get data2AsU16 => (bytes[4] << 8) | bytes[5];

  @override
  String toString() => 'Frame(${bytes.map((b) => b.toRadixString(16)
      .padLeft(2, '0')).join(' ')})';

  @override
  bool operator ==(Object other) =>
      other is Frame &&
      other.bytes.length == bytes.length &&
      _equalBytes(other.bytes);

  bool _equalBytes(Uint8List other) {
    for (var i = 0; i < bytes.length; i++) {
      if (bytes[i] != other[i]) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hash(command, subcommand, data0AsU16, data2AsU16);
}

/// Decodes a stream of concatenated frames, tolerating partial chunks.
///
/// The parser accumulates bytes and only emits complete 6-byte frames. It never
/// claims a byte is part of a frame when fewer than 6 bytes are available, and
/// it never silently drops bytes: a caller can inspect the leftover via
/// [takePending] when it wants to verify that nothing unaccounted remains.
class FrameParser {
  final List<int> _buffer = [];
  int _offset = 0;

  /// Current number of buffered, unconsumed bytes.
  int get pendingLength => _buffer.length - _offset;

  /// Feeds [bytes] and returns the complete frames found within.
  List<Frame> feed(List<int> bytes) {
    if (bytes.isNotEmpty) {
      _buffer.addAll(bytes);
    }
    final frames = <Frame>[];
    while (pendingLength >= kFrameLength) {
      final frameBytes = Uint8List(kFrameLength);
      for (var i = 0; i < kFrameLength; i++) {
        frameBytes[i] = _buffer[_offset + i];
      }
      frames.add(Frame._(frameBytes));
      _offset += kFrameLength;
    }
    _compact();
    return frames;
  }

  /// Returns and clears any bytes that did not form a complete frame.
  Uint8List takePending() {
    final pending = Uint8List.sublistView(
      Uint8List.fromList(_buffer.sublist(_offset, _buffer.length)),
    );
    final copy = Uint8List.fromList(pending);
    _buffer.clear();
    _offset = 0;
    return copy;
  }

  void _compact() {
    if (_offset == _buffer.length) {
      _buffer.clear();
      _offset = 0;
    } else if (_offset > 64) {
      _buffer.removeRange(0, _offset);
      _offset = 0;
    }
  }
}

/// Extracts integer values out of a 6-byte response frame.
extension ResponseReader on Frame {
  /// The response convention for most status replies: u8 at byte 5.
  int get responseU8 => bytes[5];

  /// u16 at bytes 4..5, big-endian (ADC, SFR u16 reads).
  int get responseU16At4 => (bytes[4] << 8) | bytes[5];

  /// u16 at bytes 2..3, big-endian.
  int get responseU16At2 => (bytes[2] << 8) | bytes[3];

  /// Compares the first [count] bytes with [expected], echoing any mismatch as
  /// a [MoliErrorKind.malformedResponse] error.
  ///
  /// The vendor demos only compare one or two bytes; this helper keeps that
  /// lenient matching explicit while still classifying surprises.
  void requirePrefix(List<int> expected, {int count = 2}) {
    if (expected.length < count) {
      throw MoliException.invalidArgument(
        'expected prefix must have at least $count bytes',
        operation: 'requirePrefix',
      );
    }
    for (var i = 0; i < count; i++) {
      if (bytes[i] != expected[i]) {
        throw MoliException.malformedResponse(
          'unexpected response prefix ${toHex(bytes)} '
          '(wanted ${toHex(expected)} at byte $i)',
          operation: 'requirePrefix',
        );
      }
    }
  }
}

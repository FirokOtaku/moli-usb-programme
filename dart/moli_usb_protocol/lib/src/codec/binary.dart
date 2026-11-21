// Binary utilities for the wire format: big-endian integers inside the 6-byte
// command frame, RGB565 pixel packing and hex formatting for diagnostics.

import 'dart:typed_data';

import '../errors.dart';

/// Reads and writes big-endian integers, as required by
// `spec/msu2-wire-protocol.md` (multi-byte integers: high byte first).
final class BigEndianCodec {
  const BigEndianCodec._();

  /// Encodes [value] into a fixed [length] big-endian byte sequence.
  static Uint8List encode(int value, int length) {
    final out = Uint8List(length);
    for (var i = length - 1; i >= 0; i--) {
      out[i] = value & 0xFF;
      value >>= 8;
    }
    return out;
  }

  /// Writes [value] into [out] at [offset] as a big-endian [length] sequence.
  static void writeInto(Uint8List out, int offset, int value, int length) {
    for (var i = length - 1; i >= 0; i--) {
      out[offset + i] = value & 0xFF;
      value >>= 8;
    }
  }

  /// Reads a big-endian unsigned integer of [length] bytes at [offset].
  static int readUnsigned(Uint8List bytes, int offset, int length) {
    var value = 0;
    for (var i = 0; i < length; i++) {
      value = (value << 8) | bytes[offset + i];
    }
    return value;
  }
}

/// 16-bit color in RGB565 layout.
extension type const Rgb565Color(int value) {
  /// Builds a color from 5/6/5 bit components.
  static Rgb565Color fromComponents(
    int red,
    int green,
    int blue,
  ) => Rgb565Color((red << 11) | (green << 5) | blue);

  /// The red channel, 0..31.
  int get red => (value >> 11) & 0x1F;

  /// The green channel, 0..63.
  int get green => (value >> 5) & 0x3F;

  /// The blue channel, 0..5 bits, 0..31.
  int get blue => value & 0x1F;

  /// Big-endian wire bytes for this color.
  Uint8List get wireBytes => BigEndianCodec.encode(value, 2);
}

/// Common colors used by the specification test vectors.
abstract final class Rgb565Colors {
  static const Rgb565Color black = Rgb565Color(0x0000);
  static const Rgb565Color red = Rgb565Color(0xF800);
  static const Rgb565Color green = Rgb565Color(0x07E0);
  static const Rgb565Color blue = Rgb565Color(0x001F);
  static const Rgb565Color white = Rgb565Color(0xFFFF);
}

/// A rectangle in display coordinates, origin at the top-left corner.
///
/// Must be validated ([validate]) before encoding: non-negative x/y and a
/// strictly positive width/height. Callers should also check that the rect fits
/// the panel geometry before issuing draw calls.
final class Rect {
  const Rect(this.x, this.y, this.width, this.height);

  final int x;
  final int y;
  final int width;
  final int height;

  int get right => x + width;
  int get bottom => y + height;

  /// Structural validation performed before encoding (spec/architecture.md).
  void validate({String? operation}) {
    if (x < 0 || y < 0 || width <= 0 || height <= 0) {
      throw MoliException.invalidArgument(
        'rect must have non-negative origin and positive size '
        '(got x=$x, y=$y, w=$width, h=$height)',
        operation: operation ?? 'Rect.validate',
      );
    }
  }

  /// Throws [MoliErrorKind.outOfBounds] when this rect exceeds the panel.
  void ensureWithin(int panelWidth, int panelHeight, {String? operation}) {
    validate(operation: operation);
    if (right > panelWidth || bottom > panelHeight) {
      throw MoliException.outOfBounds(
        'rect $this exceeds panel ${panelWidth}x$panelHeight',
        operation: operation ?? 'Rect.ensureWithin',
      );
    }
  }

  @override
  String toString() => 'Rect($x, $y, ${width}x$height)';
}

/// Lowercase hex, used for logs and test vector comparisons.
String toHex(List<int> bytes) =>
    bytes.map((b) => b.toRadixString(16).padLeft(2, '0').toUpperCase()).join();

/// Parses a whitespace-separated hex string (test-vector helper).
Uint8List parseHex(String hex) {
  final tokens =
      hex.split(RegExp(r'[\s,]+')).where((t) => t.isNotEmpty).toList();
  try {
    return Uint8List.fromList(
      tokens.map((t) => int.parse(t, radix: 16)).toList(),
    );
  } on FormatException catch (e) {
    throw MoliException.invalidArgument('invalid hex string: $hex',
        operation: 'parseHex', cause: e);
  }
}

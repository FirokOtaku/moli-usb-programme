import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// Packs 8-bit channels into RGB565 by dropping the low bits.
int rgb888ToRgb565(int red, int green, int blue) {
  return ((_clamp8(red) >> 3) << 11) |
      ((_clamp8(green) >> 2) << 5) |
      (_clamp8(blue) >> 3);
}

Rgb565Color rgb888ToColor(int red, int green, int blue) =>
    Rgb565Color(rgb888ToRgb565(red, green, blue));

/// Opaque pixels keep their color. Transparent pixels become [background].
/// Partial alpha is blended in 8-bit space, then packed.
int rgbaToRgb565(
  int red,
  int green,
  int blue,
  int alpha, {
  int background = 0x0000,
}) {
  final a = _clamp8(alpha);
  if (a >= 250) return rgb888ToRgb565(red, green, blue);
  if (a <= 5) return background & 0xFFFF;
  final bg = Rgb565Color(background & 0xFFFF);
  final br = (bg.red * 255) ~/ 31;
  final bgc = (bg.green * 255) ~/ 63;
  final bb = (bg.blue * 255) ~/ 31;
  final inv = 255 - a;
  return rgb888ToRgb565(
    (_clamp8(red) * a + br * inv) ~/ 255,
    (_clamp8(green) * a + bgc * inv) ~/ 255,
    (_clamp8(blue) * a + bb * inv) ~/ 255,
  );
}

/// Coverage threshold used so text and clock frames stay two colors.
const int kTwoColorAlpha = 128;

/// Maps one RGBA pixel to either [foreground] or [background].
int twoColorRgb565({
  required int alpha,
  required int foreground,
  required int background,
  int threshold = kTwoColorAlpha,
}) {
  return _clamp8(alpha) >= threshold ? foreground & 0xFFFF : background & 0xFFFF;
}

/// Walks a raw RGBA buffer and emits one RGB565 value per pixel.
List<int> rgbaBufferToTwoColor(
  List<int> rgba, {
  required int foreground,
  required int background,
  int threshold = kTwoColorAlpha,
}) {
  if (rgba.length % 4 != 0) {
    throw ArgumentError('RGBA buffer length must be a multiple of 4');
  }
  final count = rgba.length ~/ 4;
  final out = List<int>.filled(count, background & 0xFFFF);
  for (var i = 0; i < count; i++) {
    out[i] = twoColorRgb565(
      alpha: rgba[i * 4 + 3],
      foreground: foreground,
      background: background,
      threshold: threshold,
    );
  }
  return out;
}

List<Rgb565Color> asColors(List<int> values) =>
    List<Rgb565Color>.generate(
      values.length,
      (index) => Rgb565Color(values[index] & 0xFFFF),
      growable: false,
    );

bool isUniform(List<Rgb565Color> pixels) {
  if (pixels.isEmpty) return false;
  final first = pixels.first.value & 0xFFFF;
  for (final pixel in pixels) {
    if ((pixel.value & 0xFFFF) != first) return false;
  }
  return true;
}

int _clamp8(int value) {
  if (value < 0) return 0;
  if (value > 255) return 255;
  return value;
}

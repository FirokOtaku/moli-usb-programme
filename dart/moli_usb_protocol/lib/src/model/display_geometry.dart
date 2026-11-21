// Display geometry (panel size, origin, supported orientations).

import '../codec/binary.dart';

/// Where the panel origin sits.
enum DisplayOrigin { topLeft, bottomLeft }

/// Pixel formats the library can encode.
enum PixelFormat {
  rgb565('rgb565', 2),
  mono1('mono1', 1);

  const PixelFormat(this.label, this.bytesPerPixel);

  final String label;
  final int bytesPerPixel;
}

/// Physical description of one panel.
///
/// `spec/architecture.md` requires width, height, origin, supported
/// orientations and pixel format. Nothing here is guessed from a model name:
/// a value of this type is either supplied by the caller or derived from the
/// device self-description (`Lcd_X`, `Lcd_Y`).
final class DisplayGeometry {
  const DisplayGeometry({
    required this.width,
    required this.height,
    this.origin = DisplayOrigin.topLeft,
    this.orientations = const [0, 1, 2, 3],
    this.pixelFormat = PixelFormat.rgb565,
  });

  final int width;
  final int height;
  final DisplayOrigin origin;

  /// Orientation values (0..3) the panel accepts for action `0x0A`.
  final List<int> orientations;

  final PixelFormat pixelFormat;

  /// Whether [rect] fits inside the panel.
  bool contains(Rect rect) => rect.right <= width && rect.bottom <= height;

  /// Bytes needed for a full-frame image in [pixelFormat].
  int get frameByteCount => width * height * pixelFormat.bytesPerPixel;

  /// Throws [MoliErrorKind.outOfBounds] when [rect] exceeds the panel.
  Rect clampRect(Rect rect) {
    final x = rect.x.clamp(0, width);
    final y = rect.y.clamp(0, height);
    final w = rect.width.clamp(0, width - x);
    final h = rect.height.clamp(0, height - y);
    return Rect(x, y, w, h);
  }

  @override
  String toString() =>
      'DisplayGeometry(${width}x$height, ${origin.name}, ${pixelFormat.label})';
}
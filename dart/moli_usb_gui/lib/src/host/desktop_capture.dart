import 'dart:typed_data';

/// One primary-screen capture, row-major RGBA.
final class DesktopCapture {
  const DesktopCapture({
    required this.width,
    required this.height,
    required this.rgba,
  });

  final int width;
  final int height;
  final Uint8List rgba;
}

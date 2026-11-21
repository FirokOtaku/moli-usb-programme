import 'dart:typed_data';
import 'dart:ui' as ui;

/// Decoded bitmap. The scale math that places it on the panel is pure Dart.
final class DecodedBitmap {
  const DecodedBitmap({
    required this.width,
    required this.height,
    required this.rgba,
  });

  final int width;
  final int height;
  final Uint8List rgba;
}

/// Decodes a jpg, png, or bmp with the Flutter image codec.
Future<DecodedBitmap> decodeImageBytes(Uint8List bytes) async {
  final codec = await ui.instantiateImageCodec(bytes);
  try {
    final frame = await codec.getNextFrame();
    final image = frame.image;
    try {
      final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
      if (data == null) {
        throw StateError('无法读取图片像素');
      }
      return DecodedBitmap(
        width: image.width,
        height: image.height,
        rgba: data.buffer.asUint8List(),
      );
    } finally {
      image.dispose();
    }
  } finally {
    codec.dispose();
  }
}

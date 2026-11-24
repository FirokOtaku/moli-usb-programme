import 'dart:math' as math;
import 'dart:typed_data';

import '../codec/binary.dart';

/// How straight alpha is handled before RGB565 quantization.
enum ImageAlphaMode { ignore, composite }

/// Nearest-neighbor mapping from the source image to the target rectangle.
enum ImageScaleMode {
  /// Preserve aspect ratio, adding padding if needed.
  fit,

  /// Preserve aspect ratio with a centered crop that fills the target.
  fill,

  /// Resize width and height independently.
  stretch,
}

/// Wire encoding; independent of image resolution.
enum ImageCompression {
  none,
  rle,

  /// MINI scratch compaction, RLE on other profiles.
  profileCompact,
}

final class ImageWriteOptions {
  const ImageWriteOptions({
    this.alphaMode = ImageAlphaMode.composite,
    this.alphaBackground = Rgb565Colors.black,
    this.scaleMode = ImageScaleMode.fit,
    this.paddingColor = Rgb565Colors.black,
    this.compression = ImageCompression.profileCompact,
  });

  final ImageAlphaMode alphaMode;
  final Rgb565Color alphaBackground;
  final ImageScaleMode scaleMode;
  final Rgb565Color paddingColor;
  final ImageCompression compression;
}

/// Immutable, decoded bitmap with row-major RGBA8888 bytes in sRGB.
///
/// Alpha must be straight (not premultiplied). The constructor copies [rgba],
/// so the same image can be submitted safely from a reusable capture buffer.
final class RgbaImage {
  factory RgbaImage(int width, int height, Uint8List rgba) {
    if (width <= 0 || height <= 0 || rgba.length != width * height * 4) {
      throw ArgumentError('expected $width x $height RGBA8888 pixels');
    }
    return RgbaImage._(
      width,
      height,
      Uint8List.fromList(rgba).asUnmodifiableView(),
    );
  }

  RgbaImage._(this.width, this.height, this.rgba);

  final int width;
  final int height;
  final Uint8List rgba;

  /// Converts and resizes the image without performing any device I/O.
  List<Rgb565Color> toRgb565(
    int targetWidth,
    int targetHeight, {
    ImageWriteOptions options = const ImageWriteOptions(),
  }) {
    if (targetWidth <= 0 || targetHeight <= 0) {
      throw ArgumentError('target size must be positive');
    }

    var drawWidth = targetWidth;
    var drawHeight = targetHeight;
    var sampleWidth = width;
    var sampleHeight = height;
    final wider = width * targetHeight > height * targetWidth;
    switch (options.scaleMode) {
      case ImageScaleMode.fit:
        if (wider) {
          drawHeight = math.max(1, height * targetWidth ~/ width);
        } else {
          drawWidth = math.max(1, width * targetHeight ~/ height);
        }
      case ImageScaleMode.fill:
        if (wider) {
          sampleWidth = math.max(1, height * targetWidth ~/ targetHeight);
        } else {
          sampleHeight = math.max(1, width * targetHeight ~/ targetWidth);
        }
      case ImageScaleMode.stretch:
        break;
    }

    final sourceX = (width - sampleWidth) ~/ 2;
    final sourceY = (height - sampleHeight) ~/ 2;
    final targetX = (targetWidth - drawWidth) ~/ 2;
    final targetY = (targetHeight - drawHeight) ~/ 2;
    final pixels = List<Rgb565Color>.filled(
      targetWidth * targetHeight,
      options.paddingColor,
    );
    final composite = options.alphaMode == ImageAlphaMode.composite;
    final background = options.alphaBackground;
    final backgroundRed = background.red * 255 ~/ 31;
    final backgroundGreen = background.green * 255 ~/ 63;
    final backgroundBlue = background.blue * 255 ~/ 31;
    for (var row = 0; row < drawHeight; row++) {
      final sourceRow = sourceY + row * sampleHeight ~/ drawHeight;
      final targetOffset = (row + targetY) * targetWidth + targetX;
      for (var column = 0; column < drawWidth; column++) {
        final sourceColumn = sourceX + column * sampleWidth ~/ drawWidth;
        final offset = 4 * (sourceRow * width + sourceColumn);
        final alpha = composite ? rgba[offset + 3] : 255;
        pixels[targetOffset + column] = Rgb565Color.fromRgb888(
          _blend(rgba[offset], backgroundRed, alpha),
          _blend(rgba[offset + 1], backgroundGreen, alpha),
          _blend(rgba[offset + 2], backgroundBlue, alpha),
        );
      }
    }
    return pixels;
  }

  static int _blend(int foreground, int background, int alpha) =>
      (foreground * alpha + background * (255 - alpha) + 127) ~/ 255;
}

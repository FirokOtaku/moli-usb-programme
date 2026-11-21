import 'dart:typed_data';

import 'rgb565_convert.dart';

/// How a source image is mapped onto the panel.
enum ImageFit {
  /// The whole image is visible. Unused panel pixels stay the background.
  contain,

  /// The panel is filled. Overflow is cropped from the center.
  cover,
}

/// Integer placement of one image onto a panel.
///
/// Source coordinates are in the decoded bitmap. Destination coordinates are
/// in the panel. Both sizes are positive.
final class ImagePlacement {
  const ImagePlacement({
    required this.sourceX,
    required this.sourceY,
    required this.sourceWidth,
    required this.sourceHeight,
    required this.destX,
    required this.destY,
    required this.destWidth,
    required this.destHeight,
  });

  final int sourceX;
  final int sourceY;
  final int sourceWidth;
  final int sourceHeight;
  final int destX;
  final int destY;
  final int destWidth;
  final int destHeight;
}

/// Places [imageWidth]x[imageHeight] onto [panelWidth]x[panelHeight].
///
/// Uses integer cross-multiplication so tests do not depend on a Flutter
/// binding or on floating-point rounding.
ImagePlacement placeImage({
  required int imageWidth,
  required int imageHeight,
  required int panelWidth,
  required int panelHeight,
  required ImageFit fit,
}) {
  if (imageWidth <= 0 ||
      imageHeight <= 0 ||
      panelWidth <= 0 ||
      panelHeight <= 0) {
    throw ArgumentError(
      'image and panel sizes must be positive '
      '(image ${imageWidth}x$imageHeight, panel ${panelWidth}x$panelHeight)',
    );
  }
  final wider = imageWidth * panelHeight > imageHeight * panelWidth;
  if (fit == ImageFit.contain) {
    final dest = _containSize(
      imageWidth: imageWidth,
      imageHeight: imageHeight,
      panelWidth: panelWidth,
      panelHeight: panelHeight,
      wider: wider,
    );
    return ImagePlacement(
      sourceX: 0,
      sourceY: 0,
      sourceWidth: imageWidth,
      sourceHeight: imageHeight,
      destX: (panelWidth - dest.$1) ~/ 2,
      destY: (panelHeight - dest.$2) ~/ 2,
      destWidth: dest.$1,
      destHeight: dest.$2,
    );
  }
  final crop = _coverCrop(
    imageWidth: imageWidth,
    imageHeight: imageHeight,
    panelWidth: panelWidth,
    panelHeight: panelHeight,
    wider: wider,
  );
  return ImagePlacement(
    sourceX: (imageWidth - crop.$1) ~/ 2,
    sourceY: (imageHeight - crop.$2) ~/ 2,
    sourceWidth: crop.$1,
    sourceHeight: crop.$2,
    destX: 0,
    destY: 0,
    destWidth: panelWidth,
    destHeight: panelHeight,
  );
}

(int, int) _containSize({
  required int imageWidth,
  required int imageHeight,
  required int panelWidth,
  required int panelHeight,
  required bool wider,
}) {
  if (wider) {
    final height = (panelWidth * imageHeight) ~/ imageWidth;
    return (panelWidth, height < 1 ? 1 : height);
  }
  final width = (panelHeight * imageWidth) ~/ imageHeight;
  return (width < 1 ? 1 : width, panelHeight);
}

(int, int) _coverCrop({
  required int imageWidth,
  required int imageHeight,
  required int panelWidth,
  required int panelHeight,
  required bool wider,
}) {
  if (wider) {
    var width = (imageHeight * panelWidth) ~/ panelHeight;
    if (width < 1) width = 1;
    if (width > imageWidth) width = imageWidth;
    return (width, imageHeight);
  }
  var height = (imageWidth * panelHeight) ~/ panelWidth;
  if (height < 1) height = 1;
  if (height > imageHeight) height = imageHeight;
  return (imageWidth, height);
}

/// Samples [rgba] (row-major, 4 bytes per pixel) into a panel-sized RGB565
/// buffer. Pixels outside the placed image stay [background].
List<int> composeRgba({
  required Uint8List rgba,
  required int imageWidth,
  required int imageHeight,
  required int panelWidth,
  required int panelHeight,
  required ImageFit fit,
  int background = 0x0000,
}) {
  final expected = imageWidth * imageHeight * 4;
  if (rgba.length < expected) {
    throw ArgumentError(
      'RGBA buffer has ${rgba.length} bytes, need $expected',
    );
  }
  final placement = placeImage(
    imageWidth: imageWidth,
    imageHeight: imageHeight,
    panelWidth: panelWidth,
    panelHeight: panelHeight,
    fit: fit,
  );
  final out = List<int>.filled(panelWidth * panelHeight, background & 0xFFFF);
  for (var dy = 0; dy < placement.destHeight; dy++) {
    final sy = placement.sourceY +
        (dy * placement.sourceHeight) ~/ placement.destHeight;
    final row = sy * imageWidth;
    for (var dx = 0; dx < placement.destWidth; dx++) {
      final sx = placement.sourceX +
          (dx * placement.sourceWidth) ~/ placement.destWidth;
      final i = (row + sx) * 4;
      final color = rgbaToRgb565(
        rgba[i],
        rgba[i + 1],
        rgba[i + 2],
        rgba[i + 3],
        background: background,
      );
      final px = placement.destX + dx;
      final py = placement.destY + dy;
      out[py * panelWidth + px] = color;
    }
  }
  return out;
}

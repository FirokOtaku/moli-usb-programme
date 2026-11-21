import 'dart:typed_data';

import '../content/image_fit.dart';

/// Places a desktop capture onto the reported panel.
///
/// Defaults to [ImageFit.contain]. Panel size is the caller's geometry;
/// this function does not assume a device resolution.
ImagePlacement placeMirror({
  required int captureWidth,
  required int captureHeight,
  required int panelWidth,
  required int panelHeight,
  ImageFit fit = ImageFit.contain,
}) {
  return placeImage(
    imageWidth: captureWidth,
    imageHeight: captureHeight,
    panelWidth: panelWidth,
    panelHeight: panelHeight,
    fit: fit,
  );
}

/// Scales a captured desktop into panel RGB565 with [fit].
///
/// The default is contain, same as [placeMirror].
List<int> scaleMirrorCapture({
  required Uint8List rgba,
  required int captureWidth,
  required int captureHeight,
  required int panelWidth,
  required int panelHeight,
  ImageFit fit = ImageFit.contain,
}) {
  return composeRgba(
    rgba: rgba,
    imageWidth: captureWidth,
    imageHeight: captureHeight,
    panelWidth: panelWidth,
    panelHeight: panelHeight,
    fit: fit,
  );
}

import 'dart:ui' as ui;

import 'package:flutter/painting.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import 'rgb565_convert.dart';

const List<String> kHostFontFallback = <String>[
  'Microsoft YaHei',
  'Segoe UI',
  'PingFang SC',
  'Noto Sans SC',
  'sans-serif',
];

/// Host-side text. Device ASCII/GB2312 fonts are not used.
///
/// The bitmap is white-on-transparent, then thresholded to two RGB565 colors
/// so the frame stays suitable for RLE.
Future<List<Rgb565Color>> rasterText({
  required String text,
  required int width,
  required int height,
  required Rgb565Color foreground,
  required Rgb565Color background,
  required double fontSize,
  TextAlign align = TextAlign.center,
}) async {
  if (width <= 0 || height <= 0) {
    throw ArgumentError('raster size must be positive ($width x $height)');
  }
  final painter = TextPainter(
    text: TextSpan(
      text: text.isEmpty ? ' ' : text,
      style: TextStyle(
        color: const ui.Color(0xFFFFFFFF),
        fontSize: fontSize < 8 ? 8 : fontSize,
        fontFamilyFallback: kHostFontFallback,
      ),
    ),
    textDirection: TextDirection.ltr,
    textAlign: align,
  )..layout(maxWidth: width.toDouble());
  return _paintAndThreshold(
    painter: painter,
    width: width,
    height: height,
    offset: Offset(0, _centerY(height, painter.height)),
    foreground: foreground,
    background: background,
  );
}

/// A horizontal strip: the text, then a gap the size of the panel, so a
/// wrapping crop can scroll the line fully off before it returns.
Future<({int width, List<Rgb565Color> pixels})> rasterScrollStrip({
  required String text,
  required int panelWidth,
  required int panelHeight,
  required Rgb565Color foreground,
  required Rgb565Color background,
  required double fontSize,
}) async {
  if (panelWidth <= 0 || panelHeight <= 0) {
    throw ArgumentError('panel size must be positive');
  }
  final painter = TextPainter(
    text: TextSpan(
      text: text.isEmpty ? ' ' : text,
      style: TextStyle(
        color: const ui.Color(0xFFFFFFFF),
        fontSize: fontSize < 8 ? 8 : fontSize,
        fontFamilyFallback: kHostFontFallback,
      ),
    ),
    textDirection: TextDirection.ltr,
    maxLines: 1,
  )..layout();
  final textWidth = painter.width.ceil().clamp(1, 4096);
  final stripWidth = (textWidth + panelWidth).clamp(panelWidth, 4096);
  final pixels = await _paintAndThreshold(
    painter: painter,
    width: stripWidth,
    height: panelHeight,
    offset: Offset(0, _centerY(panelHeight, painter.height)),
    foreground: foreground,
    background: background,
  );
  return (width: stripWidth, pixels: pixels);
}

/// Time on the first line, date on the second. Both are host-rasterized.
Future<List<Rgb565Color>> rasterClock({
  required String time,
  required String date,
  required int width,
  required int height,
  required Rgb565Color foreground,
  required Rgb565Color background,
}) async {
  if (width <= 0 || height <= 0) {
    throw ArgumentError('raster size must be positive');
  }
  final timePainter = TextPainter(
    text: TextSpan(
      text: time,
      style: TextStyle(
        color: const ui.Color(0xFFFFFFFF),
        fontSize: _font(height, 0.42),
        fontFamilyFallback: kHostFontFallback,
      ),
    ),
    textAlign: TextAlign.center,
    textDirection: TextDirection.ltr,
  )..layout(maxWidth: width.toDouble());
  final datePainter = TextPainter(
    text: TextSpan(
      text: date,
      style: TextStyle(
        color: const ui.Color(0xFFFFFFFF),
        fontSize: _font(height, 0.22),
        fontFamilyFallback: kHostFontFallback,
      ),
    ),
    textAlign: TextAlign.center,
    textDirection: TextDirection.ltr,
  )..layout(maxWidth: width.toDouble());
  final gap = height * 0.04;
  final block = timePainter.height + gap + datePainter.height;
  final top = _centerY(height, block);
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  timePainter.paint(canvas, Offset(0, top));
  datePainter.paint(canvas, Offset(0, top + timePainter.height + gap));
  return _thresholdPicture(
    recorder: recorder,
    width: width,
    height: height,
    foreground: foreground,
    background: background,
  );
}

double fontSizeForPanel(int panelHeight) => _font(panelHeight, 0.32);

double _font(int panelHeight, double fraction) {
  final scaled = panelHeight * fraction;
  if (scaled < 8) return 8;
  return scaled;
}

double _centerY(num panelHeight, num contentHeight) {
  final y = (panelHeight - contentHeight) / 2;
  return y < 0 ? 0 : y.toDouble();
}

Future<List<Rgb565Color>> _paintAndThreshold({
  required TextPainter painter,
  required int width,
  required int height,
  required Offset offset,
  required Rgb565Color foreground,
  required Rgb565Color background,
}) async {
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  painter.paint(canvas, offset);
  return _thresholdPicture(
    recorder: recorder,
    width: width,
    height: height,
    foreground: foreground,
    background: background,
  );
}

Future<List<Rgb565Color>> _thresholdPicture({
  required ui.PictureRecorder recorder,
  required int width,
  required int height,
  required Rgb565Color foreground,
  required Rgb565Color background,
}) async {
  final picture = recorder.endRecording();
  final image = await picture.toImage(width, height);
  picture.dispose();
  try {
    final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (data == null) {
      throw StateError('文本光栅化没有像素');
    }
    return asColors(
      rgbaBufferToTwoColor(
        data.buffer.asUint8List(),
        foreground: foreground.value,
        background: background.value,
      ),
    );
  } finally {
    image.dispose();
  }
}

String formatClockTime(DateTime now) {
  String two(int value) => value.toString().padLeft(2, '0');
  return '${two(now.hour)}:${two(now.minute)}:${two(now.second)}';
}

String formatClockDate(DateTime now) {
  String two(int value) => value.toString().padLeft(2, '0');
  return '${now.year}-${two(now.month)}-${two(now.day)}';
}

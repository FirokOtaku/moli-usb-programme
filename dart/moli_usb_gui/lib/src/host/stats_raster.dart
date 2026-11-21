import 'dart:ui' as ui;

import 'package:flutter/painting.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import '../content/rgb565_convert.dart';
import '../content/text_raster.dart';
import 'system_reading.dart';

/// Host-side stats. Device fonts are not used.
///
/// Text and bars are painted white, then thresholded to two RGB565 colors so
/// the frame can go out as RLE.
Future<List<Rgb565Color>> rasterStats({
  required SystemReading reading,
  required int width,
  required int height,
  required Rgb565Color foreground,
  required Rgb565Color background,
}) async {
  if (width <= 0 || height <= 0) {
    throw ArgumentError('raster size must be positive ($width x $height)');
  }
  final rows = statsRows(reading);
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  final fill = Paint()
    ..color = const ui.Color(0xFFFFFFFF)
    ..style = PaintingStyle.fill;
  final stroke = Paint()
    ..color = const ui.Color(0xFFFFFFFF)
    ..style = PaintingStyle.stroke
    ..strokeWidth = 1;
  final rowHeight = height / rows.length;
  for (var i = 0; i < rows.length; i++) {
    final row = rows[i];
    final top = i * rowHeight;
    final painter = TextPainter(
      text: TextSpan(
        text: row.label,
        style: TextStyle(
          color: const ui.Color(0xFFFFFFFF),
          fontSize: _statsFont(rowHeight),
          fontFamilyFallback: kHostFontFallback,
        ),
      ),
      textDirection: TextDirection.ltr,
      maxLines: 1,
      ellipsis: '…',
    )..layout(maxWidth: (width - 2).clamp(1, width).toDouble());
    painter.paint(canvas, Offset(1, top));
    final percent = row.percent;
    final barTop = top + painter.height + 1;
    final barBottom = top + rowHeight - 1;
    if (percent == null || barBottom - barTop < 3 || width < 8) continue;
    final track = ui.Rect.fromLTWH(
      1,
      barTop,
      (width - 2).toDouble(),
      barBottom - barTop,
    );
    canvas.drawRect(track, stroke);
    final inner = track.width - 2;
    final fillWidth = inner * percent / 100;
    if (fillWidth >= 1) {
      canvas.drawRect(
        ui.Rect.fromLTWH(
          track.left + 1,
          track.top + 1,
          fillWidth,
          track.height - 2,
        ),
        fill,
      );
    }
  }
  return _thresholdPicture(
    recorder: recorder,
    width: width,
    height: height,
    foreground: foreground,
    background: background,
  );
}

double _statsFont(double rowHeight) {
  final scaled = rowHeight * 0.42;
  if (scaled < 6) return 6;
  if (scaled > 20) return 20;
  return scaled;
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
      throw StateError('系统状态光栅化没有像素');
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

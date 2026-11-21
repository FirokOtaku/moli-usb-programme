/// Crops a panel-sized window from a horizontal strip.
///
/// [strip] is row-major, [stripWidth] by [height]. [offset] is the window's
/// left edge. The window wraps on the x axis so a marquee can loop without
/// a second copy of the strip.
List<int> cropScrollWindow({
  required List<int> strip,
  required int stripWidth,
  required int height,
  required int windowWidth,
  required int offset,
}) {
  if (stripWidth <= 0 || height <= 0 || windowWidth <= 0) {
    throw ArgumentError(
      'strip and window sizes must be positive '
      '(strip $stripWidth x $height, window $windowWidth)',
    );
  }
  if (strip.length != stripWidth * height) {
    throw ArgumentError(
      'strip has ${strip.length} pixels, expected ${stripWidth * height}',
    );
  }
  final start = offset % stripWidth;
  final normalized = start < 0 ? start + stripWidth : start;
  final out = List<int>.filled(windowWidth * height, 0);
  for (var y = 0; y < height; y++) {
    final row = y * stripWidth;
    final dest = y * windowWidth;
    for (var x = 0; x < windowWidth; x++) {
      final sx = (normalized + x) % stripWidth;
      out[dest + x] = strip[row + sx];
    }
  }
  return out;
}

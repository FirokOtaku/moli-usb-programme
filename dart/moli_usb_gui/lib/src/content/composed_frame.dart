import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// One host-composed panel image, row-major RGB565.
final class ComposedFrame {
  const ComposedFrame({
    required this.width,
    required this.height,
    required this.pixels,
  });

  final int width;
  final int height;
  final List<Rgb565Color> pixels;

  int get area => width * height;
}

/// How a composed frame should be written to the device.
enum PixelDelivery {
  /// Uniform color. Prefer [MoliDeviceApi.fillRect].
  fill,

  /// Photograph. Uncompressed [MoliDeviceApi.drawPixels] / [MoliDeviceApi.drawFrame].
  uncompressed,

  /// Text or clock. [MoliDeviceApi.drawPixelsRle], with an uncompressed fallback.
  rle,
}

final class OutgoingFrame {
  const OutgoingFrame({
    required this.preview,
    required this.delivery,
  });

  final ComposedFrame preview;
  final PixelDelivery delivery;
}

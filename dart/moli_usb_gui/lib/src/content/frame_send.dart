import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import 'composed_frame.dart';
import 'rgb565_convert.dart';

/// Safety bound passed to [MoliDeviceApi.drawPixelsRle].
///
/// The facade rejects the call when [RleCodec.worstCaseFrameCount] is larger,
/// and the host then sends the same pixels uncompressed.
const int kTwoColorRleFrameBudget = 8192;

/// Writes [frame] through the device facade. Does not open a port.
Future<bool> sendOutgoingFrame(
  MoliDeviceApi device,
  OutgoingFrame frame, {
  required CancelToken token,
}) async {
  final preview = frame.preview;
  final rect = Rect(0, 0, preview.width, preview.height);
  final pixels = preview.pixels;
  token.ensureNotCancelled(operation: 'content');
  if (frame.delivery == PixelDelivery.fill || isUniform(pixels)) {
    final color = pixels.isEmpty ? Rgb565Colors.black : pixels.first;
    await device.fillRect(rect, color, token: token);
    return false;
  }
  if (frame.delivery == PixelDelivery.uncompressed) {
    await _sendUncompressed(device, rect, pixels, token);
    return false;
  }
  return _sendTwoColor(device, rect, pixels, token);
}

Future<void> _sendUncompressed(
  MoliDeviceApi device,
  Rect rect,
  List<Rgb565Color> pixels,
  CancelToken token,
) async {
  final display = device.display;
  if (rect.x == 0 &&
      rect.y == 0 &&
      rect.width == display.width &&
      rect.height == display.height &&
      pixels.length == display.width * display.height) {
    await device.drawFrame(pixels, token: token);
    return;
  }
  await device.drawPixels(rect, pixels, token: token);
}

/// Returns true when the RLE bound was exceeded and uncompressed was used.
Future<bool> _sendTwoColor(
  MoliDeviceApi device,
  Rect rect,
  List<Rgb565Color> pixels,
  CancelToken token,
) async {
  final worst = RleCodec.worstCaseFrameCount(pixels.length);
  if (worst > kTwoColorRleFrameBudget) {
    await device.drawPixels(rect, pixels, token: token);
    return true;
  }
  try {
    await device.drawPixelsRle(
      rect,
      pixels,
      maxFrames: kTwoColorRleFrameBudget,
      token: token,
    );
    return false;
  } on MoliException catch (error) {
    if (error.kind == MoliErrorKind.invalidArgument &&
        error.operation == 'drawPixelsRle') {
      await device.drawPixels(rect, pixels, token: token);
      return true;
    }
    rethrow;
  }
}

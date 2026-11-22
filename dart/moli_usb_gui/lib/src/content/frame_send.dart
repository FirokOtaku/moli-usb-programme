import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import 'composed_frame.dart';
import 'rgb565_convert.dart';

/// Safety bound passed to [MoliDeviceApi.drawPixelsRle].
///
/// The facade rejects the call when [RleCodec.worstCaseFrameCount] is larger,
/// and the host then sends the same pixels uncompressed.
const int kTwoColorRleFrameBudget = 131072;

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
  if (frame.delivery == PixelDelivery.compact) {
    await device.drawPixelsCompact(rect, pixels, token: token);
    return false;
  }
  return _sendTwoColor(device, rect, pixels, token);
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

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// Panel size shown after connect.
///
/// Width and height come from the session. A zero axis is not a valid panel,
/// so the profile's display is used instead. Sizes are never invented here.
final class PanelGeometry {
  const PanelGeometry({
    required this.width,
    required this.height,
    required this.usedProfileFallback,
    this.warning,
  });

  final int width;
  final int height;
  final bool usedProfileFallback;
  final String? warning;
}

PanelGeometry resolvePanelGeometry({
  required int reportedWidth,
  required int reportedHeight,
  required DeviceProfile profile,
}) {
  if (reportedWidth != 0 && reportedHeight != 0) {
    return PanelGeometry(
      width: reportedWidth,
      height: reportedHeight,
      usedProfileFallback: false,
    );
  }
  final fallback = profile.display;
  return PanelGeometry(
    width: fallback.width,
    height: fallback.height,
    usedProfileFallback: true,
    warning:
        '设备报告的分辨率无效（${reportedWidth}x$reportedHeight），'
        '已改用配置 ${profile.name} 的 ${fallback.width}x${fallback.height}',
  );
}

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import '../connection/connection_controller.dart';
import '../host/desktop_capture.dart';
import '../host/host_metrics.dart';
import '../host/mirror_scale.dart';
import '../host/stats_raster.dart';
import '../host/system_reading.dart';
import 'composed_frame.dart';
import 'frame_send.dart';
import 'image_decode.dart';
import 'image_fit.dart';
import 'link_loss.dart';
import 'picked_image.dart';
import 'rgb565_convert.dart';
import 'scroll_window.dart';
import 'text_raster.dart';

/// Host content. Device writes go through [ConnectionController.submit].
enum ContentMode {
  solid('纯色'),
  image('图片'),
  staticText('静态文本'),
  scrollingText('滚动文本'),
  clock('时钟'),
  stats('系统状态'),
  mirror('桌面镜像');

  const ContentMode(this.label);

  final String label;
}

/// One editable RGB color. Channels are 0..255.
final class HostColor {
  const HostColor(this.red, this.green, this.blue);

  final int red;
  final int green;
  final int blue;

  HostColor copyWith({int? red, int? green, int? blue}) {
    return HostColor(red ?? this.red, green ?? this.green, blue ?? this.blue);
  }

  Rgb565Color toRgb565() => rgb888ToColor(red, green, blue);
}

/// Composes frames and sends at most one at a time.
///
/// A mode switch or disconnect cancels the in-flight [CancelToken]. If that
/// send has not finished, the new frame is dropped rather than queued. One
/// follow-up recomposes the current mode after the cancelled send ends.
///
/// Stats and desktop mirror never set that follow-up from a timer tick: if a
/// capture or send is still running, the tick is dropped.
final class ContentController extends ChangeNotifier {
  ContentController({
    required ConnectionController connection,
    HostMetrics? hostMetrics,
  }) : _connection = connection,
       _metrics = hostMetrics ?? createHostMetrics() {
    _seen = _connection.phase;
    _connection.addListener(_onConnection);
  }

  static const Duration scrollInterval = Duration(milliseconds: 120);
  static const int scrollStep = 2;
  static const Duration statsInterval = Duration(seconds: 1);
  static const Duration mirrorInterval = Duration(milliseconds: 400);
  static const String zeroPanelMessage = '面板宽度或高度为 0，已停止发送';
  static const String statsFailedPrefix = '读取系统状态失败';
  static const String captureFailedPrefix = '截取桌面失败';

  final ConnectionController _connection;
  final HostMetrics _metrics;

  ContentMode mode = ContentMode.solid;
  ImageFit imageFit = ImageFit.contain;
  ImageFit mirrorFit = ImageFit.contain;
  HostColor solid = const HostColor(0, 80, 180);
  HostColor foreground = const HostColor(255, 255, 255);
  HostColor background = const HostColor(0, 0, 0);
  String text = '你好，MSU2';
  String? imageName;
  String? statusMessage;
  ComposedFrame? preview;
  bool sending = false;

  late ConnectionPhase _seen;
  bool _disposed = false;
  bool _inflight = false;
  bool _again = false;
  int _epoch = 0;
  int _imageEpoch = 0;
  CancelToken? _token;
  Timer? _timer;
  Timer? _textDebounce;
  DecodedBitmap? _decoded;
  List<Rgb565Color>? _strip;
  int _stripWidth = 0;
  int _scrollOffset = 0;
  bool _loggedRleFallback = false;
  bool _closingForLoss = false;

  bool get cover => imageFit == ImageFit.cover;

  bool get mirrorCover => mirrorFit == ImageFit.cover;

  bool get hostMetricsSupported => _metrics.supported;

  String? get hostMetricsUnsupportedReason => _metrics.unsupportedReason;

  bool modeSelectable(ContentMode value) {
    if (value == ContentMode.stats || value == ContentMode.mirror) {
      return _metrics.supported;
    }
    return true;
  }

  bool get canSend {
    if (_connection.phase != ConnectionPhase.connected) return false;
    final size = _panel();
    return size != null;
  }

  bool get geometryBlocked {
    if (_connection.phase != ConnectionPhase.connected) return false;
    return _panel() == null;
  }

  /// 19200 baud. Full-frame photos and desktop mirror are slow; the UI stays usable.
  bool get slowPhoto => _connection.selectedProfile.serial.baudRate == 19200;

  void selectMode(ContentMode next) {
    if (_disposed || next == mode) return;
    if (!modeSelectable(next)) {
      statusMessage =
          _metrics.unsupportedReason ?? kHostMetricsUnsupportedReason;
      _notify();
      return;
    }
    _beginNewSend();
    _stopLoops();
    mode = next;
    _scrollOffset = 0;
    _strip = null;
    _loggedRleFallback = false;
    _kick();
    _notify();
  }

  void setSolid(HostColor color) {
    solid = color;
    if (mode == ContentMode.solid) _kick();
    _notify();
  }

  void setForeground(HostColor color) {
    foreground = color;
    _strip = null;
    if (_usesTextColors) _kick();
    _notify();
  }

  void setBackground(HostColor color) {
    background = color;
    _strip = null;
    if (_usesTextColors) _kick();
    _notify();
  }

  void setText(String value) {
    text = value;
    _textDebounce?.cancel();
    _textDebounce = Timer(const Duration(milliseconds: 250), () {
      if (_disposed) return;
      _strip = null;
      _scrollOffset = 0;
      if (mode == ContentMode.staticText || mode == ContentMode.scrollingText) {
        _kick();
      }
      _notify();
    });
    _notify();
  }

  void setCover(bool value) {
    imageFit = value ? ImageFit.cover : ImageFit.contain;
    if (mode == ContentMode.image) _kick();
    _notify();
  }

  void setMirrorCover(bool value) {
    mirrorFit = value ? ImageFit.cover : ImageFit.contain;
    if (mode == ContentMode.mirror) _kick();
    _notify();
  }

  Future<void> acceptImage(PickedImage image) async {
    if (!isSupportedImageName(image.name)) {
      statusMessage = '只支持 jpg、png、bmp';
      _notify();
      return;
    }
    final epoch = ++_imageEpoch;
    imageName = image.name;
    statusMessage = '正在解码图片…';
    _notify();
    try {
      final decoded = await decodeImageBytes(image.bytes);
      if (_disposed || epoch != _imageEpoch) return;
      _decoded = decoded;
      statusMessage = null;
      if (mode == ContentMode.image) _kick();
      _notify();
    } catch (error) {
      if (_disposed || epoch != _imageEpoch) return;
      statusMessage = '图片解码失败: $error';
      _notify();
    }
  }

  void reportStatus(String message) {
    statusMessage = message;
    _notify();
  }

  /// Cancels the in-flight write before [ConnectionController.disconnect].
  void prepareDisconnect() {
    _beginNewSend();
    _stopLoops();
  }

  @override
  void dispose() {
    _disposed = true;
    _textDebounce?.cancel();
    _stopLoops();
    _cancelInFlight();
    _connection.removeListener(_onConnection);
    super.dispose();
  }

  bool get _usesTextColors =>
      mode == ContentMode.staticText ||
      mode == ContentMode.scrollingText ||
      mode == ContentMode.clock ||
      mode == ContentMode.stats;

  bool get _dropInsteadOfQueue =>
      mode == ContentMode.stats || mode == ContentMode.mirror;

  bool get _hostModeAllowed =>
      (mode != ContentMode.stats && mode != ContentMode.mirror) ||
      _metrics.supported;

  void _beginNewSend() {
    _epoch++;
    _cancelInFlight();
  }

  void _onConnection() {
    if (_disposed) return;
    final phase = _connection.phase;
    if (phase == _seen) {
      _notify();
      return;
    }
    final previous = _seen;
    _seen = phase;
    if (phase != ConnectionPhase.connected) {
      _stopLoops();
      if (previous == ConnectionPhase.connected) _cancelInFlight();
    } else {
      _kick();
    }
    _notify();
  }

  void _kick() {
    if (_disposed) return;
    if (!canSend) {
      if (geometryBlocked) {
        statusMessage = zeroPanelMessage;
        _notify();
      }
      return;
    }
    if (!_hostModeAllowed) {
      statusMessage =
          _metrics.unsupportedReason ?? kHostMetricsUnsupportedReason;
      _notify();
      return;
    }
    switch (mode) {
      case ContentMode.solid:
      case ContentMode.image:
      case ContentMode.staticText:
        unawaited(_sendLatest());
      case ContentMode.scrollingText:
        _startScroll();
      case ContentMode.clock:
        _startClock();
      case ContentMode.stats:
        _startStats();
      case ContentMode.mirror:
        _startMirror();
    }
  }

  void _startStats() {
    _stopLoops();
    unawaited(_sendLatest());
    _timer = Timer.periodic(statsInterval, (_) {
      unawaited(_sendLatest());
    });
  }

  void _startMirror() {
    _stopLoops();
    unawaited(_sendLatest());
    _timer = Timer.periodic(mirrorInterval, (_) {
      unawaited(_sendLatest());
    });
  }

  void _startScroll() {
    _stopLoops();
    unawaited(_sendLatest());
    _timer = Timer.periodic(scrollInterval, (_) {
      _scrollOffset += scrollStep;
      unawaited(_sendLatest());
    });
  }

  void _startClock() {
    _stopLoops();
    unawaited(_sendLatest());
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      unawaited(_sendLatest());
    });
  }

  void _stopLoops() {
    _timer?.cancel();
    _timer = null;
  }

  void _cancelInFlight() {
    _token?.cancel('已取消');
  }

  Future<void> _sendLatest() async {
    if (_disposed || !canSend) return;
    if (_inflight) {
      if (_dropInsteadOfQueue) return;
      _again = true;
      return;
    }
    if (mode == ContentMode.image && _decoded == null) {
      statusMessage = '尚未选择图片';
      _notify();
      return;
    }
    _inflight = true;
    _again = false;
    sending = true;
    final token = CancelToken();
    _token = token;
    final epoch = _epoch;
    _notify();
    try {
      final frame = await _compose();
      if (_disposed || token.isCancelled || frame == null) return;
      preview = frame.preview;
      if (statusMessage == zeroPanelMessage ||
          statusMessage == '尚未选择图片' ||
          _isHostMetricStatus(statusMessage)) {
        statusMessage = null;
      }
      _notify();
      await _connection.submit((device) async {
        token.ensureNotCancelled(operation: 'content');
        final fellBack = await sendOutgoingFrame(device, frame, token: token);
        if (fellBack && !_loggedRleFallback && !_disposed) {
          _loggedRleFallback = true;
          _connection.appendLog('RLE 上界不足，已改用未压缩发送');
        }
      });
    } on MoliException catch (error) {
      if (_disposed ||
          error.kind == MoliErrorKind.cancelled ||
          token.isCancelled) {
        return;
      }
      if (_linkGone(error)) {
        await _onLinkLost(error);
        return;
      }
      _connection.appendLog('${error.kind.label}: ${error.message}');
      statusMessage = error.message;
    } catch (error) {
      if (_disposed || token.isCancelled) return;
      _connection.appendLog('发送失败: $error');
      statusMessage = '$error';
    } finally {
      _inflight = false;
      sending = false;
      if (identical(_token, token)) _token = null;
      final follow = !_disposed && (_again || _epoch != epoch);
      _again = false;
      if (!_disposed) {
        _notify();
        if (follow && canSend) {
          unawaited(_sendLatest());
        }
      }
    }
  }

  Future<OutgoingFrame?> _compose() async {
    final panel = _panel();
    if (panel == null) {
      statusMessage = zeroPanelMessage;
      return null;
    }
    final width = panel.$1;
    final height = panel.$2;
    switch (mode) {
      case ContentMode.solid:
        return _solid(width, height);
      case ContentMode.image:
        return _image(width, height);
      case ContentMode.staticText:
        return _staticText(width, height);
      case ContentMode.scrollingText:
        return _scroll(width, height);
      case ContentMode.clock:
        return _clock(width, height);
      case ContentMode.stats:
        return _stats(width, height);
      case ContentMode.mirror:
        return _mirror(width, height);
    }
  }

  Future<OutgoingFrame?> _stats(int width, int height) async {
    final SystemReading? reading;
    try {
      reading = await _metrics.sampler.sample();
    } catch (error) {
      _reportHostIssue('$statsFailedPrefix: $error');
      return null;
    }
    if (reading == null || _disposed) return null;
    final pixels = await rasterStats(
      reading: reading,
      width: width,
      height: height,
      foreground: foreground.toRgb565(),
      background: background.toRgb565(),
    );
    return OutgoingFrame(
      preview: ComposedFrame(width: width, height: height, pixels: pixels),
      delivery: PixelDelivery.rle,
    );
  }

  Future<OutgoingFrame?> _mirror(int width, int height) async {
    final DesktopCapture? captured;
    try {
      captured = await _metrics.capturer.capture();
    } catch (error) {
      _reportHostIssue('$captureFailedPrefix: $error');
      return null;
    }
    if (captured == null || _disposed) return null;
    if (captured.width <= 0 || captured.height <= 0) {
      _reportHostIssue('$captureFailedPrefix: 主屏幕尺寸无效');
      return null;
    }
    final values = scaleMirrorCapture(
      rgba: captured.rgba,
      captureWidth: captured.width,
      captureHeight: captured.height,
      panelWidth: width,
      panelHeight: height,
      fit: mirrorFit,
    );
    return OutgoingFrame(
      preview: ComposedFrame(
        width: width,
        height: height,
        pixels: asColors(values),
      ),
      delivery: PixelDelivery.uncompressed,
    );
  }

  void _reportHostIssue(String message) {
    if (statusMessage == message) return;
    statusMessage = message;
    _connection.appendLog(message);
  }

  bool _isHostMetricStatus(String? message) {
    if (message == null) return false;
    return message.startsWith(statsFailedPrefix) ||
        message.startsWith(captureFailedPrefix);
  }

  OutgoingFrame _solid(int width, int height) {
    final color = solid.toRgb565();
    return OutgoingFrame(
      preview: ComposedFrame(
        width: width,
        height: height,
        pixels: List<Rgb565Color>.filled(width * height, color),
      ),
      delivery: PixelDelivery.fill,
    );
  }

  OutgoingFrame? _image(int width, int height) {
    final decoded = _decoded;
    if (decoded == null) {
      statusMessage = '尚未选择图片';
      return null;
    }
    final values = composeRgba(
      rgba: decoded.rgba,
      imageWidth: decoded.width,
      imageHeight: decoded.height,
      panelWidth: width,
      panelHeight: height,
      fit: imageFit,
    );
    return OutgoingFrame(
      preview: ComposedFrame(
        width: width,
        height: height,
        pixels: asColors(values),
      ),
      delivery: PixelDelivery.uncompressed,
    );
  }

  Future<OutgoingFrame> _staticText(int width, int height) {
    return _textFrame(
      rasterText(
        text: text,
        width: width,
        height: height,
        foreground: foreground.toRgb565(),
        background: background.toRgb565(),
        fontSize: fontSizeForPanel(height),
      ),
      width,
      height,
    );
  }

  Future<OutgoingFrame> _scroll(int width, int height) async {
    var strip = _strip;
    var stripWidth = _stripWidth;
    if (strip == null || strip.length != stripWidth * height) {
      final raster = await rasterScrollStrip(
        text: text,
        panelWidth: width,
        panelHeight: height,
        foreground: foreground.toRgb565(),
        background: background.toRgb565(),
        fontSize: fontSizeForPanel(height),
      );
      strip = raster.pixels;
      stripWidth = raster.width;
      _strip = strip;
      _stripWidth = stripWidth;
    }
    final cropped = cropScrollWindow(
      strip: [for (final pixel in strip) pixel.value],
      stripWidth: stripWidth,
      height: height,
      windowWidth: width,
      offset: _scrollOffset,
    );
    return OutgoingFrame(
      preview: ComposedFrame(
        width: width,
        height: height,
        pixels: asColors(cropped),
      ),
      delivery: PixelDelivery.rle,
    );
  }

  Future<OutgoingFrame> _clock(int width, int height) {
    final now = DateTime.now();
    return _textFrame(
      rasterClock(
        time: formatClockTime(now),
        date: formatClockDate(now),
        width: width,
        height: height,
        foreground: foreground.toRgb565(),
        background: background.toRgb565(),
      ),
      width,
      height,
    );
  }

  Future<OutgoingFrame> _textFrame(
    Future<List<Rgb565Color>> pixels,
    int width,
    int height,
  ) async {
    return OutgoingFrame(
      preview: ComposedFrame(
        width: width,
        height: height,
        pixels: await pixels,
      ),
      delivery: PixelDelivery.rle,
    );
  }

  (int, int)? _panel() {
    final width = _connection.panelWidth;
    final height = _connection.panelHeight;
    if (width == null || height == null || width <= 0 || height <= 0) {
      return null;
    }
    return (width, height);
  }

  bool _linkGone(MoliException error) {
    final session = _connection.session;
    return shouldCloseForLinkLoss(
      error,
      portClosed: session == null || session.port.isClosed,
      sessionConnected: session != null && session.isConnected,
    );
  }

  Future<void> _onLinkLost(MoliException error) async {
    _stopLoops();
    _cancelInFlight();
    if (_closingForLoss || _disposed) return;
    _closingForLoss = true;
    statusMessage = '${error.kind.label}: ${error.message}';
    _connection.appendLog('${error.kind.label}: ${error.message}');
    try {
      if (_connection.phase == ConnectionPhase.connected ||
          _connection.phase == ConnectionPhase.connecting) {
        await _connection.disconnect();
      }
    } catch (closeError) {
      if (!_disposed) {
        _connection.appendLog('断开失败: $closeError');
      }
    } finally {
      _closingForLoss = false;
      _notify();
    }
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }
}

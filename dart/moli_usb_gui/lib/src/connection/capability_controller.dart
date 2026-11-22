import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import 'connection_controller.dart';

/// Why orientation cannot be changed, or null when the user may pick one.
///
/// [orientations] is the list the connected geometry reported.
String? orientationUnavailableReason(List<int> orientations) {
  if (orientations.isEmpty) return '当前设备没有可切换的显示方向';
  if (orientations.length == 1) return '当前设备只有一个显示方向';
  return null;
}

/// Capability commands for one connected screen.
///
/// Writes go through [ConnectionController.submit]. The widget tree reads
/// snapshots on the connection controller and this object's labels; it does
/// not hold the session. Flash burn, NAND write, and firmware update are not
/// part of this controller.
final class CapabilityController extends ChangeNotifier {
  CapabilityController({
    required ConnectionController connection,
    required bool Function() contentSending,
    this.probeInterval = const Duration(seconds: 2),
  }) : _connection = connection,
       _contentSending = contentSending {
    _connection.addListener(_onConnection);
    _sync();
  }

  /// Host default for the lamp-count field. Not a protocol fact.
  static const int defaultLampCount = 64;

  /// LED index is one byte, so the host can address 0..255.
  static const int maxLampCount = 256;

  final ConnectionController _connection;
  final bool Function() _contentSending;
  final Duration probeInterval;

  int backlight = 0;
  int red = 0;
  int green = 80;
  int blue = 180;
  int lampCount = defaultLampCount;
  bool lampCountValid = true;
  String? lampCountMessage;
  int? selectedOrientation;
  String? keyLabel;

  int _commandsInFlight = 0;
  bool _backlightBusy = false;
  int? _pendingBacklight;
  bool _disposed = false;
  Timer? _probeTimer;
  StreamSubscription<InputEvent>? _inputSub;
  CancelToken _token = CancelToken();

  bool get commandInFlight => _commandsInFlight > 0;

  bool get listeningForKeys => _inputSub != null;

  bool get backlightSupported => _connection.hasBacklight;

  bool get rgbSupported => _connection.hasRgbLed;

  bool get keySupported => _connection.hasKeyInput;

  void setBacklight(int pwm) {
    final next = pwm.clamp(0, 1000);
    backlight = next;
    if (!backlightSupported ||
        _connection.phase != ConnectionPhase.connected) {
      _notify();
      return;
    }
    _pendingBacklight = next;
    unawaited(_flushBacklight());
    _notify();
  }

  Future<void> setOrientation(int orientation) async {
    final choices = _connection.supportedOrientations;
    if (choices.length < 2 || !choices.contains(orientation)) return;
    if (_connection.phase != ConnectionPhase.connected) return;
    selectedOrientation = orientation;
    _notify();
    await _run(
      (device, token) => device.setOrientation(orientation, token: token),
    );
  }

  void setLedColor({int? red, int? green, int? blue}) {
    if (red != null) this.red = red.clamp(0, 255);
    if (green != null) this.green = green.clamp(0, 255);
    if (blue != null) this.blue = blue.clamp(0, 255);
    _notify();
  }

  void setLampCountText(String raw) {
    final parsed = int.tryParse(raw.trim());
    if (parsed == null || parsed < 1 || parsed > maxLampCount) {
      lampCountValid = false;
      lampCountMessage = '灯数量需要是 1 到 $maxLampCount 的整数';
    } else {
      lampCountValid = true;
      lampCount = parsed;
      lampCountMessage = null;
    }
    _notify();
  }

  /// Stages [lampCount] lamps, then applies once.
  ///
  /// Does not call [MoliDeviceApi.setLed]: that applies after every lamp.
  Future<void> applyRgb() async {
    if (!rgbSupported || _connection.phase != ConnectionPhase.connected) {
      return;
    }
    if (!lampCountValid) return;
    final count = lampCount;
    final ledRed = red;
    final ledGreen = green;
    final ledBlue = blue;
    await _run((device, token) async {
      for (var index = 0; index < count; index++) {
        await device.stageLed(
          index,
          red: ledRed,
          green: ledGreen,
          blue: ledBlue,
          token: token,
        );
      }
      await device.applyLeds(token: token);
    });
  }

  /// One idle tick. Skips when content, a capability command, or the queue
  /// is already busy. Drops bytes already received and does not write
  /// `00 30 00 01 00 00`.
  Future<void> probeIfIdle() async {
    if (_disposed || _connection.phase != ConnectionPhase.connected) return;
    if (_commandsInFlight > 0 || _contentSending() || _connection.queueBusy) {
      return;
    }
    final token = _token;
    final pending = _connection.trySubmit(
      (device) => device.discardPending(token: token),
    );
    if (pending == null) return;
    _commandsInFlight++;
    try {
      await pending;
    } on MoliException catch (error) {
      await _handle(error);
    } catch (error) {
      if (!_disposed && _connection.phase == ConnectionPhase.connected) {
        _connection.appendLog('链路探测失败: $error');
      }
    } finally {
      _commandsInFlight--;
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _stopProbeTimer();
    _cancelInput();
    _token.cancel('已关闭');
    _connection.removeListener(_onConnection);
    super.dispose();
  }

  Future<void> _flushBacklight() async {
    if (_backlightBusy || _disposed) return;
    if (!backlightSupported) return;
    _backlightBusy = true;
    _commandsInFlight++;
    final token = _token;
    _notify();
    try {
      while (_pendingBacklight != null &&
          !_disposed &&
          !token.isCancelled &&
          _connection.phase == ConnectionPhase.connected &&
          backlightSupported) {
        final pwm = _pendingBacklight!;
        _pendingBacklight = null;
        await _connection.submit(
          (device) => device.setBacklight(pwm, token: token),
        );
      }
    } on MoliException catch (error) {
      _pendingBacklight = null;
      await _handle(error);
    } catch (error) {
      _pendingBacklight = null;
      if (!_disposed && !token.isCancelled) {
        _connection.appendLog('背光设置失败: $error');
      }
    } finally {
      _backlightBusy = false;
      _commandsInFlight--;
      if (!_disposed) {
        _notify();
        if (_pendingBacklight != null &&
            _connection.phase == ConnectionPhase.connected) {
          unawaited(_flushBacklight());
        }
      }
    }
  }

  Future<void> _run(
    Future<void> Function(MoliDeviceApi device, CancelToken token) action,
  ) async {
    if (_disposed || _connection.phase != ConnectionPhase.connected) return;
    _commandsInFlight++;
    final token = _token;
    _notify();
    try {
      await _connection.submit((device) => action(device, token));
    } on MoliException catch (error) {
      await _handle(error);
    } catch (error) {
      if (!_disposed && !token.isCancelled) {
        _connection.appendLog('命令失败: $error');
      }
    } finally {
      _commandsInFlight--;
      _notify();
    }
  }

  Future<void> _handle(MoliException error) async {
    if (_disposed) return;
    if (error.kind == MoliErrorKind.cancelled || _token.isCancelled) return;
    if (_connection.phase != ConnectionPhase.connected) return;
    _connection.appendLog('${error.kind.label}: ${error.message}');
  }

  void _onConnection() {
    _sync();
    _notify();
  }

  void _sync() {
    final connected = _connection.phase == ConnectionPhase.connected;
    if (!connected) {
      _token.cancel('已断开');
      _token = CancelToken();
      _pendingBacklight = null;
      _stopProbeTimer();
      _cancelInput();
      keyLabel = null;
      selectedOrientation = null;
      return;
    }
    _ensureProbeTimer();
    _ensureInput();
  }

  void _ensureProbeTimer() {
    if (probeInterval <= Duration.zero) return;
    _probeTimer ??= Timer.periodic(probeInterval, (_) {
      unawaited(probeIfIdle());
    });
  }

  void _stopProbeTimer() {
    _probeTimer?.cancel();
    _probeTimer = null;
  }

  void _ensureInput() {
    if (_inputSub != null || !_connection.hasKeyInput) return;
    final session = _connection.session;
    final events = _connection.keyEvents;
    if (events == null || session == null) return;
    _inputSub = events.listen(
      (event) {
        keyLabel = event.isPressed ? '按下' : '释放';
        _notify();
      },
      onError: (Object error, StackTrace _) {
        if (error is MoliException) {
          _connection.reportInputFailure(session, error);
        }
      },
    );
  }

  void _cancelInput() {
    final sub = _inputSub;
    _inputSub = null;
    if (sub != null) unawaited(sub.cancel());
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }
}

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import '../transport/host_binding.dart';
import '../content/link_loss.dart';
import 'command_queue.dart';
import 'panel_geometry.dart';

enum ConnectionPhase {
  disconnected,
  connecting,
  connected,
  disconnecting,
}

/// One window, one session.
///
/// Connect, disconnect and [submit] share [CommandQueue] so a later wave can
/// send frames on this session without opening a second port. [submit] does
/// not send anything by itself.
final class ConnectionController extends ChangeNotifier {
  ConnectionController({
    required List<TransportProvider> providers,
    SessionOptions sessionOptions =
        const SessionOptions(readSelfDescription: true),
    this.pollInterval = const Duration(milliseconds: 1500),
    bool pollWhileDisconnected = false,
    Future<void> Function()? requestGrantedPort,
    Stream<void>? portEvents,
    this.grantedPortsHint,
  })  : _manager = MoliDeviceManager(providers: providers),
        _sessionOptions = sessionOptions,
        _pollWhileDisconnected = pollWhileDisconnected,
        _requestGrantedPort = requestGrantedPort {
    _portSub = portEvents?.listen((_) {
      if (_phase == ConnectionPhase.disconnected ||
          _phase == ConnectionPhase.connected) {
        unawaited(refresh());
      }
    });
    _syncPoll();
  }

  factory ConnectionController.fromBinding(
    HostTransportBinding binding, {
    SessionOptions sessionOptions =
        const SessionOptions(readSelfDescription: true),
    Duration pollInterval = const Duration(milliseconds: 1500),
  }) {
    return ConnectionController(
      providers: [binding.provider],
      sessionOptions: sessionOptions,
      pollInterval: pollInterval,
      pollWhileDisconnected: binding.pollWhileDisconnected,
      requestGrantedPort: binding.requestGrantedPort,
      portEvents: binding.portEvents,
      grantedPortsHint: binding.grantedPortsHint,
    );
  }

  static final List<({String label, DeviceProfile profile})> profileChoices =
      <({String label, DeviceProfile profile})>[
    (label: 'MINI', profile: DeviceProfile.mini),
    (label: 'LITE', profile: DeviceProfile.lite),
    (label: 'PRO', profile: DeviceProfile.pro),
    (label: '通用', profile: DeviceProfile.generic),
  ];

  final MoliDeviceManager _manager;
  final SessionOptions _sessionOptions;
  final Duration pollInterval;
  final bool _pollWhileDisconnected;
  final Future<void> Function()? _requestGrantedPort;
  final String? grantedPortsHint;
  final CommandQueue _queue = CommandQueue();
  final List<String> _log = <String>[];

  StreamSubscription<void>? _portSub;
  Timer? _poll;
  Future<void>? _refreshing;

  ConnectionPhase _phase = ConnectionPhase.disconnected;
  List<DeviceCandidate> _candidates = const <DeviceCandidate>[];
  String? _selectedKey;
  DeviceCandidate? _lockedCandidate;
  String _profileName = DeviceProfile.nameGeneric;
  String _statusText = '未连接';
  String? _geometryWarning;
  int? _panelWidth;
  int? _panelHeight;
  DeviceCapabilities? _capabilities;
  List<int> _orientations = const <int>[];
  int _submittedActions = 0;
  MoliSession? _session;
  MoliDeviceApi? _device;
  CancelToken? _connectToken;
  Future<void>? _connectInFlight;
  Timer? _reconnectTimer;
  int _reconnectGeneration = 0;
  bool _reconnecting = false;
  bool _disposed = false;
  ({Object error, StackTrace stack})? _lastConnectFailure;
  ({Object error, StackTrace stack})? get lastConnectFailure => _lastConnectFailure;

  ConnectionPhase get phase => _phase;
  List<DeviceCandidate> get candidates => List.unmodifiable(_candidates);
  List<String> get log => List.unmodifiable(_log);
  String get statusText => _statusText;
  String? get geometryWarning => _geometryWarning;
  int? get panelWidth => _panelWidth;
  int? get panelHeight => _panelHeight;

  /// Capability snapshot. Null unless [ConnectionPhase.connected], so the
  /// widget tree does not read a live session.
  DeviceCapabilities? get capabilities =>
      _phase == ConnectionPhase.connected ? _capabilities : null;

  /// Orientations the connected geometry reports. Empty unless connected.
  List<int> get supportedOrientations => _phase == ConnectionPhase.connected
      ? List.unmodifiable(_orientations)
      : const <int>[];

  bool get hasBacklight => capabilities?.backlight ?? false;
  bool get hasRgbLed => capabilities?.rgbLed ?? false;
  bool get hasKeyInput => capabilities?.keyInput ?? false;

  /// Device actions that have begun running on [submit] / [trySubmit].
  int get submittedActions => _submittedActions;

  /// True while a connect, disconnect, content send, or capability command
  /// occupies the shared queue.
  bool get queueBusy => _queue.isBusy;

  String get profileName => _profileName;
  String? get selectedKey => _selectedKey;
  bool get canRequestPort => _requestGrantedPort != null;

  bool get controlsLocked => _phase != ConnectionPhase.disconnected;
  bool get dropdownsEnabled => !controlsLocked;

  bool get connectEnabled =>
      _phase == ConnectionPhase.disconnected && selectedCandidate != null;

  bool get disconnectEnabled =>
      _phase == ConnectionPhase.connecting ||
      _phase == ConnectionPhase.connected ||
      _reconnecting;

  DeviceCandidate? get selectedCandidate {
    final key = _selectedKey;
    if (key == null) return null;
    for (final candidate in _candidates) {
      if (candidate.transport.transportKey == key) return candidate;
    }
    return _lockedCandidate;
  }

  DeviceProfile get selectedProfile {
    for (final choice in profileChoices) {
      if (choice.profile.name == _profileName) return choice.profile;
    }
    return DeviceProfile.generic;
  }

  /// The live session, only while [ConnectionPhase.connected].
  MoliDeviceApi? get connectedDevice =>
      _phase == ConnectionPhase.connected ? _device : null;

  /// Same session [submit] uses. Null unless connected.
  MoliSession? get session =>
      _phase == ConnectionPhase.connected ? _session : null;

  /// Key events while connected and the device reports key input.
  ///
  /// The page must not subscribe. The capability controller owns the
  /// subscription and cancels it on disconnect.
  Stream<InputEvent>? get keyEvents {
    if (_phase != ConnectionPhase.connected) return null;
    if (!(_capabilities?.keyInput ?? false)) return null;
    return _device?.inputEvents;
  }

  String get centerText {
    return switch (_phase) {
      ConnectionPhase.connecting => '连接中',
      ConnectionPhase.connected => '已连接，尚未发送画面',
      ConnectionPhase.disconnecting => '断开中',
      ConnectionPhase.disconnected => '未连接',
    };
  }

  void selectCandidate(String? transportKey) {
    if (controlsLocked) return;
    if (_selectedKey != transportKey) {
      _cancelReconnect();
      _lockedCandidate = null;
    }
    _selectedKey = transportKey;
    notifyListeners();
  }

  void selectProfile(String name) {
    if (controlsLocked) return;
    if (_profileName != name) _cancelReconnect();
    _profileName = name;
    notifyListeners();
  }

  Future<void> refresh() {
    final inFlight = _refreshing;
    if (inFlight != null) return inFlight;
    final run = _refreshNow();
    _refreshing = run;
    return run.whenComplete(() {
      if (identical(_refreshing, run)) _refreshing = null;
    });
  }

  /// User-gesture entry for the web 添加设备 button.
  ///
  /// The injected callback calls `requestWebSerialPort` before awaiting
  /// anything else. Do not wrap this in a timer.
  Future<void> requestGrantedPort() async {
    final request = _requestGrantedPort;
    if (request == null) return;
    try {
      await request();
    } on MoliException catch (error) {
      _logException(error);
    } catch (error) {
      _appendLog('添加设备失败: $error');
    }
    await refresh();
  }

  /// Opens the selected candidate with that profile's serial settings.
  ///
  /// Refuses a second connect while a session exists. Yields once so the
  /// scaffold can paint 连接中 before the handshake runs.
  Future<void> connect() {
    _cancelReconnect();
    return _trackConnect();
  }

  Future<void> _trackConnect() {
    final existing = _connectInFlight;
    if (existing != null) return existing;
    final run = _connectImpl();
    _connectInFlight = run;
    return run.whenComplete(() {
      if (identical(_connectInFlight, run)) _connectInFlight = null;
    });
  }

  Future<void> _connectImpl() async {
    if (_disposed) return;
    if (_phase != ConnectionPhase.disconnected) {
      throw StateError('已有连接，拒绝再次连接（$_phase）');
    }
    final candidate = selectedCandidate;
    if (candidate == null) {
      throw StateError('未选择设备');
    }
    final profile = selectedProfile;
    final token = CancelToken();
    _lastConnectFailure = null;
    _connectToken = token;
    _phase = ConnectionPhase.connecting;
    _statusText = '连接中';
    _geometryWarning = null;
    _lockedCandidate = candidate;
    _appendLog(
      '正在连接 ${candidate.transport.displayName}（${profile.name}）',
    );
    _syncPoll();
    notifyListeners();
    await Future<void>.delayed(Duration.zero);
    if (token.isCancelled) {
      if (!_disposed) _enterDisconnected(status: '已断开');
      return;
    }

    try {
      final session = await _manager.openCandidate(
        candidate,
        profile: profile,
        settingsOverride: profile.serial,
        options: _sessionOptions,
        token: token,
      );
      if (token.isCancelled || _phase == ConnectionPhase.disconnecting) {
        await session.disconnect();
        if (!_disposed) _enterDisconnected(status: '已断开');
        return;
      }
      final device = MoliDeviceApi(session);
      final reported = device.display;
      final geometry = resolvePanelGeometry(
        reportedWidth: reported.width,
        reportedHeight: reported.height,
        profile: profile,
      );
      _session = session;
      _device = device;
      _panelWidth = geometry.width;
      _panelHeight = geometry.height;
      _capabilities = device.capabilities;
      _orientations = List<int>.unmodifiable(device.display.orientations);
      _geometryWarning = geometry.warning;
      if (geometry.warning != null) _appendLog(geometry.warning!);
      final uid = device.id?.value ?? candidate.id.value;
      _phase = ConnectionPhase.connected;
      _statusText = '已连接 $uid ${geometry.width}x${geometry.height}';
      _appendLog(_statusText);
      _appendLog('串口参数 ${session.appliedSettings}');
      _syncPoll();
      notifyListeners();
    } on MoliException catch (error, stack) {
      if (_disposed) return;
      _lastConnectFailure = (error: error, stack: stack);
      _enterDisconnectedFromError(error);
    } catch (error, stack) {
      if (_disposed) return;
      _lastConnectFailure = (error: error, stack: stack);
      _appendLog('连接失败: $error');
      _enterDisconnected(status: '连接失败: $error');
    } finally {
      _connectToken = null;
    }
  }

  /// Cancels an in-flight connect, or closes the live session.
  Future<void> disconnect() async {
    final pending = _reconnecting;
    _cancelReconnect();
    if (pending && _phase == ConnectionPhase.disconnected) {
      _enterDisconnected(status: '已断开');
      return;
    }
    if (_phase == ConnectionPhase.disconnected ||
        _phase == ConnectionPhase.disconnecting) {
      return;
    }
    if (_phase == ConnectionPhase.connecting) {
      _connectToken?.cancel('用户断开');
      _phase = ConnectionPhase.disconnecting;
      _statusText = '断开中';
      notifyListeners();
      await _connectInFlight;
      return;
    }
    _phase = ConnectionPhase.disconnecting;
    _statusText = '断开中';
    notifyListeners();
    await _queue.run(() async {
      final device = _device;
      _device = null;
      _session = null;
      if (device != null) {
        try {
          await device.close();
        } on MoliException catch (error) {
          _logException(error);
        }
      }
    });
    _enterDisconnected(status: '已断开');
  }

  /// Runs [action] on the connected device, after any earlier submission.
  ///
  /// Does not open a port. Content sends and capability commands go through
  /// this queue. Callers must not submit another frame while this future is
  /// still running.
  Future<T> submit<T>(Future<T> Function(MoliDeviceApi device) action) {
    final expected = _device;
    return _queue.run(() => _deviceAction(expected, action));
  }

  /// Like [submit], but returns null when the queue is already busy.
  ///
  /// Idle probes use this so a tick is skipped instead of queued behind a
  /// content send or a capability command.
  Future<T>? trySubmit<T>(Future<T> Function(MoliDeviceApi device) action) {
    if (_phase != ConnectionPhase.connected || _device == null) return null;
    final expected = _device;
    return _queue.tryRun(() => _deviceAction(expected, action));
  }

  Future<T> _deviceAction<T>(
    MoliDeviceApi? device,
    Future<T> Function(MoliDeviceApi device) action,
  ) async {
    if (_phase != ConnectionPhase.connected || device == null ||
        !identical(_device, device)) {
      throw const MoliException.disconnected(
        '没有已连接的会话',
        operation: 'submit',
      );
    }
    _submittedActions++;
    try {
      return await action(device);
    } on MoliException catch (error) {
      await _recoverLink(device.session, error);
      rethrow;
    }
  }

  void reportInputFailure(MoliSession expected, MoliException error) {
    if (!_disposed && identical(_session, expected)) {
      unawaited(_queue.run(() => _recoverLink(expected, error)));
    }
  }

  Future<void> _recoverLink(MoliSession expected, MoliException error) async {
    if (_disposed || _phase != ConnectionPhase.connected ||
        !identical(_session, expected) ||
        !shouldCloseForLinkLoss(error, portClosed: expected.port.isClosed,
            sessionConnected: expected.isConnected)) return;
    final target = _lockedCandidate;
    final profile = selectedProfile;
    final generation = ++_reconnectGeneration;
    _phase = ConnectionPhase.disconnecting;
    _statusText = '断开中';
    notifyListeners();
    try {
      await expected.disconnect();
    } catch (_) {
      // Recover only by opening a fresh session, never by reusing this port.
    }
    if (_disposed) return;
    if (target != null && generation == _reconnectGeneration) {
      _reconnecting = true;
      _scheduleReconnect(target, profile, generation, 0);
    }
    _enterDisconnectedFromError(error);
  }

  void _cancelReconnect() {
    _reconnectGeneration++;
    _reconnecting = false;
    _reconnectTimer?.cancel();
    _reconnectTimer = null;
  }

  void _scheduleReconnect(DeviceCandidate target, DeviceProfile profile,
      int generation, int attempt) {
    if (attempt >= 4) {
      _cancelReconnect();
      notifyListeners();
      return;
    }
    _reconnectTimer = Timer(Duration(seconds: 1 << attempt), () async {
      bool valid() => !_disposed && generation == _reconnectGeneration &&
          _phase == ConnectionPhase.disconnected &&
          selectedProfile.name == profile.name &&
          _selectedKey == target.transport.transportKey;
      if (!valid()) return;
      // Discover enumerates already granted Web Serial ports; never request one.
      List<DeviceCandidate> found;
      try {
        found = await _manager.discover();
      } catch (_) {
        found = const [];
      }
      if (!valid()) return;
      final old = target.transport;
      for (final candidate in found) {
        final next = candidate.transport;
        if (next.transportKey != old.transportKey || next.kind != old.kind) continue;
        if (next.usb?.vendorId != old.usb?.vendorId ||
            next.usb?.productId != old.usb?.productId ||
            next.usb?.serialNumber != old.usb?.serialNumber) {
          _cancelReconnect();
          notifyListeners();
          return;
        }
        if (candidate.isOpenable && !candidate.isBusy) {
          _candidates = found;
          await _trackConnect();
          if (_disposed || generation != _reconnectGeneration) return;
          if (_phase == ConnectionPhase.connected) {
            _cancelReconnect();
            return;
          }
        }
        break;
      }
      if (valid()) _scheduleReconnect(target, profile, generation, attempt + 1);
    });
  }

  @override
  void dispose() {
    _disposed = true;
    _cancelReconnect();
    _connectToken?.cancel('已关闭');
    _poll?.cancel();
    unawaited(_portSub?.cancel() ?? Future<void>.value());
    _queue.close();
    final device = _device;
    _device = null;
    _session = null;
    if (device != null) {
      unawaited(device.close());
    }
    super.dispose();
  }

  Future<void> _refreshNow() async {
    try {
      final found = await _manager.discover();
      if (_disposed) return;
      _applyCandidates(found);
    } on MoliException catch (error) {
      _logException(error);
    } catch (error) {
      _appendLog('刷新失败: $error');
    }
    if (!_disposed) notifyListeners();
  }

  void _applyCandidates(List<DeviceCandidate> fresh) {
    if ((_reconnecting || _phase != ConnectionPhase.disconnected) && _selectedKey != null) {
      final stillThere = fresh.any(
        (candidate) => candidate.transport.transportKey == _selectedKey,
      );
      if (!stillThere && _lockedCandidate != null) {
        _candidates = <DeviceCandidate>[...fresh, _lockedCandidate!];
      } else {
        _candidates = fresh;
      }
      return;
    }
    _candidates = fresh;
    final key = _selectedKey;
    if (key != null &&
        !fresh.any((candidate) => candidate.transport.transportKey == key)) {
      _selectedKey = null;
    }
  }

  void _enterDisconnectedFromError(MoliException error) {
    _logException(error);
    final status = switch (error.kind) {
      MoliErrorKind.cancelled => '已断开',
      _ => '已断开 ${error.kind.label}',
    };
    _enterDisconnected(status: status);
  }

  void _enterDisconnected({required String status}) {
    _device = null;
    _session = null;
    _panelWidth = null;
    _panelHeight = null;
    _capabilities = null;
    _orientations = const <int>[];
    _phase = ConnectionPhase.disconnected;
    _statusText = status;
    _geometryWarning = null;
    _syncPoll();
    notifyListeners();
  }

  void _logException(MoliException error) {
    _appendLog('${error.kind.label}: ${error.message}');
  }

  /// Appends one line to the on-screen log.
  void appendLog(String message) {
    _appendLog(message);
    notifyListeners();
  }

  /// Clears only the on-screen history; the session is left untouched.
  void clearLog() {
    if (_log.isEmpty) return;
    _log.clear();
    notifyListeners();
  }

  void _appendLog(String message) {
    final now = DateTime.now();
    final stamp =
        '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';
    _log.add('$stamp $message');
    if (_log.length > 200) {
      _log.removeRange(0, _log.length - 200);
    }
  }

  void _syncPoll() {
    final should = _pollWhileDisconnected &&
        _phase == ConnectionPhase.disconnected &&
        pollInterval > Duration.zero;
    if (should) {
      _poll ??= Timer.periodic(pollInterval, (_) {
        if (_phase == ConnectionPhase.disconnected) {
          unawaited(refresh());
        }
      });
    } else {
      _poll?.cancel();
      _poll = null;
    }
  }
}

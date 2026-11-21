// Session layer: one live device, one serialized transaction stream.
//
// Responsibilities (spec/architecture.md, "会话约束"):
//   - own exactly one transport port,
//   - perform the `\0MSNCN` handshake and refuse to continue without it,
//   - serialize every request/response pair (the wire protocol has no request
//     id, so concurrent transactions would interleave),
//   - translate codec output into device commands and validate responses,
//   - surface typed errors with the device id attached,
//   - never touch dart:io / dart:html / dart:ffi: the port is injected.
//
// The session does not decide panel sizes or capabilities from a product name;
// it starts from the caller's [DeviceProfile] and prefers device-reported facts.

import 'dart:async';
import 'dart:typed_data';

import '../codec/adc_rle.dart';
import '../codec/binary.dart';
import '../codec/display.dart';
import '../codec/flash.dart';
import '../codec/frame.dart';
import '../codec/handshake.dart';
import '../codec/sfr.dart';
import '../errors.dart';
import '../model/device_info.dart';
import '../model/device_id.dart';
import '../model/device_profile.dart';
import '../model/display_geometry.dart';
import '../model/input_event.dart';
import '../transport/serial_settings.dart';
import '../transport/transport.dart';
import 'serial_queue.dart';
import 'session_options.dart';

/// A connected, handshaken MSU2 device.
///
/// Obtain one through `MoliDeviceManager`; direct construction is supported for
/// callers that already own a [TransportPort] (tests, custom transports).
final class MoliSession {
  MoliSession({
    required TransportPort port,
    required DeviceProfile profile,
    SessionOptions options = const SessionOptions(),
    DeviceId? knownDeviceId,
  })  : _port = port,
        _profile = profile,
        _options = options,
        _deviceId = knownDeviceId,
        _display = const DisplayCodec(),
        _scratch = const ScratchCodec(),
        _sfr = const SfrCodec(),
        _adc = const AdcCodec(),
        _flash = const FlashCodec(),
        _handshake = const HandshakeCodec();

  final TransportPort _port;
  final DeviceProfile _profile;
  final SessionOptions _options;
  DeviceId? _deviceId;

  final DisplayCodec _display;
  final ScratchCodec _scratch;
  final SfrCodec _sfr;
  final AdcCodec _adc;
  final FlashCodec _flash;
  final HandshakeCodec _handshake;

  final SerialQueue _queue = SerialQueue();

  DeviceInfo? _info;
  bool _connected = false;
  bool _closing = false;

  StreamController<InputEvent>? _inputController;
  Timer? _inputTimer;
  AdcKeyDetector? _keyDetector;
  int? _keyIdleLevel;

  /// The transport this session owns.
  TransportPort get port => _port;

  /// The profile used to open the device.
  DeviceProfile get profile => _profile;

  /// Negotiated device facts, available after [connect].
  DeviceInfo? get info => _info;

  /// Whether the handshake completed and the link is still usable.
  bool get isConnected => _connected;

  /// The resolved device id (provisional until the UID was read).
  DeviceId? get deviceId => _deviceId ?? _info?.id;

  /// Serial parameters the transport reported as applied.
  SerialSettings get appliedSettings => _port.settings;

  /// Options in force for this session.
  SessionOptions get options => _options;

  // ---------------------------------------------------------------- lifecycle

  /// Performs banner detection and the `\0MSNCN` challenge/response.
  ///
  /// Throws [MoliErrorKind.handshakeFailed] when no banner arrives within
  /// [SessionOptions.handshakeTimeout], [MoliErrorKind.protocolMismatch] when
  /// the banner is not an MSU2 banner, [MoliErrorKind.disconnected] when the
  /// transport ends, and [MoliErrorKind.cancelled] when [token] fires.
  Future<DeviceInfo> connect({CancelToken? token}) {
    return _guard('connect', () async {
      if (_connected) {
        throw MoliException.invalidArgument(
          'session is already connected',
          operation: 'connect',
        );
      }
      // The handshake is one uninterrupted exchange, so it runs inside the
      // queue. The self-description must NOT: it issues ordinary SFR requests,
      // and nesting [SerialQueue.run] inside a queued task would deadlock.
      final bannerVersion = await _queue.run(() async {
        final version = await _awaitBanner(token: token);
        await _sendChallenge(token: token);
        return version;
      });

      _connected = true;

      var info = DeviceInfo(
        id: _deviceId ?? DeviceId.transport(_port.descriptor.transportKey),
        profile: _profile,
        protocolVersion: bannerVersion,
        capabilities: _profile.capabilities,
        display: _profile.display,
        storage: _profile.storage,
      );
      _deviceId = info.id;
      _info = info;

      if (_options.readSelfDescription) {
        try {
          info = await _applySelfDescription(info, token: token);
          _info = info;
        } catch (error) {
          // The link stays usable; only the richer facts are missing.
          if (!_options.tolerateSelfDescriptionFailure) rethrow;
        }
      }
      return _info!;
    });
  }

  /// Closes the transport and stops input polling. Idempotent.
  Future<void> disconnect() async {
    if (_closing) return;
    _closing = true;
    _connected = false;
    await _stopInput();
    _queue.close();
    await _port.close();
    _closing = false;
  }

  // ------------------------------------------------------------- transactions

  /// Sends [frame] and returns the 6-byte response.
  ///
  /// When [expectEcho] is true (the default and the spec's observed behaviour)
  /// the response must start with the request's `command` and `subcommand`;
  /// otherwise the error kind is [MoliErrorKind.protocolDesynchronized].
  Future<Frame> request(
    Frame frame, {
    Duration? timeout,
    CancelToken? token,
    bool? expectEcho,
  }) {
    return _guard('request', () async {
      // State is checked before enqueuing so a closed session fails with a
      // classified error instead of the queue's StateError.
      if (!_connected) {
        throw MoliException.disconnected(
          'session is not connected',
          operation: 'request',
        );
      }
      return _queue.run(() async {
        token?.ensureNotCancelled(operation: 'request');
        await _port.write(
          frame.bytes,
          writeTimeout: _options.writeTimeout,
          token: token,
        );
        final response = await _readFrame(
          timeout ?? _options.operationTimeout,
          token: token,
        );
        if (expectEcho ?? _options.strictEcho) {
          if (response.command != frame.command ||
              response.subcommand != frame.subcommand) {
            throw MoliException.protocolDesynchronized(
              'response ${toHex(response.bytes)} does not echo request '
              '${toHex(frame.bytes)}',
              operation: 'request',
            );
          }
        }
        return response;
      });
    });
  }

  /// Sends [frame] and waits for its response (same as [request]).
  ///
  /// Kept as a named alias so call sites read as commands, while both paths
  /// still drain and validate the response: the protocol has no way to know
  /// which reply belongs to which write, so every write must be answered.
  Future<Frame> command(
    Frame frame, {
    Duration? timeout,
    CancelToken? token,
  }) =>
      request(frame, timeout: timeout, token: token);

  // --------------------------------------------------------------- display API

  /// Sets the drawing origin for subsequent display commands.
  Future<void> setOrigin(int x, int y, {CancelToken? token}) async {
    _requireDisplay(token);
    await request(_display.setXy(x, y), token: token);
  }

  /// Sets the region size for subsequent display commands.
  Future<void> setRegionSize(
    int width,
    int height, {
    CancelToken? token,
  }) async {
    _requireDisplay(token);
    await request(_display.setSize(width, height), token: token);
  }

  /// Sets the active region (origin + size) after bounds-checking it.
  Future<void> setRegion(Rect rect, {CancelToken? token}) async {
    _requireDisplay(token);
    final geometry = displayGeometry;
    rect.ensureWithin(geometry.width, geometry.height, operation: 'setRegion');
    await request(_display.setXy(rect.x, rect.y), token: token);
    await request(_display.setSize(rect.width, rect.height), token: token);
  }

  /// Sets the foreground/background colors used by text and mono operations.
  Future<void> setColors(
    Rgb565Color foreground,
    Rgb565Color background, {
    CancelToken? token,
  }) async {
    _requireDisplay(token);
    await request(_display.setColors(foreground, background), token: token);
  }

  /// Fills the current region with [color].
  Future<void> fill(Rgb565Color color, {CancelToken? token}) async {
    _requireDisplay(token);
    await request(_display.fillCurrent(color), token: token);
  }

  /// Clears the current region to black.
  Future<void> clear({CancelToken? token}) =>
      fill(Rgb565Colors.black, token: token);

  /// Sets the panel orientation (0..3), rejected when the panel disagrees.
  Future<void> setOrientation(int orientation, {CancelToken? token}) async {
    _requireDisplay(token);
    if (!displayGeometry.orientations.contains(orientation)) {
      throw MoliException.unsupportedCapability(
        'orientation $orientation is not supported by this panel '
        '(${displayGeometry.orientations})',
        operation: 'setOrientation',
        deviceId: deviceId?.value,
      );
    }
    await request(_display.setOrientation(orientation), token: token);
  }

  /// Sets the backlight PWM (0..1000). PRO only in vendor sources.
  Future<void> setBacklight(int pwm, {CancelToken? token}) async {
    _requireCapability(_profile.capabilities.backlight, 'backlight',
        'setBacklight');
    await request(_display.setBacklight(pwm), token: token);
  }

  /// Writes one RGB LED value to the staging area (values are 0..255).
  Future<void> setLed(
    int index,
    int red,
    int green,
    int blue, {
    CancelToken? token,
  }) async {
    _requireCapability(_profile.capabilities.rgbLed, 'rgbLed', 'setLed');
    await request(_display.setLed(index, red, green, blue), token: token);
  }

  /// Applies all staged LED values.
  Future<void> applyLeds({CancelToken? token}) async {
    _requireCapability(_profile.capabilities.rgbLed, 'rgbLed', 'applyLeds');
    await request(_display.applyLeds(), token: token);
  }

  /// Shows a color image stored in Flash at [page].
  Future<void> showFlashColor(int page, {CancelToken? token}) async {
    _requireCapability(
        _profile.capabilities.externalFlash, 'externalFlash', 'showFlashColor');
    await request(
      _display.showFlashColor(page, _profile.colorPageEncoding),
      token: token,
    );
  }

  /// Shows a monochrome image stored in Flash at [page].
  Future<void> showFlashMono(int page, {CancelToken? token}) async {
    _requireCapability(
        _profile.capabilities.externalFlash, 'externalFlash', 'showFlashMono');
    await request(_display.showFlashMono(page), token: token);
  }

  /// Streams [pixels] (RGB565, one entry per pixel) into the region [rect].
  ///
  /// This is the uncompressed path: action `0x07`, staged `0x04` frames,
  /// action `0x08` per 256-byte batch, action `0x09` at the end.
  Future<void> drawPixels(
    Rect rect,
    List<Rgb565Color> pixels, {
    CancelToken? token,
  }) async {
    _requireDisplay(token);
    rect.ensureWithin(displayGeometry.width, displayGeometry.height,
        operation: 'drawPixels');
    final expected = rect.width * rect.height;
    if (pixels.length != expected) {
      throw MoliException.invalidArgument(
        'pixel count ${pixels.length} does not match region $rect ($expected)',
        operation: 'drawPixels',
        deviceId: deviceId?.value,
      );
    }
    await setRegion(rect, token: token);
    final encoder = PixelStreamEncoder(_display, _scratch);
    for (final frame in encoder.encodeStream(pixels.toWireBytes())) {
      await request(frame, token: token);
    }
  }

  // ------------------------------------------------------------------ SFR API

  /// Reads one u8 SFR.
  Future<int> readSfrU8(int address, {CancelToken? token}) async {
    _requireCapability(_profile.capabilities.sfrU8Read, 'sfrU8Read',
        'readSfrU8');
    final response = await request(_sfr.readU8(address), token: token);
    return _sfr.decodeU8(response);
  }

  /// Writes one u8 SFR.
  Future<void> writeSfrU8(int address, int value, {CancelToken? token}) async {
    _requireCapability(_profile.capabilities.sfrU8Write, 'sfrU8Write',
        'writeSfrU8');
    await request(_sfr.writeU8(address, value), token: token);
  }

  /// Reads one u16 SFR.
  Future<int> readSfrU16(int address, {CancelToken? token}) async {
    _requireCapability(_profile.capabilities.sfrU16Read, 'sfrU16Read',
        'readSfrU16');
    final response = await request(_sfr.readU16(address), token: token);
    return _sfr.decodeU16(response);
  }

  // ------------------------------------------------------------------ ADC API

  /// Reads one ADC channel (0..255) and returns the raw u16 sample.
  ///
  /// The raw value stays inside the session; domain code consumes
  /// [inputEvents] instead.
  Future<int> readAdc(int channel, {CancelToken? token}) async {
    _requireCapability(_profile.capabilities.adc, 'adc', 'readAdc');
    final response = await request(_adc.readChannel(channel), token: token);
    return _adc.decodeSample(response);
  }

  /// Input events for devices with [DeviceCapabilities.keyInput].
  ///
  /// The ADC-based MINI key is polled at [SessionOptions.inputPollInterval] and
  /// debounced with [SessionOptions.keyDebounce]. Repeated reads return the
  /// same stream.
  Stream<InputEvent> get inputEvents {
    final existing = _inputController;
    if (existing != null) return existing.stream;
    final controller = StreamController<InputEvent>.broadcast(
      onListen: () => unawaited(_startInput()),
      onCancel: _stopInputPolling,
    );
    _inputController = controller;
    return controller.stream;
  }

  // ---------------------------------------------------------------- Flash API

  /// Reads one byte from the external medium.
  Future<int> flashReadByte(int address, {CancelToken? token}) async {
    _requireStorage('flashReadByte');
    final geometry = _profile.storage!;
    geometry.checkAddress(address, operation: 'flashReadByte');
    final response = await request(_flash.readByte(address), token: token);
    return _flash.decodeReadByte(response);
  }

  /// Writes [count] pages of staged data starting at [page].
  Future<void> flashWritePagesFromScratch(
    int page,
    int count, {
    CancelToken? token,
  }) async {
    _requireStorage('flashWritePagesFromScratch');
    _checkPageRange(
        _profile.storage!, page, count, 'flashWritePagesFromScratch');
    await request(_flash.writePagesFromScratch(page, count), token: token);
  }

  /// Erases [count] pages starting at [page].
  Future<void> flashErasePages(
    int page,
    int count, {
    CancelToken? token,
  }) async {
    _requireStorage('flashErasePages');
    _checkPageRange(_profile.storage!, page, count, 'flashErasePages');
    await request(_flash.erasePages(page, count), token: token);
  }

  /// Chip erase. Requires [DeviceCapabilities.storageChipErase].
  Future<void> flashChipErase({CancelToken? token}) async {
    _requireCapability(_profile.capabilities.storageChipErase, 'chipErase',
        'flashChipErase');
    await request(_flash.chipErase(), token: token);
  }

  /// Erases one NAND block. Requires [DeviceCapabilities.storageNandBlockErase].
  Future<void> nandEraseBlock(int block, {CancelToken? token}) async {
    _requireCapability(_profile.capabilities.storageNandBlockErase,
        'nandBlockErase', 'nandEraseBlock');
    await request(_flash.eraseNandBlock(block), token: token);
  }

  // -------------------------------------------------------------- self-report

  /// Reads and parses the 256-byte SFR self-description table at `0x0100`.
  Future<List<SfrTableEntry>> readSelfDescription({CancelToken? token}) async {
    final table = Uint8List(256);
    for (var i = 0; i < 256; i++) {
      table[i] = await readSfrU8(0x0100 + i, token: token);
    }
    return const SfrTableParser().parse(table);
  }

  Future<DeviceInfo> _applySelfDescription(
    DeviceInfo info, {
    CancelToken? token,
  }) async {
    final entries = await readSelfDescription(token: token);
    final byName = <String, SfrTableEntry>{
      for (final e in entries) e.name: e,
    };

    var id = info.id;
    final uidEntry = byName['MSN_UID'];
    if (uidEntry != null && uidEntry.address != 0) {
      // UID width is a P0 verification item; reading one u8 at the documented
      // address keeps the stream aligned even if the width guess is wrong.
      try {
        final uid = await readSfrU8(uidEntry.address, token: token);
        id = DeviceId('uid:${uid.toRadixString(16).padLeft(2, '0')}');
      } catch (_) {
        // Keep the transport-derived id.
      }
    }

    final width = await _tryReadValue(byName['Lcd_X'], token: token);
    final height = await _tryReadValue(byName['Lcd_Y'], token: token);
    final display =
        (width != null && height != null && width > 0 && height > 0)
            ? DisplayGeometry(
                width: width,
                height: height,
                origin: info.display.origin,
                orientations: info.display.orientations,
                pixelFormat: info.display.pixelFormat,
              )
            : info.display;

    final capabilities = info.capabilities.copyWith(
      externalFlash:
          info.capabilities.externalFlash || byName.containsKey('Flash_Info'),
      sfrU16Read:
          entries.any((e) => e.family == SfrEntryFamily.u16Register),
    );

    _deviceId = id;
    return info.withConfirmed(
      id: id,
      display: display,
      capabilities: capabilities,
      rawAttributes: {for (final e in entries) e.name: e.unit},
    );
  }

  Future<int?> _tryReadValue(
    SfrTableEntry? entry, {
    CancelToken? token,
  }) async {
    if (entry == null || entry.address == 0) return null;
    try {
      if (entry.family == SfrEntryFamily.u16Register) {
        return await readSfrU16(entry.address, token: token);
      }
      if (entry.family == SfrEntryFamily.u8Register) {
        return await readSfrU8(entry.address, token: token);
      }
    } catch (_) {
      return null;
    }
    return null;
  }

  // ------------------------------------------------------------------ internals

  /// Panel geometry in force: confirmed values when available.
  DisplayGeometry get displayGeometry => _info?.display ?? _profile.display;

  Future<void> _startInput() async {
    final capabilities = _info?.capabilities ?? _profile.capabilities;
    if (!capabilities.keyInput) return;
    final channel = _profile.keyAdcChannel ?? 9;
    // Establish the idle level from the mean of two samples, as the vendor demo
    // does. The threshold stays configurable and is not a protocol constant.
    try {
      final first = await readAdc(channel);
      final second = await readAdc(channel);
      _keyIdleLevel = (first + second) ~/ 2;
    } catch (_) {
      _keyIdleLevel = null;
    }
    final idle = _keyIdleLevel ?? 0;
    _keyDetector = AdcKeyDetector(
      channel: channel,
      pressThreshold: idle - _options.keyPressThresholdDelta,
      debounce: _options.keyDebounce,
      idleLevel: _keyIdleLevel,
    );
    _inputTimer ??= Timer.periodic(
      _options.inputPollInterval,
      (_) => unawaited(_pollKey()),
    );
  }

  Future<void> _pollKey() async {
    final detector = _keyDetector;
    final controller = _inputController;
    if (detector == null || controller == null || controller.isClosed) return;
    if (!_connected) return;
    try {
      final sample = await readAdc(detector.channel);
      final state = detector.feed(sample);
      if (state == null) return;
      controller.add(InputEvent(
        source: InputSource.adcKey,
        keyIndex: 0,
        state: state == KeyState.pressed
            ? InputState.pressed
            : InputState.released,
        timestamp: DateTime.now(),
      ));
    } catch (error) {
      if (!controller.isClosed) controller.addError(error);
    }
  }

  void _stopInputPolling() {
    _inputTimer?.cancel();
    _inputTimer = null;
  }

  Future<void> _stopInput() async {
    _stopInputPolling();
    _keyDetector = null;
    final controller = _inputController;
    _inputController = null;
    if (controller != null && !controller.isClosed) {
      await controller.close();
    }
  }

  void _checkPageRange(
    StorageGeometry geometry,
    int page,
    int count,
    String operation,
  ) {
    if (page < 0 || count <= 0) {
      throw MoliException.invalidArgument(
        'page must be non-negative and count positive '
        '(page=$page, count=$count)',
        operation: operation,
        deviceId: deviceId?.value,
      );
    }
    if (page + count > geometry.pageCount) {
      throw MoliException.storageCapacityExceeded(
        'pages $page..${page + count} exceed medium page count '
        '${geometry.pageCount}',
        operation: operation,
        deviceId: deviceId?.value,
      );
    }
  }

  void _requireDisplay(CancelToken? token) {
    token?.ensureNotCancelled(operation: 'display');
    if (!_connected) {
      throw MoliException.disconnected(
        'session is not connected',
        operation: 'display',
        deviceId: deviceId?.value,
      );
    }
    _requireCapability(_profile.capabilities.display, 'display', 'display');
  }

  void _requireStorage(String operation) {
    if (_profile.storage == null || !_profile.capabilities.externalFlash) {
      throw MoliException.unsupportedCapability(
        'device has no external storage',
        operation: operation,
        deviceId: deviceId?.value,
      );
    }
  }

  void _requireCapability(bool present, String capability, String operation) {
    if (!present) {
      throw MoliException.unsupportedCapability(
        'device/profile does not provide "$capability"',
        operation: operation,
        deviceId: deviceId?.value,
      );
    }
  }

  /// Reads exactly one frame from the port.
  ///
  /// Must be called from inside the serialized queue (or during the handshake,
  /// before any transaction is running).
  Future<Frame> _readFrame(Duration timeout, {CancelToken? token}) async {
    token?.ensureNotCancelled(operation: 'read');
    final bytes = await _port.readExact(
      kFrameLength,
      overallTimeout: timeout,
      token: token,
    );
    return Frame.fromBytes(bytes);
  }

  /// Sends the `\0MSNCN` challenge and waits for the identical echo.
  Future<void> _sendChallenge({CancelToken? token}) async {
    for (var attempt = 1; attempt <= _options.challengeAttempts; attempt++) {
      await _port.write(
        kHandshakeChallenge,
        writeTimeout: _options.writeTimeout,
        token: token,
      );
      final echoed = await _awaitEcho(
        kHandshakeChallenge,
        _options.confirmTimeout,
        token: token,
      );
      if (echoed) return;
      if (attempt == _options.challengeAttempts) {
        throw MoliException.handshakeFailed(
          'device did not echo the MSNCN challenge after '
          '${_options.challengeAttempts} attempts',
          operation: 'handshake',
          deviceId: deviceId?.value,
        );
      }
    }
  }

  /// Scans the byte stream for the exact [expected] sequence.
  ///
  /// Banners may still be in flight when the challenge is written, so the echo
  /// is located rather than assumed to be the next 6 bytes (`spec/verification`
  /// still has the response framing as an open item).
  Future<bool> _awaitEcho(
    List<int> expected,
    Duration timeout, {
    CancelToken? token,
  }) async {
    final deadline = DateTime.now().add(timeout);
    final window = <int>[];
    while (true) {
      token?.ensureNotCancelled(operation: 'handshake');
      final left = deadline.difference(DateTime.now());
      if (left <= Duration.zero) return false;
      final slice =
          left < _options.readSliceTimeout ? left : _options.readSliceTimeout;
      final Uint8List chunk;
      try {
        chunk = await _port.read(16, readTimeout: slice, token: token);
      } on MoliException catch (error) {
        if (error.kind == MoliErrorKind.timeout) continue;
        throw error.withContext(operation: 'handshake');
      }
      if (chunk.isEmpty) continue;
      for (final byte in chunk) {
        window.add(byte);
        if (window.length > expected.length) {
          window.removeAt(0);
        }
        if (window.length == expected.length) {
          var match = true;
          for (var i = 0; i < expected.length; i++) {
            if (window[i] != expected[i]) {
              match = false;
              break;
            }
          }
          if (match) return true;
        }
      }
    }
  }

  /// Scans the byte stream for a `\0MSNxx` banner.
  Future<int> _awaitBanner({CancelToken? token}) async {
    final deadline = DateTime.now().add(_options.handshakeTimeout);
    final window = <int>[];
    final banner = Uint8List(kFrameLength);
    var sawBytes = false;
    while (true) {
      token?.ensureNotCancelled(operation: 'handshake');
      final left = deadline.difference(DateTime.now());
      if (left <= Duration.zero) {
        // Silence and noise are different failures: the first means the port
        // is wrong or the device is off, the second means something answered
        // that is not an MSU2 display.
        if (sawBytes) {
          throw MoliException.protocolMismatch(
            'port produced data but no MSU2 banner within '
            '${_options.handshakeTimeout.inMilliseconds}ms',
            operation: 'handshake',
            deviceId: deviceId?.value,
          );
        }
        throw MoliException.handshakeFailed(
          'no MSU2 banner within ${_options.handshakeTimeout.inMilliseconds}ms',
          operation: 'handshake',
          deviceId: deviceId?.value,
        );
      }
      final slice =
          left < _options.readSliceTimeout ? left : _options.readSliceTimeout;
      final Uint8List chunk;
      try {
        chunk = await _port.read(16, readTimeout: slice, token: token);
      } on MoliException catch (error) {
        if (error.kind == MoliErrorKind.timeout) continue;
        throw error.withContext(operation: 'handshake');
      }
      if (chunk.isEmpty) continue;
      sawBytes = true;
      for (final byte in chunk) {
        window.add(byte);
        if (window.length > kFrameLength) window.removeAt(0);
        if (window.length == kFrameLength) {
          for (var i = 0; i < kFrameLength; i++) {
            banner[i] = window[i];
          }
          final version = _handshake.tryParseBanner(banner);
          if (version != null) return version;
        }
      }
    }
  }

  /// Adds operation/device context to a [MoliException].
  ///
  /// Anything that is not a [MoliException] is a programming error or a
  /// transport contract violation; it propagates unchanged so it cannot be
  /// mistaken for a classified protocol failure.
  Future<T> _guard<T>(String operation, Future<T> Function() body) async {
    try {
      return await body();
    } on MoliException catch (error) {
      throw error.withContext(operation: operation, deviceId: deviceId?.value);
    }
  }
}
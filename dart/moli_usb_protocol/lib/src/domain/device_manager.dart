// Domain API: enumeration, explicit device selection, capability-checked
// control.
//
// `spec/architecture.md` (领域操作):
//   - enumerate candidates without opening every port,
//   - open exactly one by [DeviceId] or by an explicit selector,
//   - report info/capabilities and allow closing the connection.
//
// This layer never imports a transport implementation: it only knows
// [TransportProvider]. That keeps the library usable from Web (Web Serial
// adapter) and Windows (native serial adapter) without any conditional import
// inside `lib/`.

import 'dart:async';

import '../codec/adc_rle.dart';
import '../codec/binary.dart';
import '../codec/sfr.dart';
import '../errors.dart';
import '../model/device_candidate.dart';
import '../model/device_capabilities.dart';
import '../model/device_id.dart';
import '../model/device_info.dart';
import '../model/device_profile.dart';
import '../model/display_geometry.dart';
import '../model/image_frame.dart';
import '../model/input_event.dart';
import '../session/session.dart';
import '../session/session_options.dart';
import '../transport/serial_settings.dart';
import '../transport/transport.dart';

/// Selects one candidate out of a list.
typedef DeviceSelector = bool Function(DeviceCandidate candidate);

/// Enumerates devices through one or more injected transports and opens exactly
/// one of them per session.
///
/// Multiple devices are represented by multiple independent sessions; a
/// session refuses to open a second port while it already owns one
/// (`spec/architecture.md`, 会话约束).
final class MoliDeviceManager {
  MoliDeviceManager({
    required List<TransportProvider> providers,
    SessionOptions options = const SessionOptions(),
  })  : assert(providers.length > 0, 'at least one provider is required'),
        _providers = List.unmodifiable(providers),
        _options = options;

  final List<TransportProvider> _providers;
  final SessionOptions _options;

  /// Transport families this manager can enumerate.
  List<String> get transportKinds =>
      List.unmodifiable(_providers.map((p) => p.kind));

  /// Lists candidates without opening any port.
  ///
  /// Profile hints come from transport metadata only; a hint is never treated
  /// as a capability source.
  Future<List<DeviceCandidate>> discover({CancelToken? token}) async {
    final candidates = <DeviceCandidate>[];
    for (final provider in _providers) {
      token?.ensureNotCancelled(operation: 'discover');
      final List<TransportPortDescriptor> descriptors;
      try {
        descriptors = await provider.listPorts();
      } on MoliException catch (error) {
        throw error.withContext(operation: 'discover');
      }
      for (final descriptor in descriptors) {
        candidates.add(DeviceCandidate(
          id: DeviceId.transport(descriptor.transportKey),
          transport: descriptor,
          profileHint: descriptor.usb?.product,
          isBusy: false,
        ));
      }
    }
    return List.unmodifiable(candidates);
  }

  /// Opens [id], which must have come from [discover] or a profile the caller
  /// configured.
  ///
  /// Throws [MoliErrorKind.deviceNotFound] when the id no longer matches a
  /// live candidate and [MoliErrorKind.deviceBusy] when the port is taken.
  Future<MoliSession> open(
    DeviceId id, {
    DeviceProfile? profile,
    SerialSettings? settingsOverride,
    SessionOptions? options,
    CancelToken? token,
  }) async {
    final candidates = await discover(token: token);
    final match = candidates.where((c) => c.id == id).toList();
    if (match.isEmpty) {
      throw MoliException.deviceNotFound(
        'no device with id $id',
        operation: 'open',
        deviceId: id.value,
      );
    }
    if (match.length > 1) {
      throw MoliException.invalidArgument(
        'device id $id matches ${match.length} candidates; '
        'use openCandidate with an explicit descriptor',
        operation: 'open',
        deviceId: id.value,
      );
    }
    return openCandidate(
      match.single,
      profile: profile,
      settingsOverride: settingsOverride,
      options: options,
      token: token,
    );
  }

  /// Opens the first candidate accepted by [selector].
  ///
  /// This is how a UI lets the user pick one screen out of several.
  Future<MoliSession> openFirst(
    DeviceSelector selector, {
    DeviceProfile? profile,
    SerialSettings? settingsOverride,
    SessionOptions? options,
    CancelToken? token,
  }) async {
    final candidates = await discover(token: token);
    final chosen = candidates.where(selector).toList();
    if (chosen.isEmpty) {
      throw MoliException.deviceNotFound(
        'no candidate matched the selector',
        operation: 'openFirst',
      );
    }
    return openCandidate(
      chosen.first,
      profile: profile,
      settingsOverride: settingsOverride,
      options: options,
      token: token,
    );
  }

  /// Opens one explicit candidate and completes the handshake.
  Future<MoliSession> openCandidate(
    DeviceCandidate candidate, {
    DeviceProfile? profile,
    SerialSettings? settingsOverride,
    SessionOptions? options,
    CancelToken? token,
  }) async {
    final resolvedProfile =
        profile ?? DeviceProfile.byNameOrDefault(candidate.profileHint);
    if (candidate.isBusy) {
      throw MoliException.deviceBusy(
        'port ${candidate.transport.displayName} is already open',
        operation: 'openCandidate',
        deviceId: candidate.id.value,
      );
    }

    final provider = _providerFor(candidate.transport);
    if (provider == null) {
      throw MoliException.deviceNotFound(
        'no provider serves transport family "${candidate.transport.kind}"',
        operation: 'openCandidate',
        deviceId: candidate.id.value,
      );
    }

    final TransportPort port;
    try {
      port = await provider.open(
        candidate.transport,
        settingsOverride:
            settingsOverride ??
            candidate.transport.confirmedSettings ??
            resolvedProfile.serial,
      );
    } on MoliException catch (error) {
      throw error.withContext(
        operation: 'openCandidate',
        deviceId: candidate.id.value,
      );
    }

    final session = MoliSession(
      port: port,
      profile: resolvedProfile,
      options: options ?? _options,
      knownDeviceId: candidate.id,
    );
    try {
      await session.connect(token: token);
    } catch (error) {
      await port.close();
      rethrow;
    }
    return session;
  }

  TransportProvider? _providerFor(TransportPortDescriptor descriptor) {
    for (final provider in _providers) {
      if (provider.kind == descriptor.kind) return provider;
    }
    return null;
  }
}

/// Thin, capability-checked facade over one connected [MoliSession].
///
/// The domain API deliberately exposes the flat operations from
/// `spec/architecture.md` (orientation, backlight, rect fill, image streaming,
/// storage, input) instead of raw frames, so callers cannot accidentally write
/// out-of-bounds geometry or unsupported commands.
final class MoliDeviceApi {
  MoliDeviceApi(this.session);

  final MoliSession session;

  DeviceInfo? get info => session.info;

  DeviceId? get id => session.deviceId;

  DeviceProfile get profile => session.profile;

  DeviceCapabilities get capabilities =>
      info?.capabilities ?? profile.capabilities;

  DisplayGeometry get display => info?.display ?? profile.display;

  SerialSettings get serial => session.appliedSettings;

  /// Sets the drawing origin.
  Future<void> setOrigin(int x, int y, {CancelToken? token}) =>
      session.setOrigin(x, y, token: token);

  /// Sets the region size.
  Future<void> setRegionSize(int width, int height, {CancelToken? token}) =>
      session.setRegionSize(width, height, token: token);

  /// Sets the active region after a bounds check.
  Future<void> setRegion(Rect rect, {CancelToken? token}) =>
      session.setRegion(rect, token: token);

  /// Sets the panel orientation, rejecting unsupported values.
  Future<void> setOrientation(int orientation, {CancelToken? token}) =>
      session.setOrientation(orientation, token: token);

  /// Sets the backlight PWM (0..1000) when the device supports it.
  Future<void> setBacklight(int pwm, {CancelToken? token}) =>
      session.setBacklight(pwm, token: token);

  /// Sets the foreground/background colors.
  Future<void> setColors(
    Rgb565Color foreground,
    Rgb565Color background, {
    CancelToken? token,
  }) =>
      session.setColors(foreground, background, token: token);

  /// Fills [rect] with [color].
  ///
  /// Bounds are checked against the reported panel before any byte is sent.
  Future<void> fillRect(
    Rect rect,
    Rgb565Color color, {
    CancelToken? token,
  }) async {
    await session.fillRect(rect, color, token: token);
  }

  /// Clears the whole panel to black.
  Future<void> clear({CancelToken? token}) async {
    await session.setRegion(
      Rect(0, 0, display.width, display.height),
      token: token,
    );
    await session.clear(token: token);
  }

  /// Streams a full RGB565 image covering the whole panel.
  Future<void> drawFrame(
    List<Rgb565Color> pixels, {
    CancelToken? token,
  }) {
    final expected = display.width * display.height;
    if (pixels.length != expected) {
      throw MoliException.invalidArgument(
        'frame has ${pixels.length} pixels, panel needs $expected '
        '(${display.width}x${display.height})',
        operation: 'drawFrame',
        deviceId: id?.value,
      );
    }
    return session.drawPixels(
      Rect(0, 0, display.width, display.height),
      pixels,
      token: token,
    );
  }

  /// Streams an RGB565 image into [rect].
  Future<void> drawPixels(
    Rect rect,
    List<Rgb565Color> pixels, {
    CancelToken? token,
  }) =>
      session.drawPixels(rect, pixels, token: token);

  /// Streams [pixels] into [rect] as two-color RLE frames.
  ///
  /// Same wire shape as `show_PC_Screen`: setXy/setSize, action `0x07`, then
  /// one write of the command `0x09` frames from [RleCodec.encode]. Those
  /// frames are not read. There is no action `0x08` and no trailing action
  /// `0x09`.
  ///
  /// When [maxFrames] is greater than 0 and [RleCodec.worstCaseFrameCount]
  /// exceeds it, this throws [MoliErrorKind.invalidArgument] and sends nothing.
  /// The message tells the caller to use [drawPixels].
  Future<void> drawPixelsRle(
    Rect rect,
    List<Rgb565Color> pixels, {
    int maxFrames = 0,
    CancelToken? token,
  }) async {
    final expected = rect.width * rect.height;
    if (pixels.length != expected) {
      throw MoliException.invalidArgument(
        'pixel count ${pixels.length} does not match region $rect ($expected)',
        operation: 'drawPixelsRle',
        deviceId: id?.value,
      );
    }
    final worst = RleCodec.worstCaseFrameCount(pixels.length);
    if (maxFrames > 0 && worst > maxFrames) {
      throw MoliException.invalidArgument(
        'RLE worst case needs $worst frames, which exceeds maxFrames '
        '$maxFrames; use drawPixels',
        operation: 'drawPixelsRle',
        deviceId: id?.value,
      );
    }
    final values = List<int>.generate(
      pixels.length,
      (index) => pixels[index].value & 0xFFFF,
      growable: false,
    );
    await session.drawPixelsRle(rect, values, token: token);
  }

  /// Sends host raster content using the device family's compact format.
  Future<void> drawPixelsCompact(Rect rect, List<Rgb565Color> pixels,
      {CancelToken? token}) {
    if (session.info?.profile.name == DeviceProfile.nameMini) {
      return session.drawPixels(rect, pixels, token: token, miniCompact: true);
    }
    return drawPixelsRle(rect, pixels, token: token);
  }

  /// Converts, scales and sends a decoded image. Without [rect], fills the panel.
  Future<void> writeImage(
    RgbaImage image, {
    Rect? rect,
    ImageWriteOptions options = const ImageWriteOptions(),
    CancelToken? token,
  }) => session.writeImage(image, rect: rect, options: options, token: token);

  /// Controls the first resize warning for this session.
  void setImageResolutionWarningEnabled(bool enabled) =>
      session.setImageResolutionWarningEnabled(enabled);

  /// Creates a bounded sender that drains before the device closes.
  ImageWriteQueue createImageWriteQueue({
    required int capacity,
    required ImageOverflowPolicy overflowPolicy,
    ImageWriteOptions options = const ImageWriteOptions(),
  }) => session.createImageWriteQueue(
    capacity: capacity,
    overflowPolicy: overflowPolicy,
    options: options,
  );

  /// Shows a color asset stored in device Flash at [page].
  Future<void> showFlashColor(int page, {CancelToken? token}) =>
      session.showFlashColor(page, token: token);

  /// Shows a monochrome asset stored in device Flash at [page].
  Future<void> showFlashMono(int page, {CancelToken? token}) =>
      session.showFlashMono(page, token: token);

  /// Reads one byte from the external medium.
  Future<int> flashReadByte(int address, {CancelToken? token}) =>
      session.flashReadByte(address, token: token);

  /// Writes [count] staged pages starting at [page].
  Future<void> flashWritePages(
    int page,
    int count, {
    CancelToken? token,
  }) =>
      session.flashWritePagesFromScratch(page, count, token: token);

  /// Erases [count] pages starting at [page].
  Future<void> flashErasePages(
    int page,
    int count, {
    CancelToken? token,
  }) =>
      session.flashErasePages(page, count, token: token);

  /// Strobes one RGB LED (0..255 per channel) and applies the staged values.
  ///
  /// This still applies immediately. To change several lamps with one apply,
  /// call [stageLed] for each index and then [applyLeds] once.
  Future<void> setLed(
    int index, {
    required int red,
    required int green,
    required int blue,
    CancelToken? token,
  }) async {
    await session.setLed(index, red, green, blue, token: token);
    await session.applyLeds(token: token);
  }

  /// Stages one RGB LED without applying the staged values.
  Future<void> stageLed(
    int index, {
    required int red,
    required int green,
    required int blue,
    CancelToken? token,
  }) =>
      session.setLed(index, red, green, blue, token: token);

  /// Applies staged LED values. Does not stage a new color.
  Future<void> applyLeds({CancelToken? token}) =>
      session.applyLeds(token: token);

  /// Reads one byte of the self-description table.
  ///
  /// This is the SFR u8 read the session already implements
  /// ([SfrTableParser.tableAddress]). It uses no new opcode and does not
  /// change pixels, backlight, orientation, or LEDs. Host idle timers must
  /// not call it: official demos never poll `0x0100`, and a read during a
  /// pixel stream is consumed as pixels.
  Future<int> probeLink({CancelToken? token}) =>
      session.readSfrU8(SfrTableParser.tableAddress, token: token);

  /// Drops bytes already received. Does not write, including the idle SFR read.
  Future<void> discardPending({CancelToken? token}) =>
      session.discardPending(token: token);

  /// Input events, for devices with [DeviceCapabilities.keyInput] (MINI).
  Stream<InputEvent> get inputEvents => session.inputEvents;

  /// Whether the connected device exposes a key.
  bool get hasKeyInput => capabilities.keyInput;

  /// Closes the device.
  Future<void> close() => session.disconnect();
}

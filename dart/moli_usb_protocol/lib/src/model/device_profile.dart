// Known device profiles.
//
// `spec/architecture.md` and `roadmap.md` 1.1: MINI, LITE and PRO are known
// profiles, NOT a closed enum. An unknown model that completes the handshake
// and reports the necessary capabilities must work as a generic MSU2 device.
//
// A profile only supplies *initial guesses* (serial parameters, panel size,
// storage geometry, color-page encoding preference). The confirmed facts always
// come from the handshake and the self-description table; nothing is inferred
// from the product name alone.

import '../codec/display.dart';
import '../codec/flash.dart';
import '../transport/serial_settings.dart';
import 'device_capabilities.dart';
import 'display_geometry.dart';

/// Named device profile.
///
/// Not an enum: callers may register their own profile through
/// [DeviceProfile.custom], and [DeviceProfile.generic] is used whenever the
/// model is unknown.
final class DeviceProfile {
  const DeviceProfile._({
    required this.name,
    required this.serial,
    required this.display,
    this.storage,
    required this.capabilities,
    required this.colorPageEncoding,
    this.storageMedium = FlashMediumProfileKind.serialFlash,
    this.keyAdcChannel,
  });

  /// Builds a profile for a model this library does not ship.
  ///
  /// Only use values you have verified for that firmware build. Passing a
  /// profile does not make the device trusted: the session still requires a
  /// successful handshake and still prefers device-reported geometry.
  factory DeviceProfile.custom({
    required String name,
    required SerialSettings serial,
    required DisplayGeometry display,
    StorageGeometry? storage,
    DeviceCapabilities capabilities = const DeviceCapabilities(),
    FlashColorPageEncoding colorPageEncoding =
        FlashColorPageEncoding.liteU16,
    FlashMediumProfileKind storageMedium = FlashMediumProfileKind.serialFlash,
    int? keyAdcChannel,
  }) {
    return DeviceProfile._(
      name: name,
      serial: serial,
      display: display,
      storage: storage,
      capabilities: capabilities,
      colorPageEncoding: colorPageEncoding,
      storageMedium: storageMedium,
      keyAdcChannel: keyAdcChannel,
    );
  }

  /// Known profile name: smallest device, one key, ADC-driven input.
  static const String nameMini = 'MINI';

  /// Known profile name: no key.
  static const String nameLite = 'LITE';

  /// Known profile name: no key, NAND storage, 24-bit color page addressing.
  static const String namePro = 'PRO';

  /// Profile name for a device whose model is unknown but that handshakes and
  /// reports its capabilities.
  static const String nameGeneric = 'GENERIC';

  /// Legacy vendor demo serial parameters: 19200 baud, no explicit flow
  /// control, 8N1.
  static const SerialSettings legacySerial = SerialSettings.eightN1(
    baudRate: 19200,
  );

  /// LITE V1.2 / PRO V1.0 serial parameters: 921600 baud, RTS/CTS, 8N1.
  static const SerialSettings highSpeedSerial = SerialSettings.eightN1(
    baudRate: 921600,
    rtscts: true,
  );

  /// Serial Flash geometry from the development guide (P25D80): 1 MiB, 256-byte
  /// pages, 4096 pages, page-granular erase.
  static const StorageGeometry p25d80Geometry = StorageGeometry(
    type: 'serial-flash',
    capacityBytes: 1024 * 1024,
    pageSizeBytes: 256,
    eraseGranularityBytes: 256,
    addressableByteRange: RangeValues(0, 1024 * 1024),
  );

  /// MINI: smallest panel, one key.
  ///
  /// MINI panel is 160×80 when the self-description table is absent.
  /// A present `Lcd_X` / `Lcd_Y` still wins.
  static final DeviceProfile mini = DeviceProfile._(
    name: nameMini,
    serial: legacySerial,
    display: const DisplayGeometry(width: 160, height: 80, orientations: [0]),
    storage: p25d80Geometry,
    capabilities: const DeviceCapabilities(
      externalFlash: true,
      keyInput: true,
      rgbLed: true,
    ),
    colorPageEncoding: FlashColorPageEncoding.liteU16,
    keyAdcChannel: 9,
  );

  /// LITE V1.2: color panel, serial Flash, no key.
  static final DeviceProfile lite = DeviceProfile._(
    name: nameLite,
    serial: highSpeedSerial,
    display: const DisplayGeometry(width: 160, height: 80, orientations: [0, 1]),
    storage: p25d80Geometry,
    capabilities: const DeviceCapabilities(
      externalFlash: true,
      rgbLed: true,
    ),
    colorPageEncoding: FlashColorPageEncoding.liteU16,
  );

  /// PRO V1.0: 2.8 inch panel, NAND, backlight, 24-bit color page addressing.
  ///
  /// The demo initializes `LCD_X`/`LCD_Y` to 160×80 and then overwrites them
  /// from `Read_MSN_Data`. Paint paths use 320×172. A missing table must fall
  /// back to that size, not the 160×80 placeholder.
  static final DeviceProfile pro = DeviceProfile._(
    name: namePro,
    serial: highSpeedSerial,
    display: const DisplayGeometry(width: 320, height: 172, orientations: [0, 1]),
    storage: p25d80Geometry,
    capabilities: const DeviceCapabilities(
      externalFlash: true,
      nand: true,
      rgbLed: true,
      backlight: true,
      monoRam: true,
      storageChipErase: true,
      storageNandBlockErase: true,
    ),
    colorPageEncoding: FlashColorPageEncoding.proMidLowHigh,
    storageMedium: FlashMediumProfileKind.nand,
  );

  /// Unknown model that already proved it speaks MSU2.
  static final DeviceProfile generic = DeviceProfile._(
    name: nameGeneric,
    serial: legacySerial,
    display: const DisplayGeometry(width: 160, height: 80),
    capabilities: const DeviceCapabilities(),
    colorPageEncoding: FlashColorPageEncoding.liteU16,
  );

  /// All profiles shipped by this library, in probing order.
  static final List<DeviceProfile> known = List.unmodifiable([
    mini,
    lite,
    pro,
  ]);

  /// Profile name (`MINI`, `LITE`, `PRO`, `GENERIC`, or a custom name).
  final String name;

  /// Serial parameters to request while opening.
  final SerialSettings serial;

  /// Panel description, possibly a placeholder pending self-description.
  final DisplayGeometry display;

  /// External storage geometry, when the device has one.
  final StorageGeometry? storage;

  final DeviceCapabilities capabilities;

  /// Flash color page address encoding this profile prefers.
  final FlashColorPageEncoding colorPageEncoding;

  final FlashMediumProfileKind storageMedium;

  /// ADC channel carrying the key, for profiles with [DeviceCapabilities.keyInput].
  final int? keyAdcChannel;

  /// Looks a profile up by name (case-insensitive), returning null if unknown.
  static DeviceProfile? byName(String name) {
    final upper = name.toUpperCase();
    for (final profile in known) {
      if (profile.name == upper) return profile;
    }
    return null;
  }

  /// Never null: a custom profile for a name this library does not ship.
  static DeviceProfile byNameOrDefault(String? name) =>
      name == null ? generic : (byName(name) ?? generic);

  /// Whether this is the generic fallback.
  bool get isGeneric => name == nameGeneric;

  DeviceProfile withOverrides({
    SerialSettings? serial,
    DisplayGeometry? display,
    StorageGeometry? storage,
    DeviceCapabilities? capabilities,
  }) {
    return DeviceProfile._(
      name: name,
      serial: serial ?? this.serial,
      display: display ?? this.display,
      storage: storage ?? this.storage,
      capabilities: capabilities ?? this.capabilities,
      colorPageEncoding: colorPageEncoding,
      storageMedium: storageMedium,
      keyAdcChannel: keyAdcChannel,
    );
  }

  @override
  String toString() => 'DeviceProfile($name, $display)';
}

/// Which storage-medium encoding family a profile belongs to.
///
/// Needed because `03 04` means different things on LITE (serial Flash start
/// page) and PRO (NAND block). See `spec/msu2-wire-protocol.md`.
enum FlashMediumProfileKind { serialFlash, nand }
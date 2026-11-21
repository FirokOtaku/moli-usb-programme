// Device capabilities.
//
// `spec/architecture.md`: business code must branch on capabilities, not on a
// model name. Every flag is an independent, device-reported (or explicitly
// configured) fact.

/// Independent capability flags for one device.
///
/// Defaults describe the minimum every known MSU2 device provides: a color
/// display and the ability to read a Flash byte. Everything else must be
/// enabled by the caller or by the self-description table.
final class DeviceCapabilities {
  const DeviceCapabilities({
    this.display = true,
    this.externalFlash = false,
    this.nand = false,
    this.rgbLed = false,
    this.backlight = false,
    this.keyInput = false,
    this.monoRam = false,
    this.asciiFont = false,
    this.gb2312Font = false,
    this.sfrU8Read = true,
    this.sfrU8Write = true,
    this.sfrU16Read = true,
    this.sfrU16Write = false,
    this.adc = true,
    this.rle = true,
    this.storageChipErase = false,
    this.storageNandBlockErase = false,
  });

  // Display group.
  final bool display;

  /// Serial Flash (P25D80) present.
  final bool externalFlash;

  /// NAND medium present (PRO).
  final bool nand;

  /// Addressable RGB LEDs.
  final bool rgbLed;

  /// PWM backlight control (action `0x12`).
  final bool backlight;

  /// At least one physical key reachable through ADC sampling (MINI).
  final bool keyInput;

  /// Monochrome RAM composition actions.
  final bool monoRam;

  final bool asciiFont;

  final bool gb2312Font;

  // SFR access.
  final bool sfrU8Read;
  final bool sfrU8Write;
  final bool sfrU16Read;

  /// u16 SFR write is NOT public until real hardware confirms opcode `0xA0`
  /// (`spec/msu2-wire-protocol.md`, SFR table).
  final bool sfrU16Write;

  /// ADC channel reads (channel 9 carries the MINI key on the vendor demo).
  final bool adc;

  /// Two-color RLE pixel blocks (command `0x09`).
  final bool rle;

  final bool storageChipErase;
  final bool storageNandBlockErase;

  /// Returns a copy with selected flags replaced.
  DeviceCapabilities copyWith({
    bool? display,
    bool? externalFlash,
    bool? nand,
    bool? rgbLed,
    bool? backlight,
    bool? keyInput,
    bool? monoRam,
    bool? asciiFont,
    bool? gb2312Font,
    bool? sfrU8Read,
    bool? sfrU8Write,
    bool? sfrU16Read,
    bool? sfrU16Write,
    bool? adc,
    bool? rle,
    bool? storageChipErase,
    bool? storageNandBlockErase,
  }) {
    return DeviceCapabilities(
      display: display ?? this.display,
      externalFlash: externalFlash ?? this.externalFlash,
      nand: nand ?? this.nand,
      rgbLed: rgbLed ?? this.rgbLed,
      backlight: backlight ?? this.backlight,
      keyInput: keyInput ?? this.keyInput,
      monoRam: monoRam ?? this.monoRam,
      asciiFont: asciiFont ?? this.asciiFont,
      gb2312Font: gb2312Font ?? this.gb2312Font,
      sfrU8Read: sfrU8Read ?? this.sfrU8Read,
      sfrU8Write: sfrU8Write ?? this.sfrU8Write,
      sfrU16Read: sfrU16Read ?? this.sfrU16Read,
      sfrU16Write: sfrU16Write ?? this.sfrU16Write,
      adc: adc ?? this.adc,
      rle: rle ?? this.rle,
      storageChipErase: storageChipErase ?? this.storageChipErase,
      storageNandBlockErase:
          storageNandBlockErase ?? this.storageNandBlockErase,
    );
  }

  @override
  String toString() {
    final on = <String>[
      if (display) 'display',
      if (externalFlash) 'externalFlash',
      if (nand) 'nand',
      if (rgbLed) 'rgbLed',
      if (backlight) 'backlight',
      if (keyInput) 'keyInput',
      if (monoRam) 'monoRam',
      if (asciiFont) 'asciiFont',
      if (gb2312Font) 'gb2312Font',
      if (adc) 'adc',
      if (rle) 'rle',
      if (storageChipErase) 'chipErase',
      if (storageNandBlockErase) 'nandBlockErase',
    ];
    return 'DeviceCapabilities(${on.join(', ')})';
  }
}
// Transport-layer serial parameters.
//
// The protocol library never decides these from a model name alone. A profile
// is either configured explicitly by the caller or supplied by a
// [TransportPortDescriptor.confirmedSettings] whose device identity (USB VID/PID
// or firmware banner) was already confirmed by the platform adapter.

/// Line parity setting.
enum SerialParity { none, even, odd }

/// Stop-bit setting.
enum SerialStopBits { one, two }

/// The bytes read/write parameters a transport should apply when opening.
class SerialSettings {
  const SerialSettings({
    required this.baudRate,
    this.dataBits = 8,
    this.parity = SerialParity.none,
    this.stopBits = SerialStopBits.one,
    this.rtscts = false,
    this.xonxoff = false,
  });

  /// 8 data bits, no parity, 1 stop bit: the framing assumed by pyserial
  /// defaults in the vendor demos.
  const SerialSettings.eightN1({
    required this.baudRate,
    this.rtscts = false,
    this.xonxoff = false,
  })  : dataBits = 8,
        parity = SerialParity.none,
        stopBits = SerialStopBits.one;

  final int baudRate;
  final int dataBits;
  final SerialParity parity;
  final SerialStopBits stopBits;

  /// Hardware flow control (RTS/CTS).
  final bool rtscts;

  /// Software flow control (XON/XOFF).
  final bool xonxoff;

  @override
  bool operator ==(Object other) =>
      other is SerialSettings &&
      other.baudRate == baudRate &&
      other.dataBits == dataBits &&
      other.parity == parity &&
      other.stopBits == stopBits &&
      other.rtscts == rtscts &&
      other.xonxoff == xonxoff;

  @override
  int get hashCode =>
      Object.hash(baudRate, dataBits, parity, stopBits, rtscts, xonxoff);

  @override
  String toString() =>
      'SerialSettings($baudRate baud, $dataBits${switch (parity) {
        SerialParity.none => 'N',
        SerialParity.even => 'E',
        SerialParity.odd => 'O',
      }}${stopBits == SerialStopBits.one ? 1 : 2}, rtscts=$rtscts, xonxoff=$xonxoff)';
}

/// A named, reusable set of serial parameters.
///
/// The names describe the vendor firmware generation observed in the demos, not
/// the product name. `spec/msu2-wire-protocol.md` lists exactly two profiles and
/// `spec/verification.md` still requires them to be confirmed per firmware.
class SerialPortProfile {
  const SerialPortProfile._(this.name, this.settings);

  /// Builds an additional profile (for example from an experimentally verified
  /// firmware build).
  factory SerialPortProfile.custom(String name, SerialSettings settings) =>
      SerialPortProfile._(name, settings);

  /// Legacy demo firmware: 19200 baud, no explicit flow control.
  static const SerialPortProfile legacyDemo = SerialPortProfile._(
    'legacy-demo',
    SerialSettings.eightN1(baudRate: 19200),
  );

  /// LITE V1.2 / PRO V1.0 demo firmware: 921600 baud with RTS/CTS.
  static const SerialPortProfile liteV12AndProV10 = SerialPortProfile._(
    'lite-v1.2/pro-v1.0',
    SerialSettings.eightN1(baudRate: 921600, rtscts: true),
  );

  /// Profile applied when nothing more specific is known. Matching the legacy
  /// demo is the least surprising default and is what the vendor's original
  /// tool used.
  static const SerialPortProfile fallback = legacyDemo;

  final String name;
  final SerialSettings settings;

  @override
  String toString() => 'SerialPortProfile($name, $settings)';
}

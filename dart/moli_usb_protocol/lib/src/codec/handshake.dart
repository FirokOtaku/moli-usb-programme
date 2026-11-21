// Handshake encoder/decoder (`\0MSNxx` banner, `\0MSNCN` challenge/response).
//
// spec/msu2-wire-protocol.md:
//   - the device broadcasts `00 4D 53 4E 30 31`-like banners roughly every
//     100 ms: `\0MSN` + two ASCII decimal digits,
//   - the host sends `\0MSNCN` and the device answers with the same 6 bytes,
//   - the two digits are parsed as a decimal protocol version.

import 'dart:typed_data';

import '../errors.dart';
import 'binary.dart';

/// The 4-byte prefix shared by banner and handshake frames.
const List<int> kMsnPrefix = [0x00, 0x4D, 0x53, 0x4E]; // \0MSN

/// Host challenge: `\0MSNCN`.
final Uint8List kHandshakeChallenge = Uint8List.fromList([...kMsnPrefix, 0x43, 0x4E]);

/// Protocol version reported in the banner digits.
final class HandshakeResult {
  const HandshakeResult({required this.protocolVersion});

  /// Decimal value of the two ASCII digits. Whether this equals the firmware
  /// version is unconfirmed (P0 verification item).
  final int protocolVersion;
}

/// Encodes and matches handshake frames.
final class HandshakeCodec {
  const HandshakeCodec();

  /// Matches a device banner frame: `\0MSN` + two ASCII digits.
  ///
  /// Returns the parsed protocol version, or null when [bytes] is not a
  /// banner. Partial frames also return null; the caller keeps collecting.
  int? tryParseBanner(Uint8List bytes) {
    if (bytes.length < 6) return null;
    if (!_hasMsnPrefix(bytes)) return null;
    final d0 = bytes[4];
    final d1 = bytes[5];
    if (d0 < 0x30 || d0 > 0x39 || d1 < 0x30 || d1 > 0x39) return null;
    return (d0 - 0x30) * 10 + (d1 - 0x30);
  }

  /// Matches the `\0MSNCN` response frame expected after the challenge.
  bool isConfirmResponse(Uint8List bytes) {
    if (bytes.length < 6) return false;
    for (var i = 0; i < 6; i++) {
      if (bytes[i] != kHandshakeChallenge[i]) return false;
    }
    return true;
  }

  /// Strict banner parse used by the session after the handshake confirmed:
  /// at that point a malformed banner is a protocol error, not noise.
  HandshakeResult parseBannerStrict(Uint8List bytes, {String? deviceId}) {
    final version = tryParseBanner(bytes);
    if (version == null) {
      throw MoliException.protocolMismatch(
        'banner ${toHex(bytes)} is not a MSNxx banner',
        operation: 'parseBannerStrict',
        deviceId: deviceId,
      );
    }
    return HandshakeResult(protocolVersion: version);
  }

  bool _hasMsnPrefix(Uint8List bytes) {
    for (var i = 0; i < 4; i++) {
      if (bytes[i] != kMsnPrefix[i]) return false;
    }
    return true;
  }
}

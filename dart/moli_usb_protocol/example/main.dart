// Minimal, pure-encoding entry point.
//
// Runs the same on the Dart VM (Windows) and in the browser: no transport is
// opened, nothing touches dart:io, dart:html or dart:js_interop. The point is
// to prove the library's codec + geometry core compiles and runs in both
// environments:
//
//   dart run example/main.dart
//   dart compile js  example/main.dart -o build/example.js
//   dart compile exe example/main.dart -o build/example.exe

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

void main() {
  final display = const DisplayCodec();
  final sfr = const SfrCodec();
  final flash = const FlashCodec();
  final scratch = const ScratchCodec();
  final adc = const AdcCodec();

  final vectors = <String, Frame>{
    'setXy(1, 2)': display.setXy(1, 2),
    'setSize(160, 80)': display.setSize(160, 80),
    'setColors(red, black)':
        display.setColors(Rgb565Colors.red, Rgb565Colors.black),
    'fillCurrent(green)': display.fillCurrent(Rgb565Colors.green),
    'readAdc(9)': adc.readChannel(9),
    'readSfrU8(0x0123)': sfr.readU8(0x0123),
    'writeSfrU8(0x0123, 0x7F)': sfr.writeU8(0x0123, 0x7F),
    'readFlashByte(0x012345)': flash.readByte(0x012345),
    'scratch.write(2, DEADBEEF)':
        scratch.write(2, const [0xDE, 0xAD, 0xBE, 0xEF]),
    'RLE red x3 + black x2': RleCodec.encode([
      Rgb565Colors.red.value,
      Rgb565Colors.red.value,
      Rgb565Colors.red.value,
      Rgb565Colors.black.value,
      Rgb565Colors.black.value,
    ]).single,
  };

  print('moli_usb_protocol example: normative encoding vectors');
  for (final entry in vectors.entries) {
    print('  ${entry.key.padRight(28)} ${toHex(entry.value.bytes)}');
  }

  // Pure domain-side planning: no device, no transport.
  const lite = DisplayGeometry(width: 160, height: 80);
  final pixels = List<Rgb565Color>.filled(
    lite.width * lite.height,
    Rgb565Colors.green,
  );
  print(
    'LITE frame: ${lite.width}x${lite.height} -> '
    '${pixels.toWireBytes().length} bytes of RGB565',
  );
  print(
    'Storage: ${DeviceProfile.p25d80Geometry.pageCount} pages of '
    '${DeviceProfile.p25d80Geometry.pageSizeBytes} bytes '
    '(${DeviceProfile.p25d80Geometry.capacityBytes ~/ 1024} KiB)',
  );
  print('Profiles: ${DeviceProfile.known.map((p) => p.name).join(', ')}');
}
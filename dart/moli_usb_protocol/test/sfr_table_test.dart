import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:space_firok_moli_usb_protocol/src/codec/sfr.dart';
import 'package:test/test.dart';

void main() {
  const parser = SfrTableParser();

  test('family 后的 NUL 不是地址，Lcd_X 地址 0 不会截断 Lcd_Y', () {
    final table = Uint8List(256);
    var offset = 0;
    offset = _put(table, offset, 'Lcd_X', 0x21, [0x00]);
    offset = _put(table, offset, 'Lcd_Y', 0x21, [0x01]);
    _put(table, offset, 'Flash_Info', 0x21, [0x02]);

    final entries = parser.parse(table);
    expect(entries.map((e) => e.name), ['Lcd_X', 'Lcd_Y', 'Flash_Info']);
    expect(entries[0].family, SfrEntryFamily.u16Register);
    expect(entries[0].address, 0x00);
    expect(entries[1].address, 0x01);
    expect(entries[2].address, 0x02);
  });

  test('全零表没有 Lcd 条目，不能当成 0x0 尺寸', () {
    expect(parser.parse(Uint8List(256)), isEmpty);
  });

  test('family 缺少结尾 NUL 时报畸形，不把结束符当成地址', () {
    final table = Uint8List(256);
    var offset = _putCString(table, 0, 'Lcd_X');
    offset = _putCString(table, offset, ' ');
    table[offset++] = 0x21;
    table[offset] = 0x05;
    expect(
      () => parser.parse(table),
      throwsA(isA<MoliException>().having(
        (e) => e.kind,
        'kind',
        MoliErrorKind.malformedResponse,
      )),
    );
  });
}

int _put(Uint8List table, int offset, String name, int family, List<int> data) {
  offset = _putCString(table, offset, name);
  offset = _putCString(table, offset, ' ');
  table[offset++] = family;
  table[offset++] = 0;
  for (final byte in data) {
    table[offset++] = byte;
  }
  return offset;
}

int _putCString(Uint8List table, int offset, String text) {
  for (final unit in text.codeUnits) {
    table[offset++] = unit;
  }
  table[offset++] = 0;
  return offset;
}

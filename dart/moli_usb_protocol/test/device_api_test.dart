// Facade behaviour the host relies on: staging LEDs must not apply, and a
// link probe is one existing SFR read.

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

import 'fake_transport.dart';

void main() {
  test('MINI orientation uses 1 for 180 degrees and 0 for normal', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.mini);
    await session.connect();
    addTearDown(session.disconnect);
    final api = MoliDeviceApi(session);
    expect(api.display.orientations, [0, 1]);

    await api.setOrientation(1);
    await api.setOrientation(0);
    await expectLater(
      api.setOrientation(2),
      throwsA(isA<MoliException>().having(
        (error) => error.kind,
        'kind',
        MoliErrorKind.unsupportedCapability,
      )),
    );
    expect(
      port.writes.map(toHex).where((hex) => hex.startsWith('02030A')),
      ['02030A010000', '02030A000000'],
    );
  });

  test('stageLed does not apply, and applyLeds applies once', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.lite);
    await session.connect();
    addTearDown(session.disconnect);
    final api = MoliDeviceApi(session);
    final before = port.writes.length;

    await api.stageLed(1, red: 2, green: 3, blue: 4);

    expect(port.writes.length, before + 1);
    expect(toHex(port.writes.last), '020501020304');
    expect(
      port.writes.skip(before).where(_isApplyLeds),
      isEmpty,
    );

    await api.applyLeds();

    expect(port.writes.length, before + 2);
    expect(port.writes.skip(before).where(_isApplyLeds), hasLength(1));
    expect(toHex(port.writes.last), '02030C000000');
  });

  test('setLed still stages and applies immediately', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.lite);
    await session.connect();
    addTearDown(session.disconnect);
    final api = MoliDeviceApi(session);
    final before = port.writes.length;

    await api.setLed(0, red: 9, green: 8, blue: 7);

    expect(port.writes.length, before + 2);
    expect(toHex(port.writes[before]), '020500090807');
    expect(toHex(port.writes[before + 1]), '02030C000000');
  });

  test('160x80 画面是一次压缩写出，不是 39006 字节未压缩载荷', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.lite);
    await session.connect();
    addTearDown(session.disconnect);
    final api = MoliDeviceApi(session);
    final pixels = List<Rgb565Color>.filled(160 * 80, Rgb565Colors.green);
    final raw = PixelStreamEncoder(const DisplayCodec(), const ScratchCodec())
        .encodeStream(pixels.toWireBytes());
    var uncompressed = 0;
    for (final frame in raw) {
      if (frame.command == 0x02 && frame.bytes[2] == DisplayAction.beginStream) {
        continue;
      }
      uncompressed += frame.bytes.length;
    }
    expect(uncompressed, 39006);

    final before = port.writes.length;
    await api.drawPixelsRle(
      const Rect(0, 0, 160, 80),
      pixels,
      maxFrames: 0,
    );
    final bulk = port.writes.skip(before).where((write) => write.length > 6);
    expect(bulk, hasLength(1));
    final payload = bulk.single;
    expect(payload.length, lessThan(uncompressed ~/ 5));
    expect(payload.length % 6, 0);
    for (var offset = 0; offset < payload.length; offset += 6) {
      expect(payload[offset], 0x09);
    }
  });

  test('MINI compact mirror uses scratch default word and no terminal action', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.mini);
    await session.connect();
    addTearDown(session.disconnect);
    final pixels = List<Rgb565Color>.filled(128, Rgb565Colors.green);
    pixels[0] = Rgb565Colors.black;
    await MoliDeviceApi(session).drawPixelsCompact(const Rect(0, 0, 16, 8), pixels);
    expect(toHex(port.writes.last), '020407E007E00400000007E0020308010000');
  });

  test('banner plus challenge alone do not establish PRO command readiness', () async {
    final session = MoliSession(port: FakeMsu2Port(autoRespond: false),
      profile: DeviceProfile.pro,
      options: const SessionOptions(operationTimeout: Duration(milliseconds: 35)));
    addTearDown(session.disconnect);
    await expectLater(session.connect(), throwsA(isA<MoliException>()));
    expect(session.isConnected, isFalse);
  });

  test('profile 波特率与官方 serial.Serial 一致', () {
    expect(DeviceProfile.mini.serial.baudRate, 19200);
    expect(DeviceProfile.mini.serial.rtscts, isFalse);
    expect(DeviceProfile.lite.serial.baudRate, 921600);
    expect(DeviceProfile.lite.serial.rtscts, isTrue);
    expect(DeviceProfile.pro.serial.baudRate, 921600);
    expect(DeviceProfile.pro.serial.rtscts, isTrue);
  });

  test('probeLink is one self-description SFR read', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.lite);
    await session.connect();
    addTearDown(session.disconnect);
    final api = MoliDeviceApi(session);
    final before = port.writes.length;

    await api.probeLink();

    expect(port.writes.length, before + 1);
    // 00 30 00 01 00 00 — read u8 at the table the session already reads.
    expect(toHex(port.writes.last), '003000010000');
    expect(port.writes.last[0], isNot(0x02));
  });

  test('discardPending does not write the idle SFR read', () async {
    final port = FakeMsu2Port();
    final session = MoliSession(port: port, profile: DeviceProfile.mini);
    await session.connect();
    addTearDown(session.disconnect);
    final api = MoliDeviceApi(session);
    final before = port.writes.length;

    await api.discardPending();

    expect(port.writes.length, before);
    expect(port.writes.map(toHex), isNot(contains('003000010000')));
  });
}

bool _isApplyLeds(List<int> frame) {
  return frame.length == 6 &&
      frame[0] == 0x02 &&
      frame[1] == 0x03 &&
      frame[2] == DisplayAction.applyLeds;
}

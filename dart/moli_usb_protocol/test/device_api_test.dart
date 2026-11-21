// Facade behaviour the host relies on: staging LEDs must not apply, and a
// link probe is one existing SFR read.

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

import 'fake_transport.dart';

void main() {
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
}

bool _isApplyLeds(List<int> frame) {
  return frame.length == 6 &&
      frame[0] == 0x02 &&
      frame[1] == 0x03 &&
      frame[2] == DisplayAction.applyLeds;
}

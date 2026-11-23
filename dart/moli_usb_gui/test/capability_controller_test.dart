import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' show Size;

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' show MaterialApp, SizedBox;
import 'package:space_firok_moli_usb_gui/src/connection/capability_controller.dart';
import 'package:space_firok_moli_usb_gui/src/connection/connection_controller.dart';
import 'package:space_firok_moli_usb_gui/src/ui/host_page.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

import 'fake_transport.dart';

const _fastOptions = SessionOptions(
  readSelfDescription: true,
  handshakeTimeout: Duration(milliseconds: 400),
  operationTimeout: Duration(milliseconds: 200),
  confirmTimeout: Duration(milliseconds: 200),
  writeTimeout: Duration(milliseconds: 200),
  readSliceTimeout: Duration(milliseconds: 50),
);

ConnectionController _connection(FakeHostProvider provider) {
  return ConnectionController(
    providers: [provider],
    sessionOptions: _fastOptions,
    pollWhileDisconnected: false,
  );
}

Future<CapabilityController> _connected({
  required ConnectionController connection,
  required FakeHostProvider provider,
  required String profileName,
  bool Function()? contentSending,
  Duration probeInterval = Duration.zero,
}) async {
  final controls = CapabilityController(
    connection: connection,
    contentSending: contentSending ?? () => false,
    probeInterval: probeInterval,
  );
  await connection.refresh();
  connection.selectCandidate(provider.portKey);
  connection.selectProfile(profileName);
  await connection.connect();
  return controls;
}

List<Uint8List> _added(FakeHostPort port, int before) {
  return port.writes.sublist(before);
}

void main() {
  test('orientation reasons name an empty or single list', () {
    expect(orientationUnavailableReason(const []), '当前设备没有可切换的显示方向');
    expect(orientationUnavailableReason(const [0]), '当前设备只有一个显示方向');
    expect(orientationUnavailableReason(const [0, 1, 2, 3]), isNull);
  });

  test('MINI can switch to 180 degrees and back to normal', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameMini,
    );
    addTearDown(controls.dispose);
    expect(connection.supportedOrientations, [0, 1]);

    final port = provider.lastPort!;
    await controls.setOrientation(1);
    expect(controls.selectedOrientation, 1);
    expect(port.writes.last, [0x02, 0x03, 0x0A, 0x01, 0x00, 0x00]);
    await controls.setOrientation(0);
    expect(controls.selectedOrientation, 0);
    expect(port.writes.last, [0x02, 0x03, 0x0A, 0x00, 0x00, 0x00]);
  });

  test('unsupported capability does not attempt a device call', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameGeneric,
    );
    addTearDown(controls.dispose);

    expect(connection.capabilities?.backlight, isFalse);
    expect(connection.capabilities?.rgbLed, isFalse);
    expect(connection.hasKeyInput, isFalse);
    expect(connection.supportedOrientations, [0]);
    final calls = connection.submittedActions;
    final writes = provider.lastPort!.writes.length;

    controls.setBacklight(400);
    await controls.applyRgb();
    await Future<void>.delayed(Duration.zero);

    expect(connection.submittedActions, calls);
    expect(provider.lastPort!.writes.length, writes);

    await connection.disconnect();
    expect(connection.phase, ConnectionPhase.disconnected);
    expect(connection.dropdownsEnabled, isTrue);
    expect(connection.capabilities, isNull);
    expect(connection.supportedOrientations, isEmpty);
  });

  test('busy queue skips the probe', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameGeneric,
    );
    addTearDown(controls.dispose);

    final started = Completer<void>();
    final gate = Completer<void>();
    final held = connection.submit((_) async {
      started.complete();
      await gate.future;
    });
    await started.future;
    expect(connection.queueBusy, isTrue);
    final calls = connection.submittedActions;
    final writes = provider.lastPort!.writes.length;

    await controls.probeIfIdle();
    gate.complete();
    await held;
    await Future<void>.delayed(const Duration(milliseconds: 30));

    expect(connection.submittedActions, calls);
    expect(provider.lastPort!.writes.length, writes);
    expect(connection.phase, ConnectionPhase.connected);
  });

  test('content send in flight skips the drain, idle drain writes nothing', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    var sending = true;
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameGeneric,
      contentSending: () => sending,
    );
    addTearDown(controls.dispose);
    final port = provider.lastPort!;
    final blockedWrites = port.writes.length;
    final blockedCalls = connection.submittedActions;

    await controls.probeIfIdle();

    expect(connection.submittedActions, blockedCalls);
    expect(port.writes.length, blockedWrites);

    sending = false;
    await controls.probeIfIdle();

    expect(connection.submittedActions, blockedCalls + 1);
    expect(port.writes.length, blockedWrites);
    expect(
      _added(port, blockedWrites).map(toHex),
      isNot(contains('003000010000')),
    );
  });

  test('RGB apply stages every lamp and applies once', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameLite,
    );
    addTearDown(controls.dispose);
    final port = provider.lastPort!;
    final before = port.writes.length;
    controls.setLampCountText('2');
    controls.setLedColor(red: 9, green: 8, blue: 7);

    await controls.applyRgb();

    final added = _added(port, before);
    expect(added, hasLength(3));
    expect(toHex(added[0]), '020500090807');
    expect(toHex(added[1]), '020501090807');
    expect(toHex(added[2]), '02030C000000');
  });

  test('backlight drag sends the latest value, not every step', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.namePro,
    );
    addTearDown(controls.dispose);
    final port = provider.lastPort!;
    final before = port.writes.length;

    controls.setBacklight(10);
    controls.setBacklight(20);
    controls.setBacklight(30);
    controls.setBacklight(40);
    await Future<void>.delayed(const Duration(milliseconds: 50));

    final backlight = _added(port, before).where((frame) {
      return frame.length >= 3 &&
          frame[0] == 0x02 &&
          frame[1] == 0x03 &&
          frame[2] == 0x12;
    }).toList();
    expect(backlight, hasLength(2));
    expect(toHex(backlight[0]), '020312000A00');
    expect(toHex(backlight[1]), '020312002800');
    expect(connection.submittedActions, 2);
  });

  test('key subscription is cancelled on disconnect', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameMini,
    );
    addTearDown(controls.dispose);

    expect(connection.hasKeyInput, isTrue);
    expect(controls.listeningForKeys, isTrue);
    expect(controls.keyLabel, isNull);

    await connection.disconnect();

    expect(controls.listeningForKeys, isFalse);
    expect(controls.keyLabel, isNull);
    expect(connection.dropdownsEnabled, isTrue);
  });

  test('idle drain does not write, and a dead port still closes', () async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    final controls = await _connected(
      connection: connection,
      provider: provider,
      profileName: DeviceProfile.nameGeneric,
    );
    addTearDown(controls.dispose);
    final before = provider.lastPort!.writes.length;
    provider.lastPort!.failReadAvailable = const MoliException.disconnected(
      'fake link lost',
      operation: 'fake.read',
    );

    await controls.probeIfIdle();

    expect(provider.lastPort!.writes.length, before);
    expect(connection.phase, ConnectionPhase.disconnected);
    expect(connection.dropdownsEnabled, isTrue);
    expect(connection.controlsLocked, isFalse);
    expect(provider.lastPort!.isClosed, isTrue);
    expect(
      connection.log.any((line) => line.contains('Disconnected')),
      isTrue,
    );
  });

  testWidgets('connected page shows capability reasons and hides ADC', (
    tester,
  ) async {
    final provider = FakeHostProvider();
    final connection = _connection(provider);
    addTearDown(connection.dispose);
    tester.view.physicalSize = const Size(1280, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(MaterialApp(home: HostPage(controller: connection)));
    await tester.pump();
    expect(find.text('设备控制'), findsOneWidget);
    expect(find.text('当前设备不支持背光'), findsNothing);

    // Widget tests freeze real timers. Connect outside that zone.
    await tester.runAsync(() async {
      await connection.refresh();
      connection.selectCandidate(provider.portKey);
      connection.selectProfile(DeviceProfile.nameGeneric);
      await connection.connect();
    });
    await tester.pump();

    expect(find.text('当前设备不支持背光'), findsOneWidget);
    expect(find.text('当前设备不支持 RGB 灯'), findsOneWidget);
    expect(find.text('当前设备没有按键'), findsOneWidget);
    expect(find.textContaining('ADC'), findsNothing);
    expect(find.textContaining('阈值'), findsNothing);
    expect(find.textContaining('通道'), findsNothing);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}

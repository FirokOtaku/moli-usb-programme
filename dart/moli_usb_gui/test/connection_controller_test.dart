import 'package:flutter_test/flutter_test.dart';
import 'package:space_firok_moli_usb_gui/src/connection/connection_controller.dart';
import 'package:space_firok_moli_usb_gui/src/connection/panel_geometry.dart';
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

ConnectionController _controller(FakeHostProvider provider) {
  return ConnectionController(
    providers: [provider],
    sessionOptions: _fastOptions,
    pollWhileDisconnected: false,
  );
}

void main() {
  test('panel size uses the report, and zero falls back to the profile', () {
    final reported = resolvePanelGeometry(
      reportedWidth: 100,
      reportedHeight: 40,
      profile: DeviceProfile.lite,
    );
    expect(reported.usedProfileFallback, isFalse);
    expect(reported.width, 100);
    expect(reported.height, 40);

    final fallback = resolvePanelGeometry(
      reportedWidth: 0,
      reportedHeight: DeviceProfile.pro.display.height,
      profile: DeviceProfile.pro,
    );
    expect(fallback.usedProfileFallback, isTrue);
    expect(fallback.width, DeviceProfile.pro.display.width);
    expect(fallback.height, DeviceProfile.pro.display.height);
    expect(fallback.warning, isNotNull);
  });

  test('connect locks controls and disconnect unlocks them', () async {
    final provider = FakeHostProvider();
    final controller = _controller(provider);
    addTearDown(controller.dispose);

    await controller.refresh();
    controller.selectCandidate(provider.portKey);
    controller.selectProfile(DeviceProfile.nameLite);
    expect(controller.dropdownsEnabled, isTrue);
    expect(controller.connectEnabled, isTrue);

    await controller.connect();

    expect(controller.phase, ConnectionPhase.connected);
    expect(controller.controlsLocked, isTrue);
    expect(controller.dropdownsEnabled, isFalse);
    expect(controller.connectEnabled, isFalse);
    expect(controller.disconnectEnabled, isTrue);
    expect(controller.panelWidth, DeviceProfile.lite.display.width);
    expect(controller.panelHeight, DeviceProfile.lite.display.height);
    expect(controller.statusText, contains('已连接'));
    expect(provider.openCount, 1);
    expect(provider.lastSettings, DeviceProfile.lite.serial);
    expect(controller.centerText, '已连接，尚未发送画面');

    await controller.disconnect();

    expect(controller.phase, ConnectionPhase.disconnected);
    expect(controller.controlsLocked, isFalse);
    expect(controller.dropdownsEnabled, isTrue);
    expect(controller.disconnectEnabled, isFalse);
    expect(controller.statusText, '已断开');
    expect(provider.lastPort?.isClosed, isTrue);
  });

  test('handshake failure returns to Disconnected and unlocks', () async {
    final provider = FakeHostProvider(
      failRead: const MoliException.handshakeFailed(
        'no banner',
        operation: 'read',
      ),
    );
    final controller = _controller(provider);
    addTearDown(controller.dispose);

    await controller.refresh();
    controller.selectCandidate(provider.portKey);
    await controller.connect();

    expect(controller.phase, ConnectionPhase.disconnected);
    expect(controller.dropdownsEnabled, isTrue);
    expect(controller.connectEnabled, isTrue);
    expect(controller.statusText, contains('HandshakeFailed'));
    expect(
      controller.log.any((line) => line.contains('HandshakeFailed')),
      isTrue,
    );
    expect(provider.lastPort?.isClosed, isTrue);
    expect(provider.openCount, 1);
  });

  test('second connect is refused while connected', () async {
    final provider = FakeHostProvider();
    final controller = _controller(provider);
    addTearDown(controller.dispose);

    await controller.refresh();
    controller.selectCandidate(provider.portKey);
    await controller.connect();
    expect(controller.phase, ConnectionPhase.connected);

    await expectLater(
      controller.connect(),
      throwsA(isA<StateError>()),
    );

    expect(controller.phase, ConnectionPhase.connected);
    expect(controller.controlsLocked, isTrue);
    expect(provider.openCount, 1);

    var ran = false;
    await controller.submit((device) async {
      ran = true;
      expect(identical(device, controller.connectedDevice), isTrue);
    });
    expect(ran, isTrue);
    expect(provider.openCount, 1);
  });

  test('disconnect during connect cancels and closes the port', () async {
    final provider = FakeHostProvider(holdReads: true);
    final controller = _controller(provider);
    addTearDown(controller.dispose);

    await controller.refresh();
    controller.selectCandidate(provider.portKey);
    final connecting = controller.connect();
    await Future<void>.delayed(const Duration(milliseconds: 30));
    expect(controller.phase, ConnectionPhase.connecting);
    expect(controller.dropdownsEnabled, isFalse);
    expect(controller.disconnectEnabled, isTrue);

    await controller.disconnect();
    await connecting;

    expect(controller.phase, ConnectionPhase.disconnected);
    expect(controller.dropdownsEnabled, isTrue);
    expect(provider.lastPort?.isClosed, isTrue);
    expect(
      controller.log.any((line) => line.contains('Cancelled')),
      isTrue,
    );
  });
}

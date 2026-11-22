import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:space_firok_moli_usb_gui/src/connection/connection_controller.dart';
import 'package:space_firok_moli_usb_gui/src/ui/host_page.dart';

import 'fake_transport.dart';

void main() {
  testWidgets('log resizes, shows latest first and clears from a right click', (
    tester,
  ) async {
    if (kIsWeb) await BrowserContextMenu.disableContextMenu();
    final provider = FakeHostProvider();
    final controller = ConnectionController(
      providers: [provider],
      pollWhileDisconnected: false,
    );
    addTearDown(controller.dispose);
    await tester.runAsync(() async {
      await controller.refresh();
      controller.selectCandidate(provider.portKey);
      await controller.connect();
    });
    expect(controller.phase, ConnectionPhase.connected);

    for (var i = 0; i < 201; i++) {
      controller.appendLog('entry $i');
    }
    final emittedLines = controller.log;
    expect(emittedLines, hasLength(200));
    expect(emittedLines.first, matches(r'^\d{2}:\d{2}:\d{2} entry 1$'));
    expect(emittedLines.last, matches(r'^\d{2}:\d{2}:\d{2} entry 200$'));

    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.binding.setSurfaceSize(const Size(1180, 720));
    await tester.pumpWidget(
      MaterialApp(home: HostPage(controller: controller)),
    );
    await tester.pumpAndSettle();
    final field = find.widgetWithText(TextField, '日志');
    TextField log() => tester.widget<TextField>(field);
    expect(log().readOnly, isTrue);
    expect(log().controller!.text, emittedLines.reversed.join('\n'));
    expect(log().scrollController!.offset, 0);
    final largeHeight = tester.getSize(field).height;
    expect(largeHeight, greaterThan(180));

    await tester.binding.setSurfaceSize(const Size(900, 560));
    await tester.pumpAndSettle();
    expect(tester.getSize(field).height, lessThan(largeHeight));
    expect(log().controller!.text, emittedLines.reversed.join('\n'));

    final status = controller.statusText;
    await tester.tapAt(
      tester.getCenter(field),
      kind: PointerDeviceKind.mouse,
      buttons: kSecondaryMouseButton,
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('清空'));
    await tester.pumpAndSettle();
    expect(controller.log, isEmpty);
    expect(log().controller!.text, isEmpty);
    expect(controller.phase, ConnectionPhase.connected);
    expect(controller.statusText, status);
    expect(provider.lastPort!.isClosed, isFalse);

    controller.appendLog('after clear');
    await tester.pumpAndSettle();
    expect(log().controller!.text, matches(r'^\d{2}:\d{2}:\d{2} after clear$'));
  }, variant: const TargetPlatformVariant({TargetPlatform.windows}));
}

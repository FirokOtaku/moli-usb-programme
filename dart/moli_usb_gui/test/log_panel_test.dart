import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:space_firok_moli_usb_gui/src/ui/log_panel.dart';

void main() {
  testWidgets('right click offers clear and keeps the selection actions', (
    tester,
  ) async {
    if (kIsWeb) {
      await tester.runAsync(BrowserContextMenu.disableContextMenu);
      addTearDown(BrowserContextMenu.enableContextMenu);
      expect(BrowserContextMenu.enabled, isFalse);
    }
    var lines = ['10:20:30 old', '10:20:31 new'];
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) => SizedBox(
              height: 220,
              child: LogPanel(
                lines: lines,
                onClear: () => setState(() => lines = []),
              ),
            ),
          ),
        ),
      ),
    );
    final field = find.byType(TextField);
    final text = tester.widget<TextField>(field).controller!;
    expect(text.text, '10:20:31 new\n10:20:30 old');
    await tester.tapAt(
      tester.getCenter(field),
      kind: PointerDeviceKind.mouse,
      buttons: kSecondaryMouseButton,
    );
    await tester.pumpAndSettle();
    expect(find.text('Select all'), findsOneWidget);
    await tester.tap(find.text('清空'));
    await tester.pumpAndSettle();
    expect(text.text, isEmpty);
  }, variant: const TargetPlatformVariant({TargetPlatform.windows}));
}

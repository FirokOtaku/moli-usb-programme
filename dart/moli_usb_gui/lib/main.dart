import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'src/ui/host_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Flutter's selection menus (including the log's clear action) replace the
  // browser menu. Native platforms already use Flutter's menus.
  if (kIsWeb) await BrowserContextMenu.disableContextMenu();
  runApp(const Msu2HostApp());
}

class Msu2HostApp extends StatelessWidget {
  const Msu2HostApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MSU2 屏幕助手',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      home: const HostPage(),
    );
  }
}

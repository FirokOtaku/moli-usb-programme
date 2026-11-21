import 'package:material_ui/material_ui.dart';

import 'src/ui/host_page.dart';

void main() {
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

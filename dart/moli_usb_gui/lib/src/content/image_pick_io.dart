import 'dart:io';

import 'picked_image.dart';

/// Windows file dialog in a child process, so the UI isolate keeps pumping
/// device I/O while the dialog is open.
Future<PickedImage?> pickImageFileImpl() async {
  final stamp = DateTime.now().microsecondsSinceEpoch;
  final out = File(
    '${Directory.systemTemp.path}${Platform.pathSeparator}moli-pick-$stamp.txt',
  );
  final script = '''
Add-Type -AssemblyName System.Windows.Forms
\$owner = New-Object System.Windows.Forms.Form
\$owner.TopMost = \$true
\$owner.ShowInTaskbar = \$false
\$owner.Opacity = 0
\$owner.Show()
\$dialog = New-Object System.Windows.Forms.OpenFileDialog
\$dialog.Filter = "图片 (jpg/png/bmp)|*.jpg;*.jpeg;*.png;*.bmp"
\$dialog.Title = "选择图片"
\$dialog.CheckFileExists = \$true
\$dialog.Multiselect = \$false
\$result = \$dialog.ShowDialog(\$owner)
\$owner.Close()
if (\$result -eq [System.Windows.Forms.DialogResult]::OK) {
  [System.IO.File]::WriteAllText(${_psQuote(out.path)}, \$dialog.FileName, (New-Object System.Text.UTF8Encoding \$false))
}
''';
  try {
    final result = await Process.run(
      'powershell.exe',
      <String>[
        '-NoProfile',
        '-STA',
        '-WindowStyle',
        'Hidden',
        '-Command',
        script,
      ],
    );
    if (result.exitCode != 0) {
      final detail = result.stderr.toString().trim();
      throw StateError(detail.isEmpty ? '无法打开文件选择窗口' : detail);
    }
    if (!await out.exists()) return null;
    final path = (await out.readAsString()).trim();
    if (path.isEmpty) return null;
    final file = File(path);
    if (!await file.exists()) {
      throw StateError('找不到所选文件');
    }
    final name = path.split(Platform.pathSeparator).last;
    return PickedImage(name: name, bytes: await file.readAsBytes());
  } finally {
    if (await out.exists()) {
      await out.delete();
    }
  }
}

String _psQuote(String value) => "'${value.replaceAll("'", "''")}'";

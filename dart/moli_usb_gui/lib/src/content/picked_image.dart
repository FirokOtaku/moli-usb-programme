import 'dart:typed_data';

/// Bytes the user picked. [name] is the file name, not a full path on web.
final class PickedImage {
  const PickedImage({required this.name, required this.bytes});

  final String name;
  final Uint8List bytes;
}

/// jpg / jpeg / png / bmp. A name with no extension is left for the decoder.
bool isSupportedImageName(String name) {
  final slash = name.lastIndexOf(RegExp(r'[\\/]'));
  final base = slash < 0 ? name : name.substring(slash + 1);
  final dot = base.lastIndexOf('.');
  if (dot < 0 || dot == base.length - 1) return true;
  final ext = base.substring(dot + 1).toLowerCase();
  return ext == 'jpg' || ext == 'jpeg' || ext == 'png' || ext == 'bmp';
}

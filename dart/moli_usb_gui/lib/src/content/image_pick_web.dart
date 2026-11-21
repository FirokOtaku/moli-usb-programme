import 'dart:async';
import 'dart:js_interop';
import 'dart:typed_data';

import 'package:web/web.dart' as web;

import 'picked_image.dart';

/// Browser file input. [click] runs before the first await so the caller's
/// click handler still counts as a user gesture.
Future<PickedImage?> pickImageFileImpl() {
  final input = web.HTMLInputElement()
    ..type = 'file'
    ..accept = '.jpg,.jpeg,.png,.bmp,image/jpeg,image/png,image/bmp';
  final completer = Completer<PickedImage?>();
  input.onchange = ((web.Event _) {
    unawaited(_readSelected(input, completer));
  }).toJS;
  input.oncancel = ((web.Event _) {
    if (!completer.isCompleted) completer.complete(null);
  }).toJS;
  web.document.body?.append(input);
  input.click();
  return completer.future.whenComplete(input.remove);
}

Future<void> _readSelected(
  web.HTMLInputElement input,
  Completer<PickedImage?> completer,
) async {
  if (completer.isCompleted) return;
  final files = input.files;
  if (files == null || files.length == 0) {
    completer.complete(null);
    return;
  }
  final file = files.item(0);
  if (file == null) {
    completer.complete(null);
    return;
  }
  try {
    final buffer = await file.arrayBuffer().toDart;
    final bytes = Uint8List.fromList(buffer.toDart.asUint8List());
    completer.complete(PickedImage(name: file.name, bytes: bytes));
  } catch (error, stackTrace) {
    if (!completer.isCompleted) {
      completer.completeError(error, stackTrace);
    }
  }
}

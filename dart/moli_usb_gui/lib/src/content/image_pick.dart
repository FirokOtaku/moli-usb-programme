import 'image_pick_stub.dart'
    if (dart.library.io) 'image_pick_io.dart'
    if (dart.library.js_interop) 'image_pick_web.dart';
import 'picked_image.dart';

export 'picked_image.dart';

/// Opens the platform file dialog. Must be called from a click handler with
/// no prior await, so the web implementation still has the user gesture.
Future<PickedImage?> pickImageFile() => pickImageFileImpl();

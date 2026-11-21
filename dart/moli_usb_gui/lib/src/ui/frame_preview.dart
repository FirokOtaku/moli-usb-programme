import 'dart:async';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';

import '../content/composed_frame.dart';

/// Last composed frame, scaled up to the space the page gives it.
class FramePreview extends StatefulWidget {
  const FramePreview({super.key, required this.frame});

  final ComposedFrame? frame;

  @override
  State<FramePreview> createState() => _FramePreviewState();
}

class _FramePreviewState extends State<FramePreview> {
  ui.Image? _image;
  int _ticket = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_rebuild());
  }

  @override
  void didUpdateWidget(FramePreview oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(widget.frame, oldWidget.frame)) {
      unawaited(_rebuild());
    }
  }

  @override
  void dispose() {
    _ticket++;
    _image?.dispose();
    super.dispose();
  }

  Future<void> _rebuild() async {
    final frame = widget.frame;
    final ticket = ++_ticket;
    if (frame == null || frame.pixels.isEmpty || frame.width <= 0) {
      if (!mounted || ticket != _ticket) return;
      _image?.dispose();
      _image = null;
      setState(() {});
      return;
    }
    final rgba = Uint8List(frame.width * frame.height * 4);
    final count = frame.pixels.length < frame.width * frame.height
        ? frame.pixels.length
        : frame.width * frame.height;
    for (var i = 0; i < count; i++) {
      final color = frame.pixels[i];
      rgba[i * 4] = (color.red * 255) ~/ 31;
      rgba[i * 4 + 1] = (color.green * 255) ~/ 63;
      rgba[i * 4 + 2] = (color.blue * 255) ~/ 31;
      rgba[i * 4 + 3] = 255;
    }
    final buffer = await ui.ImmutableBuffer.fromUint8List(rgba);
    final descriptor = ui.ImageDescriptor.raw(
      buffer,
      width: frame.width,
      height: frame.height,
      pixelFormat: ui.PixelFormat.rgba8888,
    );
    final codec = await descriptor.instantiateCodec();
    final next = await codec.getNextFrame();
    codec.dispose();
    descriptor.dispose();
    buffer.dispose();
    if (!mounted || ticket != _ticket) {
      next.image.dispose();
      return;
    }
    _image?.dispose();
    _image = next.image;
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final image = _image;
    final frame = widget.frame;
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border.all(color: theme.dividerColor),
        color: theme.colorScheme.surfaceContainerHighest,
      ),
      child: image == null || frame == null
          ? const Center(child: Text('尚无画面'))
          : Padding(
              padding: const EdgeInsets.all(8),
              child: FittedBox(
                fit: BoxFit.contain,
                child: RawImage(
                  image: image,
                  width: frame.width.toDouble(),
                  height: frame.height.toDouble(),
                  filterQuality: FilterQuality.none,
                ),
              ),
            ),
    );
  }
}

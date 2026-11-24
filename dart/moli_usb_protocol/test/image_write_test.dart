import 'dart:async';
import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

import 'fake_transport.dart';

RgbaImage _redImage() => RgbaImage(1, 1, Uint8List.fromList([255, 0, 0, 255]));

Future<MoliDeviceApi> _open(FakeMsu2Port port, DeviceProfile profile) async {
  final session = MoliSession(port: port, profile: profile);
  await session.connect();
  addTearDown(session.disconnect);
  return MoliDeviceApi(session);
}

void main() {
  test('color helpers pack RGB and ignore packed alpha', () {
    expect(Rgb565Color.fromRgb(0xff0000), Rgb565Colors.red);
    expect(Rgb565Color.fromArgb(0x8000ff00), Rgb565Colors.green);
    expect(Rgb565Color.fromRgb888(-1, 0, 256), Rgb565Colors.blue);
  });

  test('RGBA image is immutable and supports alpha and fit padding', () {
    final rgba = Uint8List.fromList([255, 0, 0, 128, 0, 0, 255, 0]);
    final image = RgbaImage(2, 1, rgba);
    rgba.fillRange(0, rgba.length, 0);
    expect(() => image.rgba[0] = 0, throwsUnsupportedError);
    expect(() => RgbaImage(2, 1, Uint8List(4)), throwsArgumentError);

    expect(
      image.toRgb565(
        2,
        2,
        options: const ImageWriteOptions(
          alphaBackground: Rgb565Colors.blue,
          paddingColor: Rgb565Colors.white,
        ),
      ),
      [
        Rgb565Color.fromRgb888(128, 0, 127),
        Rgb565Colors.blue,
        Rgb565Colors.white,
        Rgb565Colors.white,
      ],
    );
    expect(
      image.toRgb565(
        2,
        1,
        options: const ImageWriteOptions(alphaMode: ImageAlphaMode.ignore),
      ),
      [Rgb565Colors.red, Rgb565Colors.blue],
    );
  });

  test('fill crops centrally on either axis and stretch covers the target', () {
    final rgb = Uint8List.fromList([
      255,
      0,
      0,
      255,
      0,
      255,
      0,
      255,
      0,
      0,
      255,
      255,
    ]);
    const fill = ImageWriteOptions(scaleMode: ImageScaleMode.fill);
    expect(RgbaImage(3, 1, rgb).toRgb565(1, 1, options: fill), [
      Rgb565Colors.green,
    ]);
    expect(RgbaImage(1, 3, rgb).toRgb565(1, 1, options: fill), [
      Rgb565Colors.green,
    ]);
    expect(
      RgbaImage(3, 1, rgb).toRgb565(
        3,
        2,
        options: const ImageWriteOptions(scaleMode: ImageScaleMode.stretch),
      ),
      [
        Rgb565Colors.red,
        Rgb565Colors.green,
        Rgb565Colors.blue,
        Rgb565Colors.red,
        Rgb565Colors.green,
        Rgb565Colors.blue,
      ],
    );
  });

  test('image write uses the requested region and wire encoding', () async {
    final port = FakeMsu2Port();
    final device = await _open(port, DeviceProfile.mini);
    device.setImageResolutionWarningEnabled(false);
    const region = Rect(2, 3, 16, 8);
    for (final (compression, prefix) in [
      (ImageCompression.none, '0400F800F800'),
      (ImageCompression.rle, '09FFF800F800'),
      (ImageCompression.profileCompact, '0204F800F800'),
    ]) {
      final before = port.writes.length;
      await device.writeImage(
        _redImage(),
        rect: region,
        options: ImageWriteOptions(
          scaleMode: ImageScaleMode.stretch,
          compression: compression,
        ),
      );
      final writes = port.writes.skip(before).map(toHex).toList();
      expect(writes.take(3), ['020000020003', '020100100008', '020307000000']);
      expect(writes.last, startsWith(prefix));
    }
  });

  test('warns only once for resize, with a session level override', () async {
    final port = FakeMsu2Port();
    final device = await _open(port, DeviceProfile.lite);
    final logs = <String>[];
    await runZoned(
      () async {
        device.setImageResolutionWarningEnabled(false);
        await device.writeImage(_redImage());
        expect(logs, isEmpty);
        device.setImageResolutionWarningEnabled(true);
        await device.writeImage(_redImage(), rect: const Rect(3, 4, 1, 1));
        expect(logs, isEmpty);
        await device.writeImage(_redImage());
        await MoliDeviceApi(device.session).writeImage(_redImage());
      },
      zoneSpecification: ZoneSpecification(
        print: (_, _, _, message) => logs.add(message),
      ),
    );
    expect(logs, [contains('resized to 160x80')]);
  });

  for (final policy in ImageOverflowPolicy.values) {
    test(
      '$policy applies only to waiting frames and close drains accepted work',
      () async {
        final port = FakeMsu2Port();
        final device = await _open(port, DeviceProfile.lite);
        device.setImageResolutionWarningEnabled(false);
        final started = Completer<void>();
        final release = Completer<void>();
        addTearDown(() {
          if (!release.isCompleted) release.complete();
        });
        port.beforeBulkWrite = () {
          if (!started.isCompleted) started.complete();
          return release.future;
        };
        final queue = device.createImageWriteQueue(
          capacity: 1,
          overflowPolicy: policy,
        );
        final active = queue.submit(_redImage());
        await started.future;
        final waiting = queue.submit(_redImage());
        final newest = queue.submit(_redImage());

        switch (policy) {
          case ImageOverflowPolicy.dropNewest:
            expect(await newest, ImageSendResult.dropped);
          case ImageOverflowPolicy.dropOldest:
            expect(await waiting, ImageSendResult.dropped);
          case ImageOverflowPolicy.block:
            final third = expectLater(newest, throwsStateError);
            unawaited(device.close());
            await third;
        }

        final closed = device.close();
        expect(port.isClosed, isFalse);
        await expectLater(queue.submit(_redImage()), throwsStateError);
        release.complete();
        expect(await active, ImageSendResult.sent);
        expect(
          await (policy == ImageOverflowPolicy.dropOldest ? newest : waiting),
          ImageSendResult.sent,
        );
        await closed;
        expect(port.isClosed, isTrue);
        expect(port.writes.where((write) => write.length > 6), hasLength(2));
      },
    );
  }
}

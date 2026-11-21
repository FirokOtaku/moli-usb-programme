import 'package:flutter_test/flutter_test.dart';
import 'package:space_firok_moli_usb_gui/src/content/image_fit.dart';
import 'package:space_firok_moli_usb_gui/src/content/link_loss.dart';
import 'package:space_firok_moli_usb_gui/src/content/rgb565_convert.dart';
import 'package:space_firok_moli_usb_gui/src/content/scroll_window.dart';
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

void main() {
  group('contain / cover', () {
    test('wide image is letterboxed by contain and cropped by cover', () {
      const imageWidth = 200;
      const imageHeight = 100;
      const panelWidth = 100;
      const panelHeight = 100;

      final contain = placeImage(
        imageWidth: imageWidth,
        imageHeight: imageHeight,
        panelWidth: panelWidth,
        panelHeight: panelHeight,
        fit: ImageFit.contain,
      );
      expect(contain.sourceX, 0);
      expect(contain.sourceY, 0);
      expect(contain.sourceWidth, imageWidth);
      expect(contain.sourceHeight, imageHeight);
      expect(contain.destWidth, panelWidth);
      expect(contain.destHeight, 50);
      expect(contain.destX, 0);
      expect(contain.destY, 25);
      expect(contain.destX + contain.destWidth, lessThanOrEqualTo(panelWidth));
      expect(
        contain.destY + contain.destHeight,
        lessThanOrEqualTo(panelHeight),
      );

      final cover = placeImage(
        imageWidth: imageWidth,
        imageHeight: imageHeight,
        panelWidth: panelWidth,
        panelHeight: panelHeight,
        fit: ImageFit.cover,
      );
      expect(cover.destX, 0);
      expect(cover.destY, 0);
      expect(cover.destWidth, panelWidth);
      expect(cover.destHeight, panelHeight);
      expect(cover.sourceWidth, 100);
      expect(cover.sourceHeight, imageHeight);
      expect(cover.sourceX, 50);
      expect(cover.sourceY, 0);
    });

    test('matching aspect fills the panel without a crop', () {
      final contain = placeImage(
        imageWidth: 80,
        imageHeight: 40,
        panelWidth: 40,
        panelHeight: 20,
        fit: ImageFit.contain,
      );
      expect(contain.destX, 0);
      expect(contain.destY, 0);
      expect(contain.destWidth, 40);
      expect(contain.destHeight, 20);

      final cover = placeImage(
        imageWidth: 80,
        imageHeight: 40,
        panelWidth: 40,
        panelHeight: 20,
        fit: ImageFit.cover,
      );
      expect(cover.sourceX, 0);
      expect(cover.sourceY, 0);
      expect(cover.sourceWidth, 80);
      expect(cover.sourceHeight, 40);
      expect(cover.destWidth, 40);
      expect(cover.destHeight, 20);
    });

    test('tall image on a wide panel letterboxes on the sides', () {
      final contain = placeImage(
        imageWidth: 50,
        imageHeight: 50,
        panelWidth: 100,
        panelHeight: 40,
        fit: ImageFit.contain,
      );
      expect(contain.destWidth, 40);
      expect(contain.destHeight, 40);
      expect(contain.destX, 30);
      expect(contain.destY, 0);

      final cover = placeImage(
        imageWidth: 50,
        imageHeight: 50,
        panelWidth: 100,
        panelHeight: 40,
        fit: ImageFit.cover,
      );
      expect(cover.destWidth, 100);
      expect(cover.destHeight, 40);
      expect(cover.sourceWidth, 50);
      expect(cover.sourceHeight, 20);
      expect(cover.sourceX, 0);
      expect(cover.sourceY, 15);
    });
  });

  group('RGB565', () {
    test('8-bit channels pack to the protocol constants', () {
      expect(rgb888ToRgb565(255, 255, 255), 0xFFFF);
      expect(rgb888ToRgb565(0, 0, 0), 0x0000);
      expect(rgb888ToRgb565(255, 0, 0), Rgb565Colors.red.value);
      expect(rgb888ToRgb565(0, 255, 0), Rgb565Colors.green.value);
      expect(rgb888ToRgb565(0, 0, 255), Rgb565Colors.blue.value);
      expect(rgb888ToRgb565(256, -1, 8), rgb888ToRgb565(255, 0, 8));
    });

    test('two-color threshold keeps text frames binary', () {
      const foreground = 0xFFFF;
      const background = 0x0000;
      expect(
        twoColorRgb565(
          alpha: 127,
          foreground: foreground,
          background: background,
        ),
        background,
      );
      expect(
        twoColorRgb565(
          alpha: 128,
          foreground: foreground,
          background: background,
        ),
        foreground,
      );
      final buffer = rgbaBufferToTwoColor(
        const [10, 20, 30, 0, 10, 20, 30, 200],
        foreground: foreground,
        background: background,
      );
      expect(buffer, [background, foreground]);
    });
  });

  group('scroll window', () {
    test('crop shifts and wraps without a device', () {
      final strip = <int>[1, 2, 3, 4, 5, 6];
      expect(
        cropScrollWindow(
          strip: strip,
          stripWidth: 6,
          height: 1,
          windowWidth: 3,
          offset: 0,
        ),
        [1, 2, 3],
      );
      expect(
        cropScrollWindow(
          strip: strip,
          stripWidth: 6,
          height: 1,
          windowWidth: 3,
          offset: 2,
        ),
        [3, 4, 5],
      );
      expect(
        cropScrollWindow(
          strip: strip,
          stripWidth: 6,
          height: 1,
          windowWidth: 3,
          offset: 4,
        ),
        [5, 6, 1],
      );
      expect(
        cropScrollWindow(
          strip: strip,
          stripWidth: 6,
          height: 1,
          windowWidth: 3,
          offset: 6,
        ),
        [1, 2, 3],
      );
    });

    test('each row is cropped independently', () {
      final strip = <int>[1, 2, 3, 4, 5, 6, 7, 8];
      expect(
        cropScrollWindow(
          strip: strip,
          stripWidth: 4,
          height: 2,
          windowWidth: 2,
          offset: 3,
        ),
        [4, 1, 8, 5],
      );
    });
  });

  group('link loss', () {
    test('closes on disconnected, and on timeout only when the port is gone', () {
      const gone = MoliException.disconnected('link lost');
      const timedOut = MoliException.timeout('slow');
      const desync = MoliException.protocolDesynchronized('echo');
      const cancelled = MoliException.cancelled('mode switch');

      expect(
        shouldCloseForLinkLoss(
          gone,
          portClosed: false,
          sessionConnected: true,
        ),
        isTrue,
      );
      expect(
        shouldCloseForLinkLoss(
          timedOut,
          portClosed: false,
          sessionConnected: true,
        ),
        isFalse,
      );
      expect(
        shouldCloseForLinkLoss(
          timedOut,
          portClosed: true,
          sessionConnected: true,
        ),
        isTrue,
      );
      expect(
        shouldCloseForLinkLoss(
          desync,
          portClosed: false,
          sessionConnected: false,
        ),
        isTrue,
      );
      expect(
        shouldCloseForLinkLoss(
          cancelled,
          portClosed: true,
          sessionConnected: false,
        ),
        isFalse,
      );
    });
  });
}

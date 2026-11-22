// Display command codec (command 0x02) and RGB LED staging (subcommand 0x05),
// display actions (subcommand 0x03) and the pixel streaming pipeline.

import 'dart:typed_data';

import '../errors.dart';
import 'binary.dart';
import 'frame.dart';

/// Display subcommands (`0x02 xx ...`).
abstract final class DisplaySub {
  static const int setXy = 0x00;
  static const int setSize = 0x01;
  static const int setColors = 0x02;
  static const int action = 0x03;
  static const int setLed = 0x05;
}

/// Display actions (subcommand 0x03, data0 = action).
abstract final class DisplayAction {
  static const int showFlashColor = 0x00;
  static const int showFlashMono = 0x01;
  static const int drawAscii = 0x02;
  static const int drawGb2312 = 0x03;
  static const int blendMonoBackground = 0x04;
  static const int blendAsciiBackground = 0x05;
  static const int blendGb2312Background = 0x06;
  static const int beginStream = 0x07;
  static const int commitStaged = 0x08;
  static const int waitTransferDone = 0x09;
  static const int setOrientation = 0x0A;
  static const int fillCurrent = 0x0B;
  static const int applyLeds = 0x0C;
  static const int initMonoRam = 0x0D;
  static const int loadMonoRamNoMask = 0x0E;
  static const int loadMonoRamMasked = 0x0F;
  static const int showRam = 0x10;
  static const int showRamWithBackground = 0x11;
  static const int setBacklight = 0x12;
}

/// How the `showFlashColor` (action 0x00) page address is laid out.
///
/// The legacy/LITE encoding uses a 16-bit page and a zeroed third byte; PRO
/// uses a 24-bit page split as middle, low, high bytes (NOT u24_be). The
/// profile selects the encoding — see `spec/msu2-wire-protocol.md`.
enum FlashColorPageEncoding {
  /// 16-bit page at data1..2, data3 = 0 (legacy demo / LITE).
  liteU16,

  /// 24-bit page as middle, low, high (PRO).
  proMidLowHigh,
}

/// Encodes display frames.
final class DisplayCodec {
  const DisplayCodec();

  /// `02 00 x_hi x_lo y_hi y_lo` — set the top-left corner for later ops.
  Frame setXy(int x, int y) {
    _checkU16('x', x);
    _checkU16('y', y);
    return Frame(command: 0x02, subcommand: 0x00, data0: x >> 8, data1: x & 0xFF,
        data2: y >> 8, data3: y & 0xFF);
  }

  /// `02 01 w_hi w_lo h_hi h_lo` — set the size for later ops.
  Frame setSize(int width, int height) {
    _checkU16('width', width);
    _checkU16('height', height);
    return Frame(command: 0x02, subcommand: 0x01, data0: width >> 8,
        data1: width & 0xFF, data2: height >> 8, data3: height & 0xFF);
  }

  /// `02 02 fg_hi fg_lo bg_hi bg_lo` — set foreground/background colors.
  Frame setColors(Rgb565Color foreground, Rgb565Color background) {
    return Frame(
      command: 0x02,
      subcommand: 0x02,
      data0: foreground.value >> 8,
      data1: foreground.value & 0xFF,
      data2: background.value >> 8,
      data3: background.value & 0xFF,
    );
  }

  /// `02 03 action a b c` — run a display action.
  Frame actionFrame(int action, int arg0, int arg1, int arg2) {
    _checkU8('action', action);
    return Frame(command: 0x02, subcommand: 0x03, data0: action,
        data1: arg0, data2: arg1, data3: arg2);
  }

  /// Action 0x0A — set orientation (0/1/2/3).
  Frame setOrientation(int orientation) {
    _checkU8('orientation', orientation);
    return actionFrame(DisplayAction.setOrientation, orientation, 0, 0);
  }

  /// Action 0x0B — fill the current region with [color].
  Frame fillCurrent(Rgb565Color color) =>
      actionFrame(DisplayAction.fillCurrent, color.value >> 8, color.value & 0xFF, 0);

  /// Action 0x12 — set the backlight PWM (0..1000, PRO only in vendor sources).
  Frame setBacklight(int pwm) {
    if (pwm < 0 || pwm > 1000) {
      throw MoliException.invalidArgument(
        'backlight pwm must be 0..1000 (got $pwm)',
        operation: 'setBacklight',
      );
    }
    return actionFrame(
        DisplayAction.setBacklight, pwm >> 8, pwm & 0xFF, 0);
  }

  /// Action 0x00 — display a color image stored in flash at [page].
  ///
  /// [encoding] is selected by the device profile; it must not be guessed by
  /// the library from a model name.
  Frame showFlashColor(int page, FlashColorPageEncoding encoding) {
    _checkU24('page', page);
    switch (encoding) {
      case FlashColorPageEncoding.liteU16:
        _checkU16('page (lite encoding)', page);
        return actionFrame(DisplayAction.showFlashColor,
            page >> 8, page & 0xFF, 0);
      case FlashColorPageEncoding.proMidLowHigh:
        // PRO splits a 24-bit page as middle, low, high — NOT u24_be.
        final mid = (page >> 8) & 0xFF;
        final low = page & 0xFF;
        final high = (page >> 16) & 0xFF;
        return actionFrame(DisplayAction.showFlashColor, mid, low, high);
    }
  }

  /// Action 0x01 — display a monochrome image at [page] (page_high, page_low,
  /// 0 per the LITE layout documented in the spec).
  Frame showFlashMono(int page) {
    _checkU16('page', page);
    return actionFrame(
        DisplayAction.showFlashMono, page >> 8, page & 0xFF, 0);
  }

  /// Action 0x07 — apply the configured region and start pixel streaming.
  Frame beginStream() =>
      actionFrame(DisplayAction.beginStream, 0, 0, 0);

  /// Action 0x08 — commit [size] bytes of staged pixel data.
  Frame commitStaged(int size) {
    _checkU16('size', size);
    return actionFrame(DisplayAction.commitStaged, size >> 8, size & 0xFF, 0);
  }

  /// Action 0x09 — wait for the transfer to complete (vendor semantics).
  Frame waitTransferDone() =>
      actionFrame(DisplayAction.waitTransferDone, 0, 0, 0);

  /// `02 05 led_index r g b` — stage one RGB LED value.
  Frame setLed(int ledIndex, int red, int green, int blue) {
    _checkU8('ledIndex', ledIndex);
    _checkU8('red', red);
    _checkU8('green', green);
    _checkU8('blue', blue);
    return Frame(command: 0x02, subcommand: 0x05, data0: ledIndex, data1: red,
        data2: green, data3: blue);
  }

  /// Action 0x0C — apply staged LED values.
  Frame applyLeds() => actionFrame(DisplayAction.applyLeds, 0, 0, 0);

  static void _checkU8(String name, int value) {
    if (value < 0 || value > 0xFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..255 (got $value)',
        operation: 'DisplayCodec',
      );
    }
  }

  static void _checkU16(String name, int value) {
    if (value < 0 || value > 0xFFFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..65535 (got $value)',
        operation: 'DisplayCodec',
      );
    }
  }

  static void _checkU24(String name, int value) {
    if (value < 0 || value > 0xFFFFFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..16777215 (got $value)',
        operation: 'DisplayCodec',
      );
    }
  }
}

/// Builds the `0x04` staging frames that fill the device's 256-byte scratch
/// buffer (64 frames x 4 data bytes each).
final class ScratchCodec {
  const ScratchCodec();

  /// Scratch buffer size in bytes.
  static const int scratchSize = 256;

  /// Number of data bytes per staging frame.
  static const int bytesPerFrame = 4;

  /// Total number of staging frames needed for a full scratch buffer.
  static const int framesPerScratch = scratchSize ~/ bytesPerFrame; // 64

  /// `04 offset d0 d1 d2 d3` with offset 0..63.
  Frame write(int offset, List<int> data) {
    if (offset < 0 || offset > 63) {
      throw MoliException.invalidArgument(
        'scratch offset must be 0..63 (got $offset)',
        operation: 'ScratchCodec.write',
      );
    }
    if (data.length != 4) {
      throw MoliException.invalidArgument(
        'scratch write needs exactly 4 data bytes (got ${data.length})',
        operation: 'ScratchCodec.write',
      );
    }
    for (var i = 0; i < 4; i++) {
      final b = data[i];
      if (b < 0 || b > 0xFF) {
        throw MoliException.invalidArgument(
          'scratch data byte $i out of range: $b',
          operation: 'ScratchCodec.write',
        );
      }
    }
    return Frame(
        command: 0x04, subcommand: offset, data0: data[0], data1: data[1],
        data2: data[2], data3: data[3]);
  }

  /// Splits [chunk] (up to 256 bytes) into consecutive staging frames.
  List<Frame> writeChunk(List<int> chunk) {
    if (chunk.isEmpty) {
      throw MoliException.invalidArgument(
        'chunk must not be empty',
        operation: 'ScratchCodec.writeChunk',
      );
    }
    if (chunk.length > scratchSize) {
      throw MoliException.invalidArgument(
        'chunk exceeds scratch size (${chunk.length} > $scratchSize)',
        operation: 'ScratchCodec.writeChunk',
      );
    }
    final frames = <Frame>[];
    var offset = 0;
    while (offset < chunk.length) {
      final slice = chunk.sublist(offset, (offset + 4).clamp(0, chunk.length));
      while (slice.length < 4) {
        slice.add(0);
      }
      frames.add(write(offset ~/ 4, slice));
      offset += 4;
    }
    return frames;
  }
}

/// Encodes the streaming write of RGB565 pixel data through the scratch
/// buffer: setXy/setSize (caller), action 0x07, staged 0x04 frames, action
/// 0x08 with the batch length, action 0x09 at the end.
///
/// The caller writes `0x04` / `0x08` / `0x09` without reading a response.
/// `Write_LCD_Screen_fast1` does the same in one `ser.write`.
final class PixelStreamEncoder {
  PixelStreamEncoder(this._display, this._scratch);

  final DisplayCodec _display;
  final ScratchCodec _scratch;

  /// MINI V1.0 mirror format: default scratch word, exceptions, then commit.
  List<Frame> encodeMiniCompactStream(List<int> pixelBytes) {
    if (pixelBytes.isEmpty || pixelBytes.length.isOdd) {
      throw const MoliException.invalidArgument('invalid RGB565 byte count');
    }
    final frames = <Frame>[_display.beginStream()];
    for (var offset = 0; offset < pixelBytes.length; offset += 256) {
      final length = (pixelBytes.length - offset).clamp(0, 256);
      final bytes = List<int>.filled(256, 0xff);
      bytes.setRange(0, length, pixelBytes, offset);
      // A hex key keeps the four-byte value independent of Web bitwise width.
      final words = List.generate(64, (i) => bytes.sublist(i * 4, i * 4 + 4));
      if (length == 256) {
        final counts = <String, int>{};
        final keys = words.map((bytes) => bytes.join(',')).toList();
        for (final key in keys) {
          counts.update(key, (n) => n + 1, ifAbsent: () => 1);
        }
        final common = counts.keys.reduce((a, b) => counts[a]! >= counts[b]! ? a : b);
        final value = words[keys.indexOf(common)];
        frames.add(Frame(command: 2, subcommand: 4, data0: value[0],
            data1: value[1], data2: value[2], data3: value[3]));
        for (var i = 0; i < 64; i++) {
          if (keys[i] != common) frames.add(_scratch.write(i, words[i]));
        }
      } else {
        frames.addAll(_scratch.writeChunk(bytes));
      }
      frames.add(_display.commitStaged(length));
    }
    return frames;
  }

  /// Builds all frames for one streaming write of [pixels] (RGB565, big-endian
  /// per pixel, high byte first).
  ///
  /// The caller is responsible for setXy/setSize and for the panel bounds
  /// check; this encoder only produces the wire frames.
  List<Frame> encodeStream(List<int> pixelBytes) {
    if (pixelBytes.isEmpty || pixelBytes.length.isOdd) {
      throw MoliException.invalidArgument(
        'pixel data must contain a whole number of RGB565 pixels',
        operation: 'PixelStreamEncoder.encodeStream',
      );
    }
    final frames = <Frame>[];
    frames.add(_display.beginStream());
    var offset = 0;
    while (offset < pixelBytes.length) {
      final end = (offset + ScratchCodec.scratchSize)
          .clamp(0, pixelBytes.length);
      final length = end - offset;
      final chunk = List<int>.filled(ScratchCodec.scratchSize, 0xff)
        ..setRange(0, length, pixelBytes, offset);
      frames.addAll(_scratch.writeChunk(chunk));
      frames.add(_display.commitStaged(length));
      offset = end;
    }
    frames.add(_display.waitTransferDone());
    return frames;
  }
}

/// Converts RGB565 pixel values into wire bytes (high byte first).
extension Rgb565Pixels on List<Rgb565Color> {
  /// Flattens this pixel list into big-endian RGB565 bytes.
  Uint8List toWireBytes() {
    final out = Uint8List(length * 2);
    for (var i = 0; i < length; i++) {
      final v = this[i].value;
      out[i * 2] = (v >> 8) & 0xFF;
      out[i * 2 + 1] = v & 0xFF;
    }
    return out;
  }
}

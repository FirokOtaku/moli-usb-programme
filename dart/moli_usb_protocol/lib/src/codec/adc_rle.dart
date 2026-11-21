// ADC read codec (command 0x08) and the two-color RLE block codec
// (command 0x09), plus the MINI key-input adapter that turns ADC samples into
// press/release events without exposing ADC thresholds to the domain layer.

import '../errors.dart';
import 'frame.dart';

/// Encodes ADC reads: `08 channel 00 00 00 00`, response u16 at bytes 4..5.
final class AdcCodec {
  const AdcCodec();

  /// Builds the ADC read frame for [channel] (0..255).
  Frame readChannel(int channel) {
    if (channel < 0 || channel > 0xFF) {
      throw MoliException.invalidArgument(
        'channel must be 0..255 (got $channel)',
        operation: 'AdcCodec.readChannel',
      );
    }
    return Frame(command: 0x08, subcommand: channel);
  }

  /// Decodes the u16 sample from the response (bytes 4..5, big-endian).
  int decodeSample(Frame response) => response.responseU16At4;
}

/// Two-color run-length encoder/decoder for RGB565 pixel blocks.
///
/// Wire format (spec/msu2-wire-protocol.md):
///   09 (run_a << 4 | run_b) color_a_hi color_a_lo color_b_hi color_b_lo
/// Each run is 0..15 pixels; every block encodes exactly 128 pixels. The
/// vendor C function lacks an output-capacity parameter and does not handle
/// tails shorter than 128 pixels — this implementation fixes both: tails are
/// encoded with a zero-length second run, and every API takes an explicit
/// output capacity or returns a bounded list.
abstract final class RleCodec {
  /// Pixels per RLE block.
  static const int pixelsPerBlock = 128;

  /// Maximum runs per block: 128 single-pixel alternations -> 64 pairs.
  static const int maxRunsPerBlock = 64;

  /// Worst-case frames for [pixelCount] pixels (safety upper bound).
  static int worstCaseFrameCount(int pixelCount) {
    final blocks = (pixelCount + pixelsPerBlock - 1) ~/ pixelsPerBlock;
    return blocks * maxRunsPerBlock;
  }

  /// Worst-case encoded byte count for [pixelCount] pixels.
  static int worstCaseByteCount(int pixelCount) =>
      worstCaseFrameCount(pixelCount) * 6;

  /// Encodes [pixels] into RLE frames.
  ///
  /// [pixels] is consumed in 128-pixel blocks; the final partial block is
  /// encoded with run_b = 0. Zero-length runs are never emitted for color_a;
  /// when the block is a single color, one pair (runA, 0) carries it.
  static List<Frame> encode(List<int> rgb565Pixels) {
    if (rgb565Pixels.isEmpty) {
      throw MoliException.invalidArgument(
        'pixel list must not be empty',
        operation: 'RleCodec.encode',
      );
    }
    final frames = <Frame>[];
    for (var blockStart = 0;
        blockStart < rgb565Pixels.length;
        blockStart += pixelsPerBlock) {
      final blockEnd =
          (blockStart + pixelsPerBlock).clamp(0, rgb565Pixels.length);
      _encodeBlock(rgb565Pixels, blockStart, blockEnd, frames);
    }
    return frames;
  }

  static void _encodeBlock(
      List<int> pixels, int start, int end, List<Frame> frames) {
    var i = start;
    while (i < end) {
      final colorA = pixels[i] & 0xFFFF;
      var runA = 1;
      i++;
      while (runA < 15 && i < end && (pixels[i] & 0xFFFF) == colorA) {
        runA++;
        i++;
      }
      // Second run: next different color, or a zero-length run to close the
      // pair when the block or budget is exhausted.
      var runB = 0;
      var colorB = colorA;
      if (i < end && runA < 15) {
        colorB = pixels[i] & 0xFFFF;
        runB = 1;
        i++;
        while (runB < 15 && i < end && (pixels[i] & 0xFFFF) == colorB) {
          runB++;
          i++;
        }
      }
      frames.add(Frame(
        command: 0x09,
        subcommand: (runA << 4) | runB,
        data0: colorA >> 8,
        data1: colorA & 0xFF,
        data2: colorB >> 8,
        data3: colorB & 0xFF,
      ));
    }
  }

  /// Decodes one RLE frame into its (color, run) pairs.
  ///
  /// Returns the pairs as a flat list: color, count, color, count, ...
  /// Zero-length runs contribute nothing (the vendor bug "second color run
  /// length 0 uses uninitialized value" is fixed here).
  static List<int> decodeFrame(Frame frame) {
    if (frame.command != 0x09) {
      throw MoliException.malformedResponse(
        'not an RLE frame: ${frame.command}',
        operation: 'RleCodec.decodeFrame',
      );
    }
    final runA = (frame.subcommand >> 4) & 0x0F;
    final runB = frame.subcommand & 0x0F;
    final colorA = (frame.bytes[2] << 8) | frame.bytes[3];
    final colorB = (frame.bytes[4] << 8) | frame.bytes[5];
    return [
      if (runA > 0) ...[colorA, runA],
      if (runB > 0) ...[colorB, runB],
    ];
  }
}

/// A key input event emitted by the ADC-based MINI key adapter.
enum KeyState { pressed, released }

/// Press/release detector derived from the vendor demo's ADC strategy, with
/// the demo's constants replaced by explicit, caller-configurable values.
///
/// The vendor demo polls channel 9, takes the mean of two startup samples as
/// the idle level, subtracts 125 as the press threshold and emits one "press"
/// on the "below threshold then rises back" transition. That strategy is a
/// demo, not a protocol fact — so this class:
///   - keeps thresholds configurable,
///   - emits both press and release,
///   - never exposes ADC values to the domain layer.
final class AdcKeyDetector {
  AdcKeyDetector({
    required this.channel,
    required this.pressThreshold,
    this.debounce = const Duration(milliseconds: 20),
    this.idleLevel,
  })  : _pressed = false,
        _lastChange = null;

  /// ADC channel the key is wired to (MINI: channel 9 per the demo, needs
  /// real-hardware confirmation).
  final int channel;

  /// ADC value below which the key counts as pressed.
  final int pressThreshold;

  /// Debounce window; samples arriving within it do not change state.
  final Duration debounce;

  /// Optional measured idle level (demo: mean of two startup samples).
  final int? idleLevel;

  bool _pressed = false;
  DateTime? _lastChange;

  /// Whether the detector currently considers the key pressed.
  bool get isPressed => _pressed;

  /// Feeds one sample and returns the state change, if any.
  ///
  /// [now] is injectable for tests.
  KeyState? feed(int sample, {DateTime? now}) {
    final t = now ?? DateTime.now();
    final rawPressed = sample < pressThreshold;
    if (rawPressed == _pressed) return null;
    if (_lastChange != null && t.difference(_lastChange!) < debounce) {
      // Bounce: keep the current state, ignore the sample.
      return null;
    }
    _pressed = rawPressed;
    _lastChange = t;
    return _pressed ? KeyState.pressed : KeyState.released;
  }
}

/// Typed key event delivered to the domain layer.
final class KeyEvent {
  const KeyEvent({
    required this.keyIndex,
    required this.state,
    required this.timestamp,
  });

  /// 0-based key index (MINI key 0).
  final int keyIndex;

  final KeyState state;

  final DateTime timestamp;

  @override
  String toString() =>
      'KeyEvent(key=$keyIndex, ${state == KeyState.pressed ? "down" : "up"})';
}

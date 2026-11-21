// Typed input events delivered to the domain layer.

/// Input source kinds the library can report.
enum InputSource {
  /// A key sampled through an ADC channel (MINI).
  adcKey,

  /// A key reported through an SFR bit (future).
  sfrKey,
}

/// Press / release state.
enum InputState { pressed, released }

/// One input event.
///
/// The ADC sampling strategy lives in the codec layer; the domain layer only
/// ever sees this typed event, never a raw ADC value or a demo threshold.
final class InputEvent {
  const InputEvent({
    required this.source,
    required this.keyIndex,
    required this.state,
    required this.timestamp,
  });

  final InputSource source;

  /// 0-based key index; MINI exposes key 0.
  final int keyIndex;

  final InputState state;

  final DateTime timestamp;

  bool get isPressed => state == InputState.pressed;

  bool get isReleased => state == InputState.released;

  @override
  String toString() => 'InputEvent(${source.name}, key=$keyIndex, '
      '${state == InputState.pressed ? "down" : "up"}, $timestamp)';
}
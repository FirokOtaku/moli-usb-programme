// Session tuning knobs.
//
// Every timeout is explicit: `spec/architecture.md` forbids unbounded busy
// loops, and a cancelled token must be able to stop any operation.

/// Options for one [MoliSession].
final class SessionOptions {
  const SessionOptions({
    this.handshakeTimeout = const Duration(seconds: 5),
    this.operationTimeout = const Duration(seconds: 2),
    this.writeTimeout = const Duration(seconds: 2),
    this.readSliceTimeout = const Duration(milliseconds: 250),
    this.challengeAttempts = 3,
    this.confirmTimeout = const Duration(seconds: 1),
    this.strictEcho = true,
    this.readSelfDescription = false,
    this.tolerateSelfDescriptionFailure = true,
    this.inputPollInterval = const Duration(milliseconds: 50),
    this.keyPressThresholdDelta = 125,
    this.keyDebounce = const Duration(milliseconds: 20),
  });

  /// Total budget for banner detection plus challenge/response.
  final Duration handshakeTimeout;

  /// Default budget for one request/response transaction.
  final Duration operationTimeout;

  /// Budget for one transport write.
  final Duration writeTimeout;

  /// Slice used when polling the transport inside a longer deadline.
  final Duration readSliceTimeout;

  /// How many times the `\0MSNCN` challenge is repeated before giving up.
  final int challengeAttempts;

  /// Budget to receive the challenge echo after one write.
  final Duration confirmTimeout;

  /// Whether a response must start with the request's `command`/`subcommand`.
  ///
  /// Vendor demos compare one or two response bytes, so echo checking catches
  /// desynchronization early. Set to false for firmware that answers with a
  /// different layout; the response is then returned unvalidated.
  final bool strictEcho;

  /// Whether the session reads the 256-byte SFR self-description table during
  /// connect. It costs 256 transactions, so callers opt in.
  final bool readSelfDescription;

  /// When true, a failing self-description read degrades to the profile values
  /// instead of failing the connection.
  final bool tolerateSelfDescriptionFailure;

  /// ADC polling period while input monitoring is active.
  final Duration inputPollInterval;

  /// Distance below the measured idle ADC level that counts as "pressed".
  ///
  /// The vendor demo used 125; it is a demo policy, not a protocol constant.
  final int keyPressThresholdDelta;

  /// Debounce window for the ADC key detector.
  final Duration keyDebounce;

  SessionOptions copyWith({
    Duration? handshakeTimeout,
    Duration? operationTimeout,
    Duration? writeTimeout,
    Duration? readSliceTimeout,
    int? challengeAttempts,
    Duration? confirmTimeout,
    bool? strictEcho,
    bool? readSelfDescription,
    bool? tolerateSelfDescriptionFailure,
    Duration? inputPollInterval,
    int? keyPressThresholdDelta,
    Duration? keyDebounce,
  }) {
    return SessionOptions(
      handshakeTimeout: handshakeTimeout ?? this.handshakeTimeout,
      operationTimeout: operationTimeout ?? this.operationTimeout,
      writeTimeout: writeTimeout ?? this.writeTimeout,
      readSliceTimeout: readSliceTimeout ?? this.readSliceTimeout,
      challengeAttempts: challengeAttempts ?? this.challengeAttempts,
      confirmTimeout: confirmTimeout ?? this.confirmTimeout,
      strictEcho: strictEcho ?? this.strictEcho,
      readSelfDescription: readSelfDescription ?? this.readSelfDescription,
      tolerateSelfDescriptionFailure: tolerateSelfDescriptionFailure ??
          this.tolerateSelfDescriptionFailure,
      inputPollInterval: inputPollInterval ?? this.inputPollInterval,
      keyPressThresholdDelta:
          keyPressThresholdDelta ?? this.keyPressThresholdDelta,
      keyDebounce: keyDebounce ?? this.keyDebounce,
    );
  }
}
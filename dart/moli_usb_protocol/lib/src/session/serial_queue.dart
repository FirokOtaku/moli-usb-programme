// Minimal serializing queue.
//
// `spec/architecture.md` (session constraints): because the wire protocol has
// no request id, every operation that produces a response must be serialized
// inside one session. This helper chains futures so only one transaction runs
// at a time, in submission order, and never loses an error.

import 'dart:async';

/// Runs submitted tasks one at a time, in FIFO order.
final class SerialQueue {
  Future<void> _tail = Future<void>.value();

  bool _closed = false;

  /// Whether [close] has been called.
  bool get isClosed => _closed;

  /// Submits [action] and completes with its result.
  ///
  /// A failing action does not break the chain: later submissions still run,
  /// and their callers receive their own results.
  Future<T> run<T>(Future<T> Function() action) {
    if (_closed) {
      return Future<T>.error(
        StateError('SerialQueue is closed'),
      );
    }
    final completer = Completer<T>();
    _tail = _tail.then((_) async {
      try {
        completer.complete(await action());
      } catch (error, stackTrace) {
        if (!completer.isCompleted) {
          completer.completeError(error, stackTrace);
        }
      }
    });
    return completer.future;
  }

  /// Rejects further submissions. Already queued work still completes.
  void close() {
    _closed = true;
  }
}
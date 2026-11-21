import 'dart:async';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

/// FIFO queue for work that must share one device session.
///
/// Later waves submit frame sends through [run] so they cannot open a second
/// connection or overlap a disconnect. A failing task does not break the chain.
final class CommandQueue {
  Future<void> _tail = Future<void>.value();
  bool _closed = false;
  int _depth = 0;

  bool get isClosed => _closed;

  /// True while a task is queued or running. Checked before a probe so an
  /// idle tick is dropped instead of lined up behind real work.
  bool get isBusy => _depth > 0;

  Future<T> run<T>(Future<T> Function() action) {
    if (_closed) {
      return Future<T>.error(
        const MoliException.disconnected(
          '命令队列已关闭',
          operation: 'submit',
        ),
      );
    }
    return _enqueue(action);
  }

  /// Runs [action] only when the queue is idle. Returns null instead of
  /// queueing when a task is already waiting or running.
  Future<T>? tryRun<T>(Future<T> Function() action) {
    if (_closed || _depth > 0) return null;
    return _enqueue(action);
  }

  Future<T> _enqueue<T>(Future<T> Function() action) {
    _depth++;
    final completer = Completer<T>();
    _tail = _tail.then((_) async {
      try {
        completer.complete(await action());
      } catch (error, stackTrace) {
        if (!completer.isCompleted) {
          completer.completeError(error, stackTrace);
        }
      } finally {
        _depth--;
      }
    });
    return completer.future;
  }

  void close() {
    _closed = true;
  }
}

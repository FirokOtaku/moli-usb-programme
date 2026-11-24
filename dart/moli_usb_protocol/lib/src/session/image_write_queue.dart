part of 'session.dart';

/// What to do when all waiting frame slots are occupied.
enum ImageOverflowPolicy {
  /// Wait asynchronously for a slot. Await submissions to apply backpressure.
  block,

  /// Reject the newly submitted image.
  dropNewest,

  /// Replace the oldest waiting image, without interrupting the active write.
  dropOldest,
}

enum ImageSendResult {
  /// The transport write completed, not a separate panel-render acknowledgment.
  sent,
  dropped,
}

/// Bounded full-screen image sender owned by one [MoliSession].
///
/// Capacity counts waiting images, not the one being sent. Accepted images
/// are sent in order, and transport errors are returned to each submitter.
final class ImageWriteQueue {
  ImageWriteQueue._(
    this._session,
    this.capacity,
    this.overflowPolicy,
    this.options,
  );

  final MoliSession _session;
  final int capacity;
  final ImageOverflowPolicy overflowPolicy;
  final ImageWriteOptions options;
  final _pending = Queue<(RgbaImage, Completer<ImageSendResult>)>();
  final _closed = Completer<void>();
  Completer<void>? _spaceAvailable;
  bool _running = false;
  bool _accepting = true;

  /// Sends an immutable image or returns [ImageSendResult.dropped] on overflow.
  Future<ImageSendResult> submit(RgbaImage image) async {
    while (_accepting &&
        _pending.length >= capacity &&
        overflowPolicy == ImageOverflowPolicy.block) {
      await (_spaceAvailable ??= Completer<void>()).future;
    }
    if (!_accepting) throw StateError('image write queue is closed');
    if (_pending.length >= capacity) {
      if (overflowPolicy == ImageOverflowPolicy.dropNewest) {
        return ImageSendResult.dropped;
      }
      _pending.removeFirst().$2.complete(ImageSendResult.dropped);
    }

    final result = Completer<ImageSendResult>();
    _pending.addLast((image, result));
    if (!_running) unawaited(_sendPending());
    return result.future;
  }

  Future<void> _sendPending() async {
    _running = true;
    while (_pending.isNotEmpty) {
      final (image, result) = _pending.removeFirst();
      _notifySpaceAvailable();
      try {
        await _session.writeImage(image, options: options);
        result.complete(ImageSendResult.sent);
      } catch (error, stackTrace) {
        result.completeError(error, stackTrace);
      }
    }
    _running = false;
    _finishIfClosed();
  }

  void _notifySpaceAvailable() {
    final available = _spaceAvailable;
    _spaceAvailable = null;
    available?.complete();
  }

  /// Stops accepting submissions and waits for already queued writes to finish.
  Future<void> close() {
    _accepting = false;
    _notifySpaceAvailable();
    _finishIfClosed();
    return _closed.future;
  }

  void _finishIfClosed() {
    if (_accepting || _running || _closed.isCompleted) return;
    _session._imageQueues.remove(this);
    _closed.complete();
  }
}

package space.firok.moli.usb.api;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Bounded asynchronous image sender owned by one open device session. */
public final class ImageWriteQueue implements AutoCloseable {

    private final Object monitor = new Object();
    private final ArrayDeque<PendingImage> pending = new ArrayDeque<>();
    private final int capacity;
    private final OverflowPolicy overflowPolicy;
    private final MoliDevice device;
    private final ImageWriteOptions options;
    private final Consumer<ImageWriteQueue> onClosed;
    private final Thread worker;
    private boolean closed;

    ImageWriteQueue(MoliDevice device, ImageWriteOptions options, int capacity,
                    OverflowPolicy overflowPolicy, Consumer<ImageWriteQueue> onClosed) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.device = Objects.requireNonNull(device, "device");
        this.options = Objects.requireNonNull(options, "options");
        this.capacity = capacity;
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
        worker = new Thread(this::run, "moli-usb-image-writer-" + device.id().transportKey());
        worker.setDaemon(true);
        worker.start();
    }

    /** Enqueues a defensive image snapshot and returns its eventual send result. */
    public CompletableFuture<SendResult> submit(BufferedImage image) {
        PendingImage item = new PendingImage(copy(image), new CompletableFuture<>());
        synchronized (monitor) {
            while (!closed && pending.size() >= capacity
                    && overflowPolicy == OverflowPolicy.BLOCK) {
                try {
                    monitor.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    item.result().completeExceptionally(interrupted);
                    return item.result();
                }
            }
            if (closed) {
                item.result().completeExceptionally(new IllegalStateException("image queue is closed"));
            } else if (pending.size() >= capacity && overflowPolicy == OverflowPolicy.DROP_NEWEST) {
                item.result().complete(SendResult.DROPPED);
            } else {
                if (pending.size() >= capacity) {
                    pending.removeFirst().result().complete(SendResult.DROPPED);
                }
                pending.addLast(item);
                monitor.notifyAll();
            }
        }
        return item.result();
    }

    private void run() {
        try {
            while (true) {
                PendingImage item;
                synchronized (monitor) {
                    while (pending.isEmpty() && !closed) {
                        try {
                            monitor.wait();
                        } catch (InterruptedException interrupted) {
                            if (closed) return;
                        }
                    }
                    if (pending.isEmpty() && closed) return;
                    item = pending.removeFirst();
                    monitor.notifyAll();
                }
                try {
                    device.writeImage(item.image(), options);
                    item.result().complete(SendResult.SENT);
                } catch (RuntimeException failure) {
                    item.result().completeExceptionally(failure);
                }
            }
        } finally {
            synchronized (monitor) {
                closed = true;
                while (!pending.isEmpty()) {
                    pending.removeFirst().result().completeExceptionally(
                            new IllegalStateException("image queue stopped before sending"));
                }
                monitor.notifyAll();
            }
            onClosed.accept(this);
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            monitor.notifyAll();
        }
        if (Thread.currentThread() != worker) {
            boolean interrupted = false;
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static BufferedImage copy(BufferedImage source) {
        Objects.requireNonNull(source, "image");
        if (source.getWidth() <= 0 || source.getHeight() <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive");
        }
        BufferedImage snapshot = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth());
        snapshot.setRGB(0, 0, source.getWidth(), source.getHeight(), pixels, 0, source.getWidth());
        return snapshot;
    }

    private record PendingImage(BufferedImage image, CompletableFuture<SendResult> result) { }

    public enum OverflowPolicy {
        /** Wait until a queue slot is available. */
        BLOCK,
        /** Complete the newly submitted frame as dropped when full. */
        DROP_NEWEST,
        /** Drop the oldest queued frame and preserve the newest submission. */
        DROP_OLDEST
    }

    public enum SendResult {
        SENT,
        DROPPED
    }
}

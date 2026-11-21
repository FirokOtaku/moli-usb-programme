package space.firok.moli.usb.gui;

import java.io.IOException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 内容循环。光栅化在 {@code moli-usb-gui-content} 上做，设备 IO 只经
 * {@link ConnectionController#submitDevice} 进 {@code moli-usb-gui-io}。
 *
 * <p>模式切换或 {@link #stop()} 会作废当前代际，已经开始的发送结束后不再发下一帧。
 * 上一帧还没结束时丢掉新帧，不往 JavaFX 线程排队。系统状态和桌面镜像遵守同一条规则：
 * 捕获或采样只在确认上一帧已经结束之后开始，不为了排队再抓一帧。</p>
 */
final class ContentLoop implements AutoCloseable {

    private static final long CAPTURE_LOG_INTERVAL_NANOS = 5_000_000_000L;

    private final ConnectionController connection;
    private final Consumer<PreviewFrame> previewListener;
    private final ScheduledExecutorService ticker;
    private final Clock clock;
    private final SystemSampler sampler;
    private final ScreenCapture screenCapture;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean sending = new AtomicBoolean();
    private final Object scheduleLock = new Object();

    private volatile ContentSpec spec;
    private volatile ScheduledFuture<?> future;
    private volatile boolean loggedFallback;
    private Rgb565Image scrollStrip;
    private int scrollStripGeneration = -1;
    private long scrollOriginNanos;
    private String lastStatsFailureLog;
    private long lastCaptureErrorLogNanos;

    ContentLoop(ConnectionController connection, Consumer<PreviewFrame> previewListener) {
        this(connection, previewListener, Clock.systemDefaultZone(), new OshiSystemSampler(), new RobotScreenCapture());
    }

    ContentLoop(ConnectionController connection, Consumer<PreviewFrame> previewListener, Clock clock) {
        this(connection, previewListener, clock, new OshiSystemSampler(), new RobotScreenCapture());
    }

    ContentLoop(ConnectionController connection, Consumer<PreviewFrame> previewListener, Clock clock,
                SystemSampler sampler, ScreenCapture screenCapture) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.previewListener = Objects.requireNonNull(previewListener, "previewListener");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.screenCapture = Objects.requireNonNull(screenCapture, "screenCapture");
        this.ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "moli-usb-gui-content");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 换成新的设置并取消上一轮。上一轮若已经在发，这一帧会被丢掉，等它结束后再发新内容。
     */
    void update(ContentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        this.spec = spec;
        loggedFallback = false;
        lastStatsFailureLog = null;
        lastCaptureErrorLogNanos = 0;
        scrollStrip = null;
        int gen = generation.incrementAndGet();
        cancelFuture();
        if (!spec.canSend()) {
            return;
        }
        arm(gen, 0);
    }

    /** 取消循环。正在发送的那一帧会走完，但不会再发下一帧。 */
    void stop() {
        generation.incrementAndGet();
        spec = null;
        scrollStrip = null;
        cancelFuture();
    }

    @Override
    public void close() {
        stop();
        ticker.shutdown();
    }

    private void arm(int gen, long delayMs) {
        synchronized (scheduleLock) {
            if (gen != generation.get()) {
                return;
            }
            if (future != null) {
                future.cancel(false);
            }
            future = ticker.schedule(() -> fire(gen), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        }
    }

    private void cancelFuture() {
        synchronized (scheduleLock) {
            if (future != null) {
                future.cancel(false);
                future = null;
            }
        }
    }

    private void fire(int gen) {
        try {
            deliver(gen);
        } catch (RuntimeException error) {
            connection.appendLog("准备画面失败：" + describe(error));
        }
    }

    private void deliver(int gen) {
        if (gen != generation.get()) {
            return;
        }
        if (connection.snapshot().state() != SessionState.CONNECTED) {
            stop();
            return;
        }
        ContentSpec current = spec;
        if (current == null || !current.canSend()) {
            return;
        }
        if (sending.get()) {
            arm(gen, current.retryMillis());
            return;
        }
        if (!sending.compareAndSet(false, true)) {
            arm(gen, current.retryMillis());
            return;
        }
        connection.markContentSendInFlight(true);
        boolean handedOff = false;
        try {
            if (gen != generation.get()) {
                return;
            }
            ComposedFrame frame = compose(current, gen);
            if (frame == null || gen != generation.get()) {
                return;
            }
            previewListener.accept(new PreviewFrame(frame.width(), frame.height(), FrameOps.toArgb(frame.pixels())));
            if (shouldLogFallback(frame)) {
                connection.appendLog("RLE 最坏情况超过上界，已改用未压缩发送");
                loggedFallback = true;
            }
            handedOff = connection.submitDevice(device -> {
                if (gen != generation.get()) {
                    return;
                }
                FrameSender.send(device, frame);
            }, () -> {
                sending.set(false);
                connection.markContentSendInFlight(false);
            });
            if (handedOff && current.periodMillis() > 0 && gen == generation.get()) {
                arm(gen, current.periodMillis());
            }
        } catch (IOException error) {
            connection.appendLog("读取图片失败：" + (error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage()));
        } catch (RuntimeException error) {
            connection.appendLog("准备画面失败：" + describe(error));
        } finally {
            if (!handedOff) {
                sending.set(false);
                connection.markContentSendInFlight(false);
            }
            // 周期模式在采样或捕获失败时继续下一拍，避免一次失败把循环停掉。
            // 已经交给设备的那一拍仍由上面的 arm 安排，这里不重复排队。
            if (!handedOff && current.periodMillis() > 0 && gen == generation.get()) {
                arm(gen, current.periodMillis());
            }
        }
    }

    private boolean shouldLogFallback(ComposedFrame frame) {
        return !loggedFallback
                && FrameSender.kindFor(frame.mode()) == FrameSender.Kind.TWO_COLOR
                && FrameSender.plan(frame.mode(), frame.pixels().length) == FrameSender.StreamPath.UNCOMPRESSED;
    }

    private ComposedFrame compose(ContentSpec current, int gen) throws IOException {
        int width = current.width();
        int height = current.height();
        return switch (current.mode()) {
            case SOLID -> new ComposedFrame(
                    ContentMode.SOLID,
                    width,
                    height,
                    FrameOps.fill(width, height, current.foreground()),
                    current.foreground());
            case IMAGE -> {
                Rgb565Image image = ImageFrames.load(current.imageFile(), width, height, current.coverCrop());
                yield new ComposedFrame(ContentMode.IMAGE, width, height, image.pixels(), 0);
            }
            case STATIC_TEXT -> {
                Rgb565Image image = TextRaster.centered(
                        width, height, current.text(), current.foreground(), current.background(), current.fontSize());
                yield new ComposedFrame(ContentMode.STATIC_TEXT, width, height, image.pixels(), current.foreground());
            }
            case SCROLL -> scrollFrame(current, gen);
            case CLOCK -> {
                Rgb565Image image = ClockRaster.render(
                        width,
                        height,
                        ZonedDateTime.now(clock),
                        current.foreground(),
                        current.background());
                yield new ComposedFrame(ContentMode.CLOCK, width, height, image.pixels(), current.foreground());
            }
            case STATS -> statsFrame(current);
            case MIRROR -> mirrorFrame(current);
        };
    }

    private ComposedFrame statsFrame(ContentSpec current) {
        SystemSample sample;
        try {
            sample = sampler.sample();
        } catch (RuntimeException error) {
            logStatsFailure("读取系统状态失败：" + describe(error));
            sample = SystemSample.unavailable();
        }
        if (sample.failures().isEmpty()) {
            if (lastStatsFailureLog != null && lastStatsFailureLog.startsWith("系统状态有项目不可用")) {
                lastStatsFailureLog = null;
            }
        } else {
            logStatsFailure("系统状态有项目不可用：" + String.join("；", sample.failures()));
        }
        Rgb565Image image = StatsRaster.render(
                current.width(),
                current.height(),
                sample,
                current.foreground(),
                current.background());
        return new ComposedFrame(ContentMode.STATS, current.width(), current.height(), image.pixels(), current.foreground());
    }

    private ComposedFrame mirrorFrame(ContentSpec current) {
        CapturedScreen shot;
        try {
            shot = screenCapture.capturePrimary();
        } catch (RuntimeException error) {
            logCaptureFailure(error);
            return null;
        }
        lastCaptureErrorLogNanos = 0;
        int[] scaled = MirrorFrames.scale(
                shot.width(),
                shot.height(),
                shot.rgb(),
                current.width(),
                current.height(),
                current.coverCrop());
        return new ComposedFrame(ContentMode.MIRROR, current.width(), current.height(), scaled, 0);
    }

    private void logStatsFailure(String text) {
        if (text == null || text.isBlank() || text.equals(lastStatsFailureLog)) {
            return;
        }
        lastStatsFailureLog = text;
        connection.appendLog(text);
    }

    private void logCaptureFailure(RuntimeException error) {
        long now = System.nanoTime();
        if (lastCaptureErrorLogNanos != 0 && now - lastCaptureErrorLogNanos < CAPTURE_LOG_INTERVAL_NANOS) {
            return;
        }
        lastCaptureErrorLogNanos = now;
        connection.appendLog("桌面捕获失败：" + describe(error));
    }

    private ComposedFrame scrollFrame(ContentSpec current, int gen) {
        Rgb565Image strip = scrollStrip;
        if (strip == null || scrollStripGeneration != gen) {
            strip = TextRaster.scrollingStrip(
                    current.width(),
                    current.height(),
                    current.text(),
                    current.foreground(),
                    current.background(),
                    current.fontSize());
            scrollStrip = strip;
            scrollStripGeneration = gen;
            scrollOriginNanos = System.nanoTime();
        }
        long elapsedMs = Math.max(0, (System.nanoTime() - scrollOriginNanos) / 1_000_000L);
        long ticks = elapsedMs / Math.max(1, current.periodMillis());
        int distance = (int) Math.min(Integer.MAX_VALUE, ticks * (long) current.scrollStep());
        int origin = Math.floorMod(distance, strip.width());
        int[] window = FrameOps.cropWindow(
                strip.width(),
                strip.height(),
                strip.pixels(),
                origin,
                current.width(),
                current.height());
        return new ComposedFrame(ContentMode.SCROLL, current.width(), current.height(), window, current.foreground());
    }

    private static String describe(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + "：" + message;
    }
}

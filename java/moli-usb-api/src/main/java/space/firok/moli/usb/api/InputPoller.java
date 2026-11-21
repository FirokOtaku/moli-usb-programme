package space.firok.moli.usb.api;

import space.firok.moli.usb.api.codec.AdcCodec;
import space.firok.moli.usb.api.codec.AdcKeyDetector;
import space.firok.moli.usb.api.domain.InputEvent;
import space.firok.moli.usb.api.error.MoliUsbException;
import space.firok.moli.usb.api.session.CancellationToken;
import space.firok.moli.usb.api.session.ProtocolSession;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 领域 API 内部：ADC 按键轮询器。
 *
 * <p>把 {@link AdcKeyDetector} 的状态翻转翻译为 {@link InputEvent}，并在库内部守护线程上
 * 回调调用方。ADC 通道与阈值不出本类。</p>
 *
 * <p>轮询失败（断线、协议错误）会记录原因并停止，不会无限重试掩盖设备问题。</p>
 */
final class InputPoller implements InputSubscription {

    private static final long POLL_INTERVAL_MS = 40;

    private final ProtocolSession session;
    private final String deviceId;
    private final Consumer<InputEvent> listener;
    private final AdcKeyDetector detector;
    private final Thread thread;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<MoliUsbException> failure = new AtomicReference<>();

    private InputPoller(ProtocolSession session, String deviceId, Consumer<InputEvent> listener,
                        AdcKeyDetector detector) {
        this.session = session;
        this.deviceId = deviceId;
        this.listener = listener;
        this.detector = detector;
        this.thread = new Thread(this::runLoop, "moli-input-" + deviceId);
        this.thread.setDaemon(true);
    }

    static InputPoller start(ProtocolSession session, String deviceId,
                             Consumer<InputEvent> listener, AdcKeyDetector detector) {
        InputPoller poller = new InputPoller(session, deviceId, listener, detector);
        poller.thread.start();
        return poller;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public Optional<MoliUsbException> failure() {
        return Optional.ofNullable(failure.get());
    }

    @Override
    public void close() {
        active.set(false);
        thread.interrupt();
    }

    private void runLoop() {
        try {
            while (active.get() && !Thread.currentThread().isInterrupted()) {
                int sample;
                try {
                    var check = session.exchange(AdcCodec.readChannel(detector.channel()),
                            "subscribeInput", CancellationToken.none(), true);
                    sample = check.u16AtBytes4And5();
                } catch (MoliUsbException error) {
                    failure.set(error);
                    return;
                }
                AdcKeyDetector.Transition transition = detector.feed(sample);
                if (transition != AdcKeyDetector.Transition.NONE) {
                    InputEvent event = new InputEvent(
                            InputEvent.Source.BUTTON_0,
                            transition == AdcKeyDetector.Transition.PRESSED
                                    ? InputEvent.Type.PRESSED
                                    : InputEvent.Type.RELEASED,
                            Instant.now());
                    try {
                        listener.accept(event);
                    } catch (MoliUsbException listenerError) {
                        // 调用方回调抛出协议错误：说明监听方已无法继续，记录并停止。
                        failure.set(listenerError);
                        return;
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            active.set(false);
        }
    }
}
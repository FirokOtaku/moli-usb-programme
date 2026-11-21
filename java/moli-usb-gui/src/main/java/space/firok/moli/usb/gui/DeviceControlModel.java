package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.InputSubscription;
import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.InputEvent;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 能力控件的决策。不持有设备，也不在调用线程做设备 IO。
 *
 * <p>不支持的能力直接拒绝，不把任务交给 {@link Actions}。灯数默认值只是界面初始值，
 * 不是协议常量。</p>
 */
final class DeviceControlModel {

    /** 界面默认灯数。用户可以改，不要当成协议事实。 */
    static final int DEFAULT_LAMP_COUNT = 64;

    static final String BACKLIGHT_UNSUPPORTED = "当前设备不支持背光";
    static final String LEDS_UNSUPPORTED = "当前设备不支持 RGB 灯";
    static final String BUTTON_ABSENT = "当前设备没有按键";
    static final String ORIENTATION_NONE = "当前设备没有报告显示方向";
    static final String ORIENTATION_SINGLE = "当前设备只支持一个方向";

    @FunctionalInterface
    interface Actions {
        /**
         * 把设备操作交给 worker。未接受时不调用 {@code onComplete}。
         *
         * @return 任务已接受时为 true
         */
        boolean submit(Consumer<MoliDevice> action, Runnable onComplete);
    }

    private final Actions actions;
    private final Executor uiExecutor;
    private final Object backlightLock = new Object();
    private boolean backlightInFlight;
    private Integer backlightPending;
    private final AtomicInteger subscribeEpoch = new AtomicInteger();
    private final AtomicReference<InputSubscription> subscription = new AtomicReference<>();

    DeviceControlModel(Actions actions, Executor uiExecutor) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
    }

    static boolean backlightEnabled(ConnectionSnapshot snapshot) {
        return connected(snapshot) && snapshot.capabilities().backlight();
    }

    static boolean ledsEnabled(ConnectionSnapshot snapshot) {
        return connected(snapshot) && snapshot.capabilities().rgbLeds();
    }

    static boolean buttonEnabled(ConnectionSnapshot snapshot) {
        return connected(snapshot) && snapshot.capabilities().buttonInput();
    }

    static boolean orientationEnabled(ConnectionSnapshot snapshot) {
        return connected(snapshot) && snapshot.orientations().size() > 1;
    }

    /** 方向控件禁用时的原因；可以操作时为 null。 */
    static String orientationHint(ConnectionSnapshot snapshot) {
        if (!connected(snapshot) || snapshot.orientations().isEmpty()) {
            return ORIENTATION_NONE;
        }
        if (snapshot.orientations().size() == 1) {
            return ORIENTATION_SINGLE;
        }
        return null;
    }

    /**
     * @return 已提交设备任务时为 true；能力不够或方向不在几何里时为 false，且不调用设备
     */
    boolean requestOrientation(ConnectionSnapshot snapshot, DisplayGeometry.Orientation orientation) {
        if (!orientationEnabled(snapshot) || orientation == null
                || !snapshot.orientations().contains(orientation)) {
            return false;
        }
        return actions.submit(device -> device.setOrientation(orientation), null);
    }

    /**
     * 背光拖动可以连续进来。已有命令在途时只保留最新值，等它结束后再发一次，不逐档排队。
     *
     * @return 这一拍已经提交时为 true；不支持、或只是记下最新值时为 false
     */
    boolean requestBacklight(ConnectionSnapshot snapshot, int pwm) {
        if (!backlightEnabled(snapshot)) {
            return false;
        }
        synchronized (backlightLock) {
            if (backlightInFlight) {
                backlightPending = pwm;
                return false;
            }
            backlightInFlight = true;
        }
        return submitBacklight(pwm);
    }

    /** 断开或失去背光能力时丢掉尚未发出的中间值。已经在途的那一次由会话状态自己丢弃。 */
    void cancelBacklight() {
        synchronized (backlightLock) {
            backlightPending = null;
        }
    }

    /**
     * 一种颜色写到 {@code 0..count-1}，然后只 {@code applyLeds} 一次。
     *
     * @return 已提交时为 true；不支持 RGB 灯时为 false，且不调用设备
     */
    boolean requestLeds(ConnectionSnapshot snapshot, int red, int green, int blue, int count) {
        if (!ledsEnabled(snapshot)) {
            return false;
        }
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255
                || count < 1 || count > 256) {
            return false;
        }
        return actions.submit(device -> {
            for (int index = 0; index < count; index++) {
                device.setLed(index, red, green, blue);
            }
            device.applyLeds();
        }, null);
    }

    /**
     * 仅在声明按键能力时订阅。库回调先经 {@code uiExecutor} 再交给 {@code onUi}，
     * 因此 {@code onUi} 可以碰界面控件。
     *
     * @return 已提交订阅时为 true；没有按键能力时为 false，且不调用设备
     */
    boolean subscribeButton(ConnectionSnapshot snapshot, Consumer<InputEvent> onUi) {
        Objects.requireNonNull(onUi, "onUi");
        if (!buttonEnabled(snapshot)) {
            return false;
        }
        int epoch = subscribeEpoch.get();
        return actions.submit(device -> {
            if (epoch != subscribeEpoch.get()) {
                return;
            }
            InputSubscription created = device.subscribeInput(event ->
                    uiExecutor.execute(() -> onUi.accept(event)));
            InputSubscription previous = subscription.getAndSet(created);
            if (previous != null) {
                previous.close();
            }
            if (epoch != subscribeEpoch.get() && subscription.compareAndSet(created, null)) {
                created.close();
            }
        }, null);
    }

    /** 断开时退订。可以在界面线程调用；{@link InputSubscription#close()} 只是打断轮询。 */
    void stopButtons() {
        subscribeEpoch.incrementAndGet();
        InputSubscription current = subscription.getAndSet(null);
        if (current != null) {
            current.close();
        }
    }

    private boolean submitBacklight(int pwm) {
        boolean accepted = actions.submit(device -> device.setBacklight(pwm), this::onBacklightDone);
        if (!accepted) {
            synchronized (backlightLock) {
                backlightInFlight = false;
                backlightPending = null;
            }
        }
        return accepted;
    }

    private void onBacklightDone() {
        Integer next;
        synchronized (backlightLock) {
            backlightInFlight = false;
            next = backlightPending;
            backlightPending = null;
            if (next != null) {
                backlightInFlight = true;
            }
        }
        if (next != null) {
            submitBacklight(next);
        }
    }

    private static boolean connected(ConnectionSnapshot snapshot) {
        return snapshot != null
                && snapshot.state() == SessionState.CONNECTED
                && snapshot.capabilities() != null
                && snapshot.orientations() != null;
    }
}

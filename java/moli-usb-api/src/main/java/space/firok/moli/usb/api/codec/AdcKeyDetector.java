package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：ADC 按键适配器。
 *
 * <p>规范（spec/msu2-wire-protocol.md「ADC 与输入」）：旧版演示轮询通道 9，以启动时
 * 两次采样的均值减 125 作为按下阈值，并在「低于阈值后再回升」时产生一次按键动作。
 * 该阈值是 Demo 策略而非协议常量，因此这里把它做成可配置参数，并且把 ADC 数值完全
 * 关在本层内部：上层只拿到按下/释放状态。</p>
 *
 * <p>该类是纯状态机，不做 I/O、不启动线程，便于用固定采样序列测试。</p>
 */
public final class AdcKeyDetector {

    /** 电压低于 {@code idleLevel - delta} 视为按下。 */
    public static final int DEMO_DELTA = 125;

    /** 一次采样判定结果。 */
    public enum Transition {
        /** 状态无变化。 */
        NONE,
        /** 由未按下变为按下。 */
        PRESSED,
        /** 由按下变为未按下。 */
        RELEASED
    }

    private final int channel;
    private final int pressThreshold;
    private final int debounceSamples;

    private boolean pressed;
    private int stableSamples;

    /**
     * @param channel          ADC 通道（MINI 演示为 9，仍需实机确认）
     * @param pressThreshold   采样值低于该值视为按下
     * @param debounceSamples  状态翻转前需要连续一致的采样次数，至少 1
     */
    public AdcKeyDetector(int channel, int pressThreshold, int debounceSamples) {
        Frame.checkByte("channel", channel);
        Frame.checkU16("pressThreshold", pressThreshold);
        if (debounceSamples < 1) {
            throw new InvalidArgumentError("AdcKeyDetector", null,
                    "debounceSamples 必须至少为 1（实际 " + debounceSamples + "）");
        }
        this.channel = channel;
        this.pressThreshold = pressThreshold;
        this.debounceSamples = debounceSamples;
        this.pressed = false;
        this.stableSamples = 0;
    }

    /**
     * 由启动时的空闲采样均值推导阈值（Demo 策略：均值 - 125）。
     *
     * @param idleLevel 空闲电平，至少 {@code DEMO_DELTA + 1}
     */
    public static AdcKeyDetector fromIdleLevel(int channel, int idleLevel) {
        if (idleLevel <= DEMO_DELTA) {
            throw new InvalidArgumentError("AdcKeyDetector.fromIdleLevel", null,
                    "空闲电平必须大于 " + DEMO_DELTA + "（实际 " + idleLevel + "）");
        }
        return new AdcKeyDetector(channel, idleLevel - DEMO_DELTA, 1);
    }

    public int channel() {
        return channel;
    }

    /** 当前阈值。仅供本层与确定性测试使用，不通过领域 API 暴露。 */
    public int pressThreshold() {
        return pressThreshold;
    }

    public boolean isPressed() {
        return pressed;
    }

    /** 送入一个采样，返回状态翻转；不足去抖次数时返回 {@code NONE}。 */
    public Transition feed(int sample) {
        Frame.checkU16("sample", sample);
        boolean rawPressed = sample < pressThreshold;
        if (rawPressed == pressed) {
            stableSamples = 0;
            return Transition.NONE;
        }
        stableSamples++;
        if (stableSamples < debounceSamples) {
            return Transition.NONE;
        }
        stableSamples = 0;
        pressed = rawPressed;
        return pressed ? Transition.PRESSED : Transition.RELEASED;
    }
}
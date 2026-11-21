package space.firok.moli.usb.api.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 领域层：输入事件。
 *
 * <p>对应 spec/architecture.md 中的 {@code InputEvent}：输入源、按下/释放状态和
 * 时间。MINI 的按键由 ADC 采样适配为事件，不把 ADC 阈值暴露给上层。</p>
 */
public record InputEvent(Source source, Type type, Instant timestamp) {

    public InputEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    /** 输入源。BUTTON_0 对应 MINI 的唯一按键。 */
    public enum Source {
        BUTTON_0
    }

    /** 事件类型：按下/释放。 */
    public enum Type {
        PRESSED, RELEASED
    }
}

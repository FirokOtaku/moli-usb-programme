package space.firok.moli.usb.api.domain;

import java.util.Objects;

/**
 * 领域层：设备能力集。
 *
 * <p>对应 spec/architecture.md 中的 {@code DeviceCapabilities}：显示、外部 Flash、
 * RGB 灯、背光、NAND、输入等独立能力。业务代码按能力判断，不以型号枚举代替
 * 能力判断。MINI、LITE、PRO 是已知 profile，不是封闭枚举；未知型号只要完成握手
 * 并报告必要能力，即可作为通用 MSU2 设备使用。</p>
 */
public record DeviceCapabilities(
        boolean display,
        boolean externalFlash,
        boolean rgbLeds,
        boolean backlight,
        boolean nand,
        boolean buttonInput) {

    public static DeviceCapabilities of(
            boolean display, boolean externalFlash, boolean rgbLeds,
            boolean backlight, boolean nand, boolean buttonInput) {
        return new DeviceCapabilities(display, externalFlash, rgbLeds, backlight, nand, buttonInput);
    }

    public boolean supports(Feature feature) {
        Objects.requireNonNull(feature, "feature");
        return switch (feature) {
            case DISPLAY -> display;
            case EXTERNAL_FLASH -> externalFlash;
            case RGB_LEDS -> rgbLeds;
            case BACKLIGHT -> backlight;
            case NAND -> nand;
            case BUTTON_INPUT -> buttonInput;
        };
    }

    /** 独立能力位。 */
    public enum Feature {
        DISPLAY, EXTERNAL_FLASH, RGB_LEDS, BACKLIGHT, NAND, BUTTON_INPUT
    }
}

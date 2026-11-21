package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：显示命令 {@code 0x02} 的帧构造。
 *
 * <p>纯函数、无 I/O、无状态。帧布局见 spec/msu2-wire-protocol.md「显示状态」。</p>
 */
public final class DisplayCodec {

    private DisplayCodec() {
    }

    /** {@code 02 00 x_hi x_lo y_hi y_lo}：设置后续操作左上角。 */
    public static Frame setXy(int x, int y) {
        Frame.checkU16("x", x);
        Frame.checkU16("y", y);
        return Frame.of(Commands.DISPLAY, DisplaySub.SET_XY,
                (x >>> 8) & 0xFF, x & 0xFF, (y >>> 8) & 0xFF, y & 0xFF);
    }

    /** {@code 02 01 w_hi w_lo h_hi h_lo}：设置后续操作尺寸。 */
    public static Frame setSize(int width, int height) {
        Frame.checkU16("width", width);
        Frame.checkU16("height", height);
        return Frame.of(Commands.DISPLAY, DisplaySub.SET_SIZE,
                (width >>> 8) & 0xFF, width & 0xFF, (height >>> 8) & 0xFF, height & 0xFF);
    }

    /** {@code 02 02 fg_hi fg_lo bg_hi bg_lo}：设置前景/背景色。 */
    public static Frame setColors(int foregroundRgb565, int backgroundRgb565) {
        Frame.checkU16("foreground", foregroundRgb565);
        Frame.checkU16("background", backgroundRgb565);
        return Frame.of(Commands.DISPLAY, DisplaySub.SET_COLORS,
                (foregroundRgb565 >>> 8) & 0xFF, foregroundRgb565 & 0xFF,
                (backgroundRgb565 >>> 8) & 0xFF, backgroundRgb565 & 0xFF);
    }

    /** {@code 02 03 action a b c}：执行显示动作。 */
    public static Frame action(int action, int arg0, int arg1, int arg2) {
        Frame.checkByte("action", action);
        Frame.checkByte("arg0", arg0);
        Frame.checkByte("arg1", arg1);
        Frame.checkByte("arg2", arg2);
        return Frame.of(Commands.DISPLAY, DisplaySub.ACTION, action, arg0, arg1, arg2);
    }

    /** 动作 {@code 0x0A}：设置显示方向。 */
    public static Frame setOrientation(int orientation) {
        Frame.checkByte("orientation", orientation);
        return action(DisplayAction.SET_ORIENTATION, orientation, 0, 0);
    }

    /** 动作 {@code 0x0B}：用指定颜色填充当前区域。 */
    public static Frame fillCurrent(int rgb565) {
        Frame.checkU16("color", rgb565);
        return action(DisplayAction.FILL_CURRENT,
                (rgb565 >>> 8) & 0xFF, rgb565 & 0xFF, 0);
    }

    /**
     * 动作 {@code 0x12}：设置背光 PWM。
     *
     * <p>源码限制 0..1000。该动作仅 PRO 源码出现过，能力检查由上层完成。</p>
     */
    public static Frame setBacklight(int pwm) {
        if (pwm < 0 || pwm > 1000) {
            throw new InvalidArgumentError("DisplayCodec.setBacklight", null,
                    "背光 PWM 必须落在 0..1000（实际 " + pwm + "）");
        }
        return action(DisplayAction.SET_BACKLIGHT, (pwm >>> 8) & 0xFF, pwm & 0xFF, 0);
    }

    /**
     * 动作 {@code 0x00}：显示 Flash 中的彩色图。
     *
     * <p>页地址的三字节布局由 {@code order} 决定，必须由调用方按设备 profile 选择：
     * 旧源码/LITE 用 16 位页并把末字节置零，PRO 用「中、低、高」的 24 位布局。</p>
     */
    public static Frame showFlashColor(int page, PageAddressOrder order) {
        if (order == null) {
            throw new InvalidArgumentError("DisplayCodec.showFlashColor", null, "order must not be null");
        }
        Frame.checkU24("page", page);
        return switch (order) {
            case LOW_HIGH_ZERO -> {
                Frame.checkU16("page (LOW_HIGH_ZERO)", page);
                yield action(DisplayAction.SHOW_FLASH_COLOR, (page >>> 8) & 0xFF, page & 0xFF, 0);
            }
            case MID_LOW_HIGH -> action(DisplayAction.SHOW_FLASH_COLOR,
                    (page >>> 8) & 0xFF, page & 0xFF, (page >>> 16) & 0xFF);
        };
    }

    /** 动作 {@code 0x01}：显示 Flash 中的单色图（{@code page_high, page_low, 00}）。 */
    public static Frame showFlashMono(int page) {
        Frame.checkU16("page", page);
        return action(DisplayAction.SHOW_FLASH_MONO, (page >>> 8) & 0xFF, page & 0xFF, 0);
    }

    /** 动作 {@code 0x07}：应用已设置区域，准备流式像素写入。 */
    public static Frame beginStream() {
        return action(DisplayAction.BEGIN_STREAM, 0, 0, 0);
    }

    /** 动作 {@code 0x08}：提交本批暂存区像素数据，{@code size} 为本批字节数。 */
    public static Frame commitStaged(int size) {
        Frame.checkU16("size", size);
        return action(DisplayAction.COMMIT_STAGED, (size >>> 8) & 0xFF, size & 0xFF, 0);
    }

    /** 动作 {@code 0x09}：厂商语义的「等待传输完成」。 */
    public static Frame waitTransferDone() {
        return action(DisplayAction.WAIT_TRANSFER_DONE, 0, 0, 0);
    }

    /** {@code 02 05 led_index r g b}：设置一个 RGB 灯的暂存值。 */
    public static Frame setLed(int ledIndex, int red, int green, int blue) {
        Frame.checkByte("ledIndex", ledIndex);
        Frame.checkByte("red", red);
        Frame.checkByte("green", green);
        Frame.checkByte("blue", blue);
        return Frame.of(Commands.DISPLAY, DisplaySub.SET_LED, ledIndex, red, green, blue);
    }

    /** 动作 {@code 0x0C}：提交 RGB 灯暂存值。 */
    public static Frame applyLeds() {
        return action(DisplayAction.APPLY_LEDS, 0, 0, 0);
    }
}
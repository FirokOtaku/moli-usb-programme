package space.firok.moli.usb.api.codec;

/**
 * 编解码层：显示命令 {@code 0x02} 的子命令（6 字节帧的 byte 1）。
 */
public final class DisplaySub {

    /** 设置后续操作的左上角坐标。 */
    public static final int SET_XY = 0x00;
    /** 设置后续操作的尺寸。 */
    public static final int SET_SIZE = 0x01;
    /** 设置前景/背景色。 */
    public static final int SET_COLORS = 0x02;
    /** 执行显示动作，data0 为动作号。 */
    public static final int ACTION = 0x03;
    /** 设置一个 RGB 灯的暂存值。 */
    public static final int SET_LED = 0x05;

    private DisplaySub() {
    }
}
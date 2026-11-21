package space.firok.moli.usb.api.codec;

/**
 * 编解码层：显示动作号（{@code 02 03 action ...} 的 action 字节）。
 *
 * <p>来源：spec/msu2-wire-protocol.md「显示状态」表格。未在表中登记的动作不得加入。</p>
 */
public final class DisplayAction {

    /** 显示 Flash 彩色图；参数为页地址，字节顺序随 profile 变化。 */
    public static final int SHOW_FLASH_COLOR = 0x00;
    /** 显示 Flash 单色图；参数为 {@code page_high, page_low, 00}。 */
    public static final int SHOW_FLASH_MONO = 0x01;
    /** 显示 ASCII 字形。 */
    public static final int DRAW_ASCII = 0x02;
    /** 显示 16x16 GB2312 字形。 */
    public static final int DRAW_GB2312 = 0x03;
    /** 单色图与当前背景页混合。 */
    public static final int BLEND_MONO_BACKGROUND = 0x04;
    /** ASCII 与当前背景页混合。 */
    public static final int BLEND_ASCII_BACKGROUND = 0x05;
    /** GB2312 与当前背景页混合。 */
    public static final int BLEND_GB2312_BACKGROUND = 0x06;
    /** 应用已设置的区域，准备流式像素写入。 */
    public static final int BEGIN_STREAM = 0x07;
    /** 提交暂存区像素数据；参数为本批长度。 */
    public static final int COMMIT_STAGED = 0x08;
    /** 厂商源码称「等待传输完成」。 */
    public static final int WAIT_TRANSFER_DONE = 0x09;
    /** 设置显示方向。 */
    public static final int SET_ORIENTATION = 0x0A;
    /** 填充当前区域。 */
    public static final int FILL_CURRENT = 0x0B;
    /** 提交 RGB 灯暂存值。 */
    public static final int APPLY_LEDS = 0x0C;
    /** 初始化单色 RAM 缓冲区。 */
    public static final int INIT_MONO_RAM = 0x0D;
    /** 无掩膜加载单色图到 RAM。 */
    public static final int LOAD_MONO_RAM_NO_MASK = 0x0E;
    /** 带掩膜加载单色图到 RAM。 */
    public static final int LOAD_MONO_RAM_MASKED = 0x0F;
    /** 以前景/背景色显示 RAM。 */
    public static final int SHOW_RAM = 0x10;
    /** 以前景色和背景图显示 RAM。 */
    public static final int SHOW_RAM_WITH_BACKGROUND = 0x11;
    /** 设置背光 PWM，源码限制 0..1000。 */
    public static final int SET_BACKLIGHT = 0x12;

    private DisplayAction() {
    }
}
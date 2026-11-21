package space.firok.moli.usb.api.codec;

/**
 * 编解码层：Flash / NAND 命令 {@code 0x03} 的子命令（6 字节帧的 byte 1）。
 *
 * <p>来源：spec/msu2-wire-protocol.md「Flash 与暂存区」。</p>
 */
public final class FlashSub {

    /** 读取一个字节；响应 byte 5。 */
    public static final int READ_BYTE = 0x00;
    /** 把暂存区写入指定页。 */
    public static final int WRITE_PAGES_FROM_SCRATCH = 0x01;
    /** 擦除连续页。 */
    public static final int ERASE_PAGES = 0x02;
    /** 厂商称快速写 / 带擦除写。 */
    public static final int WRITE_PAGES_FAST = 0x03;
    /** 配置连续高速写起点；布局随介质（串行 Flash / NAND）不同。 */
    public static final int HIGH_SPEED_WRITE_START = 0x04;
    /** 芯片擦除（PRO）。 */
    public static final int CHIP_ERASE = 0x05;
    /** 擦除 NAND 块（PRO）。 */
    public static final int ERASE_NAND_BLOCK = 0x06;

    private FlashSub() {
    }
}
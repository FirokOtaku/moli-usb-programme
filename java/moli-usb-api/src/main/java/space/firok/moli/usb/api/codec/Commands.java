package space.firok.moli.usb.api.codec;

/**
 * 编解码层：顶层命令字（6 字节帧的 byte 0）。
 *
 * <p>来源：spec/msu2-wire-protocol.md「顶层命令」。握手帧不是命令帧，由
 * {@link HandshakeCodec} 单独处理。</p>
 */
public final class Commands {

    /** SFR 读写；byte 1 固定为 ASCII '0' ({@code 0x30})。 */
    public static final int SFR = 0x00;
    /** 显示、RGB 灯及显示动作。 */
    public static final int DISPLAY = 0x02;
    /** Flash / NAND 操作。 */
    public static final int FLASH = 0x03;
    /** 向 256 字节暂存区写入 4 字节。 */
    public static final int SCRATCH = 0x04;
    /** ADC 通道读取。 */
    public static final int ADC = 0x08;
    /** 两色游程编码的 RGB565 数据块。 */
    public static final int RLE = 0x09;

    private Commands() {
    }
}
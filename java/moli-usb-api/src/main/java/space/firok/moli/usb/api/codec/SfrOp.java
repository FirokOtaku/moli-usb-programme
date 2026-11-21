package space.firok.moli.usb.api.codec;

/**
 * 编解码层：SFR 操作码（{@code 00 30 op ...} 的 op 字节）。
 *
 * <p>来源：spec/msu2-wire-protocol.md「SFR」。</p>
 */
public final class SfrOp {

    /** u8 读：{@code 00 30 00 addr_u16 00}，响应 byte 5。 */
    public static final int READ_U8 = 0x00;
    /** u16 读：{@code 00 30 20 addr_u8 00 00}，响应 byte 4..5。 */
    public static final int READ_U16 = 0x20;
    /** u8 写：{@code 00 30 80 addr_u16 value}。 */
    public static final int WRITE_U8 = 0x80;
    /**
     * u16 写的候选操作码。
     *
     * <p>新 LITE/PRO 源码把写操作码误写成 {@code 0x20}（与读冲突），旧源码与函数
     * 注释使用 {@code 0xA0}。实机确认前编解码层只提供候选编码，领域 API 不公开
     * u16 写能力。</p>
     */
    public static final int WRITE_U16 = 0xA0;

    /** SFR 帧固定的子命令字节：ASCII '0'。 */
    public static final int SUBCOMMAND = 0x30;

    private SfrOp() {
    }
}
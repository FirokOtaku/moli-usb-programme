package space.firok.moli.usb.api.codec;

/**
 * 编解码层：SFR 读写（命令 {@code 0x00}，子命令固定 ASCII '0'）。
 *
 * <p>规范见 spec/msu2-wire-protocol.md「SFR」：</p>
 *
 * <pre>{@code
 * u8  读：00 30 00 addr_hi addr_lo 00   响应 byte 5
 * u16 读：00 30 20 addr 00 00           响应 byte 4..5
 * u8  写：00 30 80 addr_hi addr_lo value
 * u16 写：00 30 A0 addr value_hi value_lo   （候选值，实机未确认）
 * }</pre>
 */
public final class SfrCodec {

    private SfrCodec() {
    }

    /** {@code 00 30 00 addr_hi addr_lo 00}：读 u8。 */
    public static Frame readU8(int address) {
        Frame.checkU16("address", address);
        return Frame.of(Commands.SFR, SfrOp.SUBCOMMAND, SfrOp.READ_U8,
                (address >>> 8) & 0xFF, address & 0xFF, 0);
    }

    /** {@code 00 30 20 addr 00 00}：读 u16（8 位地址）。 */
    public static Frame readU16(int address) {
        Frame.checkByte("address", address);
        return Frame.of(Commands.SFR, SfrOp.SUBCOMMAND, SfrOp.READ_U16, address, 0, 0);
    }

    /** {@code 00 30 80 addr_hi addr_lo value}：写 u8。 */
    public static Frame writeU8(int address, int value) {
        Frame.checkU16("address", address);
        Frame.checkByte("value", value);
        return Frame.of(Commands.SFR, SfrOp.SUBCOMMAND, SfrOp.WRITE_U8,
                (address >>> 8) & 0xFF, address & 0xFF, value);
    }

    /**
     * {@code 00 30 A0 addr value_hi value_lo}：写 u16。
     *
     * <p>厂商新源码把写操作码误写成 {@code 0x20}，实机确认前本编码使用旧源码与函数
     * 注释一致的 {@code 0xA0}。领域 API 暂不公开该操作。</p>
     */
    public static Frame writeU16(int address, int value) {
        Frame.checkByte("address", address);
        Frame.checkU16("value", value);
        return Frame.of(Commands.SFR, SfrOp.SUBCOMMAND, SfrOp.WRITE_U16,
                address, (value >>> 8) & 0xFF, value & 0xFF);
    }
}
package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：6 字节命令帧。
 *
 * <p>布局（spec/msu2-wire-protocol.md「基本帧」）：</p>
 *
 * <pre>{@code
 * byte 0     byte 1       byte 2       byte 3       byte 4       byte 5
 * command    subcommand   data0        data1        data2        data3
 * }</pre>
 *
 * <p>帧没有同步头、长度、校验和和请求 ID。多个完整帧可以在一次传输写入中连续发送；
 * 本类型不负责拼接，也不负责 I/O。多字节整数一律高字节在前。</p>
 *
 * <p>本类型是不可变值对象：构造时校验每个字节落在 0..255，构造后不会被改写。</p>
 */
public final class Frame {

    /** 帧长度，固定 6 字节。 */
    public static final int LENGTH = 6;

    private final int command;
    private final int subcommand;
    private final int data0;
    private final int data1;
    private final int data2;
    private final int data3;

    private Frame(int command, int subcommand, int data0, int data1, int data2, int data3) {
        this.command = checkByte("command", command);
        this.subcommand = checkByte("subcommand", subcommand);
        this.data0 = checkByte("data0", data0);
        this.data1 = checkByte("data1", data1);
        this.data2 = checkByte("data2", data2);
        this.data3 = checkByte("data3", data3);
    }

    /** 由 6 个字节字段构造一帧。 */
    public static Frame of(int command, int subcommand, int data0, int data1, int data2, int data3) {
        return new Frame(command, subcommand, data0, data1, data2, data3);
    }

    /**
     * 由两个 16 位大端字段构造一帧：{@code data0..1 = first}，{@code data2..3 = second}。
     * 用于「设置坐标」与「设置尺寸」这类布局。
     */
    public static Frame ofU16Pair(int command, int subcommand, int first, int second) {
        checkU16("first", first);
        checkU16("second", second);
        return new Frame(command, subcommand,
                (first >>> 8) & 0xFF, first & 0xFF,
                (second >>> 8) & 0xFF, second & 0xFF);
    }

    /**
     * 把一段字节按原始顺序放进 6 字节帧：前 2 字节是 command/subcommand，随后 4 字节
     * 为 data。仅用于握手帧与规范测试向量已经固定字节顺序的场合。
     */
    public static Frame fromWire(byte[] wire) {
        if (wire == null) {
            throw new InvalidArgumentError("Frame.fromWire", null, "wire must not be null");
        }
        if (wire.length != LENGTH) {
            throw new InvalidArgumentError("Frame.fromWire", null,
                    "帧必须是 6 字节（实际 " + wire.length + "）: " + Hex.of(wire));
        }
        return new Frame(wire[0] & 0xFF, wire[1] & 0xFF,
                wire[2] & 0xFF, wire[3] & 0xFF, wire[4] & 0xFF, wire[5] & 0xFF);
    }

    public int command() {
        return command;
    }

    public int subcommand() {
        return subcommand;
    }

    public int data0() {
        return data0;
    }

    public int data1() {
        return data1;
    }

    public int data2() {
        return data2;
    }

    public int data3() {
        return data3;
    }

    /** 转为 6 字节新数组。每次调用返回独立副本，调用方可安全修改。 */
    public byte[] toWire() {
        return new byte[]{
                (byte) command, (byte) subcommand,
                (byte) data0, (byte) data1, (byte) data2, (byte) data3
        };
    }

    /** 第 {@code index} 个字节（0..5），自动拒绝越界索引。 */
    public int byteAt(int index) {
        if (index < 0 || index >= LENGTH) {
            throw new InvalidArgumentError("Frame.byteAt", null,
                    "帧索引必须为 0..5（实际 " + index + "）");
        }
        return switch (index) {
            case 0 -> command;
            case 1 -> subcommand;
            case 2 -> data0;
            case 3 -> data1;
            case 4 -> data2;
            default -> data3;
        };
    }

    /** data0..1 组成的大端 u16。 */
    public int u16AtData0() {
        return (data0 << 8) | data1;
    }

    /** data2..3 组成的大端 u16。 */
    public int u16AtData2() {
        return (data2 << 8) | data3;
    }

    /**
     * data0..1..2 组成的大端 u24。
     *
     * <p>Flash 命令的 24 位地址占用 {@code data0..data2}（例如
     * {@code 03 00 01 23 45 00} 的地址是 {@code 0x012345}）。</p>
     */
    public int u24AtData0() {
        return (data0 << 16) | (data1 << 8) | data2;
    }

    /** 大写十六进制串，用于错误消息与测试断言。 */
    public String hex() {
        return Hex.of(toWire());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Frame frame
                && frame.command == command
                && frame.subcommand == subcommand
                && frame.data0 == data0
                && frame.data1 == data1
                && frame.data2 == data2
                && frame.data3 == data3;
    }

    @Override
    public int hashCode() {
        int result = command;
        result = 31 * result + subcommand;
        result = 31 * result + data0;
        result = 31 * result + data1;
        result = 31 * result + data2;
        result = 31 * result + data3;
        return result;
    }

    @Override
    public String toString() {
        return "Frame[" + hex() + "]";
    }

    static int checkByte(String name, int value) {
        if (value < 0 || value > 0xFF) {
            throw new InvalidArgumentError("Frame", null,
                    name + " 必须落在 0..255（实际 " + value + "）");
        }
        return value;
    }

    static int checkU16(String name, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new InvalidArgumentError("Frame", null,
                    name + " 必须落在 0..65535（实际 " + value + "）");
        }
        return value;
    }

    static void checkU24(String name, int value) {
        if (value < 0 || value > 0xFFFFFF) {
            throw new InvalidArgumentError("Frame", null,
                    name + " 必须落在 0..16777215（实际 " + value + "）");
        }
    }
}
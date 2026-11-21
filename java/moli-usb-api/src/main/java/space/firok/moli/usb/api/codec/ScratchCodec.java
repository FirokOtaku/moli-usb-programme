package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码层：暂存区写入（命令 {@code 0x04}）。
 *
 * <p>帧为 {@code 04 offset d0 d1 d2 d3}，{@code offset} 取 0..63；64 帧正好填满
 * 256 字节暂存区。此命令只写入暂存区，真正落屏由显示动作 {@code 0x08} 提交。</p>
 */
public final class ScratchCodec {

    /** 暂存区大小（字节）。 */
    public static final int SCRATCH_SIZE = 256;
    /** 每帧承载的数据字节数。 */
    public static final int BYTES_PER_FRAME = 4;
    /** 填满暂存区所需的帧数。 */
    public static final int FRAMES_PER_SCRATCH = SCRATCH_SIZE / BYTES_PER_FRAME;

    private ScratchCodec() {
    }

    /**
     * 构造一帧暂存区写入。
     *
     * @param offset 0..63
     * @param data   恰好 4 个字节
     */
    public static Frame write(int offset, byte[] data) {
        if (offset < 0 || offset > FRAMES_PER_SCRATCH - 1) {
            throw new InvalidArgumentError("ScratchCodec.write", null,
                    "暂存区偏移必须为 0..63（实际 " + offset + "）");
        }
        if (data == null) {
            throw new InvalidArgumentError("ScratchCodec.write", null, "data must not be null");
        }
        if (data.length != BYTES_PER_FRAME) {
            throw new InvalidArgumentError("ScratchCodec.write", null,
                    "暂存区单帧必须写入 4 字节（实际 " + data.length + "）");
        }
        return Frame.of(Commands.SCRATCH, offset,
                data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF, data[3] & 0xFF);
    }

    /**
     * 把一段不超过 256 字节的数据拆成连续的暂存区写入帧。
     *
     * <p>末帧不足 4 字节时补零；由随后的提交动作携带真实长度，补零不会变成多余
     * 像素（与厂商流程一致）。</p>
     */
    public static List<Frame> writeChunk(byte[] chunk) {
        if (chunk == null || chunk.length == 0) {
            throw new InvalidArgumentError("ScratchCodec.writeChunk", null,
                    "chunk 不能为空");
        }
        if (chunk.length > SCRATCH_SIZE) {
            throw new InvalidArgumentError("ScratchCodec.writeChunk", null,
                    "chunk 超过暂存区容量（" + chunk.length + " > " + SCRATCH_SIZE + "）");
        }
        List<Frame> frames = new ArrayList<>((chunk.length + 3) / BYTES_PER_FRAME);
        byte[] slice = new byte[BYTES_PER_FRAME];
        for (int offset = 0; offset < chunk.length; offset += BYTES_PER_FRAME) {
            int length = Math.min(BYTES_PER_FRAME, chunk.length - offset);
            System.arraycopy(chunk, offset, slice, 0, length);
            for (int i = length; i < BYTES_PER_FRAME; i++) {
                slice[i] = 0;
            }
            frames.add(write(offset / BYTES_PER_FRAME, slice));
        }
        return frames;
    }
}
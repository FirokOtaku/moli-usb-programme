package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.MalformedResponseError;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码层：两色游程编码（命令 {@code 0x09}）。
 *
 * <p>线上格式（spec/msu2-wire-protocol.md「像素流」）：</p>
 *
 * <pre>{@code
 * 09 (run_a << 4 | run_b) color_a_hi color_a_lo color_b_hi color_b_lo
 * }</pre>
 *
 * <p>每个 run 为 0..15 个 RGB565 像素，每 128 个像素构成一个编码块。厂商 C 函数存在
 * 三个缺陷，本实现全部修正：</p>
 *
 * <ol>
 *   <li>没有输出容量参数，无法防止最坏情况膨胀——本实现既可返回可增长的帧列表，也能
 *       接收调用方指定的安全上界 {@code maxFrames} 并在超出时失败；</li>
 *   <li>输入不足 128 像素的尾部不处理——本实现按实际长度收尾，用 {@code run_b = 0}
 *       关闭最后一个配对；</li>
 *   <li>{@code run_b == 0} 时读取未初始化的第二颜色——本实现显式把第二颜色设为
 *       第一颜色，解码方也不会从未定义的字节里取值。</li>
 * </ol>
 *
 * <p>最坏情况为逐像素交替，每帧 2 像素，即每块 64 帧；{@link #worstCaseFrameCount(int)}
 * 给出可直接用于预分配或容量检查的上界。</p>
 */
public final class RleCodec {

    /** 每个 RLE 编码块覆盖的像素数。 */
    public static final int PIXELS_PER_BLOCK = 128;
    /** 单帧能承载的最大像素数（两个 15 像素 run，剩余 2 像素由下一帧处理）。 */
    public static final int MAX_PIXELS_PER_FRAME = 30;
    /** 每块可能产生的最多帧数（逐像素交替时每帧 2 像素）。 */
    public static final int MAX_FRAMES_PER_BLOCK = PIXELS_PER_BLOCK / 2;
    /** 单个 run 能表示的最大像素数。 */
    public static final int MAX_RUN = 15;

    private RleCodec() {
    }

    /**
     * 把 RGB565 像素编码为 RLE 帧；结果可增长，调用方无需预知帧数。
     *
     * @param pixels RGB565 像素值，每个都应落在 0..0xFFFF
     */
    public static List<Frame> encode(int[] pixels) {
        return encode(pixels, 0, pixels == null ? 0 : pixels.length, 0);
    }

    /**
     * 把 RGB565 像素编码为 RLE 帧，并限制输出帧数。
     *
     * <p>{@code maxFrames > 0} 时一旦需要超过该帧数立即失败，避免最坏情况膨胀耗尽
     * 内存或超出设备缓冲；{@code maxFrames == 0} 表示不限制。</p>
     */
    public static List<Frame> encode(int[] pixels, int maxFrames) {
        return encode(pixels, 0, pixels == null ? 0 : pixels.length, maxFrames);
    }

    /** 编码 {@code pixels[offset, offset+length)} 区间的像素。 */
    public static List<Frame> encode(int[] pixels, int offset, int length, int maxFrames) {
        if (pixels == null || length <= 0) {
            throw new InvalidArgumentError("RleCodec.encode", null, "像素数据不能为空");
        }
        if (offset < 0 || offset + length > pixels.length) {
            throw new InvalidArgumentError("RleCodec.encode", null,
                    "像素区间非法: offset=" + offset + ", length=" + length
                            + ", total=" + pixels.length);
        }
        if (maxFrames < 0) {
            throw new InvalidArgumentError("RleCodec.encode", null,
                    "maxFrames 不能为负（实际 " + maxFrames + "）");
        }
        List<Frame> frames = maxFrames > 0
                ? new ArrayList<>(Math.min(maxFrames, Math.max(1, length / 2)))
                : new ArrayList<>(Math.max(1, length / 2));
        int blockEnd = offset + length;
        for (int blockStart = offset; blockStart < blockEnd; blockStart += PIXELS_PER_BLOCK) {
            int thisBlockEnd = Math.min(blockStart + PIXELS_PER_BLOCK, blockEnd);
            encodeBlock(pixels, blockStart, thisBlockEnd, frames, maxFrames);
        }
        return frames;
    }

    /**
     * 指定像素数在最坏情况下的帧数上界（用于调用方预分配或容量检查）。
     * 像素数为 0 或负数时返回 0。
     */
    public static int worstCaseFrameCount(int pixelCount) {
        if (pixelCount <= 0) {
            return 0;
        }
        long blocks = (pixelCount + (long) PIXELS_PER_BLOCK - 1) / PIXELS_PER_BLOCK;
        return (int) Math.min(Integer.MAX_VALUE, blocks * MAX_FRAMES_PER_BLOCK);
    }

    /** 指定像素数在最坏情况下的编码字节数上界。 */
    public static int worstCaseByteCount(int pixelCount) {
        return worstCaseFrameCount(pixelCount) * Frame.LENGTH;
    }

    private static void encodeBlock(int[] pixels, int start, int end,
                                    List<Frame> frames, int maxFrames) {
        int index = start;
        while (index < end) {
            int colorA = colorAt(pixels, index);
            int runA = 1;
            index++;
            while (runA < MAX_RUN && index < end && colorAt(pixels, index) == colorA) {
                runA++;
                index++;
            }
            int runB = 0;
            int colorB = colorA;
            if (index < end && runA < MAX_RUN) {
                colorB = colorAt(pixels, index);
                runB = 1;
                index++;
                while (runB < MAX_RUN && index < end && colorAt(pixels, index) == colorB) {
                    runB++;
                    index++;
                }
            }
            if (maxFrames > 0 && frames.size() >= maxFrames) {
                throw new InvalidArgumentError("RleCodec.encode", null,
                        "RLE 输出超过给定容量上界 " + maxFrames + " 帧");
            }
            frames.add(Frame.of(Commands.RLE, (runA << 4) | runB,
                    (colorA >>> 8) & 0xFF, colorA & 0xFF,
                    (colorB >>> 8) & 0xFF, colorB & 0xFF));
        }
    }

    private static int colorAt(int[] pixels, int index) {
        int value = pixels[index];
        if (value < 0 || value > 0xFFFF) {
            throw new InvalidArgumentError("RleCodec.encode", null,
                    "像素值必须是 0..0xFFFF 的 RGB565（索引 " + index + "，实际 " + value + "）");
        }
        return value;
    }

    /**
     * 解码单帧为 {@code [colorA, runA, colorB, runB]}；长度为 0 的 run 也会返回，
     * 但其计数用于区分「配对已关闭」，调用方不得把它当作像素。
     */
    public static int[] decodeFrame(Frame frame) {
        if (frame == null) {
            throw new InvalidArgumentError("RleCodec.decodeFrame", null, "frame 不能为 null");
        }
        if (frame.command() != Commands.RLE) {
            throw new MalformedResponseError("RleCodec.decodeFrame", null,
                    "不是 RLE 帧（command=0x" + Integer.toHexString(frame.command()) + "）",
                    frame.toWire());
        }
        int runA = (frame.subcommand() >>> 4) & 0x0F;
        int runB = frame.subcommand() & 0x0F;
        return new int[]{frame.u16AtData0(), runA, frame.u16AtData2(), runB};
    }

    /**
     * 把若干 RLE 帧解码回像素序列。
     *
     * <p>每个编码块必须恰好还原 128 像素，最后一个块可以给出较少像素；块内像素数
     * 超过 128 或某个块内没有有效像素视为响应格式错误，不做「尽量还原」的容错。</p>
     *
     * @param pixelCount        原始像素总数，用于校验块边界
     * @param expectedFrameCount 预期帧数，用于发现缺帧
     */
    public static int[] decode(List<Frame> frames, int pixelCount, int expectedFrameCount) {
        if (frames == null || frames.isEmpty()) {
            throw new MalformedResponseError("RleCodec.decode", null, "RLE 帧列表为空");
        }
        if (expectedFrameCount > 0 && frames.size() != expectedFrameCount) {
            throw new MalformedResponseError("RleCodec.decode", null,
                    "RLE 帧数不符：期望 " + expectedFrameCount + "，实际 " + frames.size());
        }
        int[] out = new int[Math.max(pixelCount, 0)];
        int written = 0;
        int blockStart = 0;
        for (Frame frame : frames) {
            int[] decoded = decodeFrame(frame);
            int colorA = decoded[0];
            int runA = decoded[1];
            int colorB = decoded[2];
            int runB = decoded[3];
            for (int i = 0; i < runA; i++) {
                if (written >= out.length) {
                    throw new MalformedResponseError("RleCodec.decode", null,
                            "RLE 解出的像素多于预期 " + pixelCount + " 像素", frame.toWire());
                }
                out[written++] = colorA;
            }
            for (int i = 0; i < runB; i++) {
                if (written >= out.length) {
                    throw new MalformedResponseError("RleCodec.decode", null,
                            "RLE 解出的像素多于预期 " + pixelCount + " 像素", frame.toWire());
                }
                out[written++] = colorB;
            }
            if (written - blockStart > PIXELS_PER_BLOCK) {
                throw new MalformedResponseError("RleCodec.decode", null,
                        "单个 RLE 块解出超过 " + PIXELS_PER_BLOCK + " 像素", frame.toWire());
            }
            if (written - blockStart == PIXELS_PER_BLOCK) {
                blockStart = written;
            }
        }
        if (written != out.length) {
            throw new MalformedResponseError("RleCodec.decode", null,
                    "RLE 解出 " + written + " 像素，与预期 " + pixelCount + " 不符");
        }
        return out;
    }
}
package space.firok.moli.usb.api.codec;

import java.util.ArrayList;
import java.util.List;

/**
 * 编解码层：像素流编码。
 *
 * <p>直接写屏幕的流程（spec/msu2-wire-protocol.md「像素流」）：主机先设置区域，发送
 * 动作 {@code 0x07}，再用 {@code 0x04} 帧填充 256 字节暂存区，随后用动作 {@code 0x08}
 * 提交本批长度，最后发送动作 {@code 0x09}。</p>
 *
 * <p>本类只负责生成帧序列；区域与面板边界检查、是否改用 RLE 由上层决定。RGB565 像素
 * 一律高字节在前。</p>
 */
public final class PixelStreamCodec {

    private PixelStreamCodec() {
    }

    /**
     * 把 RGB565 像素值展平为高字节在前的线字节。
     */
    public static byte[] toWireBytes(int[] pixels) {
        if (pixels == null || pixels.length == 0) {
            throw new space.firok.moli.usb.api.error.InvalidArgumentError(
                    "PixelStreamCodec.toWireBytes", null, "像素数据不能为空");
        }
        byte[] out = new byte[pixels.length * 2];
        for (int i = 0; i < pixels.length; i++) {
            int value = pixels[i];
            if (value < 0 || value > 0xFFFF) {
                throw new space.firok.moli.usb.api.error.InvalidArgumentError(
                        "PixelStreamCodec.toWireBytes", null,
                        "像素值必须是 0..0xFFFF 的 RGB565（索引 " + i + "，实际 " + value + "）");
            }
            out[i * 2] = (byte) (value >>> 8);
            out[i * 2 + 1] = (byte) value;
        }
        return out;
    }

    /**
     * 生成一次性流式写入的全部帧，不含前置的 setXy / setSize（由调用方显式发送）。
     *
     * @param pixelWireBytes RGB565 线字节，长度必须为偶数
     */
    public static List<Frame> encodeStream(byte[] pixelWireBytes) {
        if (pixelWireBytes == null || pixelWireBytes.length == 0) {
            throw new space.firok.moli.usb.api.error.InvalidArgumentError(
                    "PixelStreamCodec.encodeStream", null, "像素数据不能为空");
        }
        if (pixelWireBytes.length % 2 != 0) {
            throw new space.firok.moli.usb.api.error.InvalidArgumentError(
                    "PixelStreamCodec.encodeStream", null,
                    "RGB565 字节数必须为偶数（实际 " + pixelWireBytes.length + "）");
        }
        List<Frame> frames = new ArrayList<>();
        frames.add(DisplayCodec.beginStream());
        for (int offset = 0; offset < pixelWireBytes.length; offset += ScratchCodec.SCRATCH_SIZE) {
            int length = Math.min(ScratchCodec.SCRATCH_SIZE, pixelWireBytes.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(pixelWireBytes, offset, chunk, 0, length);
            frames.addAll(ScratchCodec.writeChunk(chunk));
            frames.add(DisplayCodec.commitStaged(length));
        }
        frames.add(DisplayCodec.waitTransferDone());
        return frames;
    }

    /** 生成 RLE 路径的完整帧序列（动作 {@code 0x07} + RLE 帧 + 动作 {@code 0x09}）。 */
    public static List<Frame> encodeRleStream(int[] pixels, int maxFrames) {
        List<Frame> frames = new ArrayList<>();
        frames.add(DisplayCodec.beginStream());
        frames.addAll(RleCodec.encode(pixels, maxFrames));
        frames.add(DisplayCodec.waitTransferDone());
        return frames;
    }
}
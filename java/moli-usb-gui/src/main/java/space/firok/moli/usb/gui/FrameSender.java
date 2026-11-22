package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.codec.RleCodec;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 把已合成的画面交给设备。照片和桌面镜像按 profile 选择压缩：MINI 使用
 * 暂存区差量，LITE/PRO 使用 {@code MSN_compaction} 的 RLE 格式。
 * 纯色使用 {@code fillRect}。文本、时钟和系统状态走两色 RLE，最坏情况超过上界时
 * 才回退到 {@code streamImage}。
 */
final class FrameSender {

    /**
     * 两色 RLE 的帧数上界。比较的是 {@link RleCodec#worstCaseFrameCount(int)}，
     * 不是实际游程。上界要盖住常见面板（320×172 最坏约 27520 帧），文本才会真正走 RLE；
     * 更大的画面回退未压缩。照片不使用本上界。
     */
    static final int RLE_MAX_FRAMES = 131_072;

    enum Kind {
        FILL,
        /** 照片和桌面镜像：由设备 profile 选择压缩格式。 */
        COMPRESSED,
        TWO_COLOR
    }

    enum StreamPath {
        FILL,
        PROFILE_COMPRESSED,
        RLE,
        UNCOMPRESSED
    }

    private FrameSender() {
    }

    static Kind kindFor(ContentMode mode) {
        return switch (mode) {
            case SOLID -> Kind.FILL;
            case IMAGE, MIRROR -> Kind.COMPRESSED;
            case STATIC_TEXT, SCROLL, CLOCK, STATS -> Kind.TWO_COLOR;
        };
    }

    static boolean rleFits(int pixelCount) {
        return RleCodec.worstCaseFrameCount(pixelCount) <= RLE_MAX_FRAMES;
    }

    static StreamPath plan(ContentMode mode, int pixelCount) {
        return switch (kindFor(mode)) {
            case FILL -> StreamPath.FILL;
            case COMPRESSED -> StreamPath.PROFILE_COMPRESSED;
            case TWO_COLOR -> rleFits(pixelCount) ? StreamPath.RLE : StreamPath.UNCOMPRESSED;
        };
    }

    /**
     * 在设备 worker 上调用。纯色只 {@code fillRect}，不把整帧当图片流发送。
     */
    static StreamPath send(MoliDevice device, ComposedFrame frame) {
        if (frame.pixels().length != (long) frame.width() * frame.height()) {
            throw new IllegalArgumentException("像素数量与面板尺寸不符");
        }
        Rect rect = new Rect(0, 0, frame.width(), frame.height());
        StreamPath planned = plan(frame.mode(), frame.pixels().length);
        switch (planned) {
            case FILL -> device.fillRect(rect, new Rgb565(frame.fillColor()));
            case UNCOMPRESSED -> device.streamImage(rect, frame.pixels());
            case PROFILE_COMPRESSED -> device.streamImageCompact(rect, frame.pixels());
            case RLE -> {
                try {
                    device.streamImageRle(rect, frame.pixels(), RLE_MAX_FRAMES);
                } catch (InvalidArgumentError rejected) {
                    device.streamImage(rect, frame.pixels());
                    return StreamPath.UNCOMPRESSED;
                }
            }
        }
        return planned;
    }
}

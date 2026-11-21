package space.firok.moli.usb.api.domain;

import java.util.List;
import java.util.Objects;

/**
 * 领域层：显示几何。
 *
 * <p>对应 spec/architecture.md 中的 {@code DisplayGeometry}：宽、高、坐标原点、
 * 支持的方向和像素格式。当前坐标原点为左上角。</p>
 *
 * @param width        像素宽
 * @param height       像素高
 * @param origin       坐标原点
 * @param orientations 设备支持的方向（按优先级排列，至少包含 {@link Orientation#NORMAL}）
 * @param colorFormat  像素格式
 */
public record DisplayGeometry(
        int width,
        int height,
        Origin origin,
        List<Orientation> orientations,
        PixelFormat colorFormat) {

    public DisplayGeometry {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "display size must be positive: " + width + "x" + height);
        }
        Objects.requireNonNull(origin, "origin");
        orientations = List.copyOf(orientations);
        if (orientations.isEmpty()) {
            throw new IllegalArgumentException("orientations must not be empty");
        }
        Objects.requireNonNull(colorFormat, "colorFormat");
    }

    /** 坐标原点。 */
    public enum Origin {
        TOP_LEFT
    }

    /** 显示方向。数值对应显示动作 {@code 0x0A} 的 orientation 参数。 */
    public enum Orientation {
        NORMAL(0),
        ROTATE_90(1),
        ROTATE_180(2),
        ROTATE_270(3);

        private final int wireValue;

        Orientation(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }
    }

    /** 像素格式。 */
    public enum PixelFormat {
        /** 每像素 2 字节，高字节在前。 */
        RGB565,
        /** 1 位单色。 */
        MONO_1BPP
    }
}

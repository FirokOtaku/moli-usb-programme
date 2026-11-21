package space.firok.moli.usb.api.domain;

/**
 * 领域层：矩形区域。
 *
 * <p>对应 spec/architecture.md 中的 {@code Rect}：x、y、width、height 必须在
 * 编码前做非负、非零和边界校验。校验用 {@link #validateWithin(int, int)} 完成。</p>
 */
public record Rect(int x, int y, int width, int height) {

    public Rect {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("rect origin must be non-negative: " + this);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("rect size must be positive: " + this);
        }
        if (x + width > 0xFFFF || y + height > 0xFFFF) {
            throw new IllegalArgumentException(
                    "rect exceeds 16-bit coordinate space: " + this);
        }
    }

    /**
     * 校验本矩形落在指定屏幕尺寸内。
     *
     * @param displayWidth  屏幕宽（像素）
     * @param displayHeight 屏幕高（像素）
     */
    public void validateWithin(int displayWidth, int displayHeight) {
        if (x + width > displayWidth || y + height > displayHeight) {
            throw new IllegalArgumentException(
                    "rect " + this + " exceeds display " + displayWidth + "x" + displayHeight);
        }
    }
}

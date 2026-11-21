package space.firok.moli.usb.api.codec;

/**
 * 编解码层：Flash 彩色素材页地址在 6 字节动作帧中的字节顺序。
 *
 * <p>动作 {@code 0x00} 使用 3 个字节承载页地址。旧源码/LITE 只使用 16 位页地址并
 * 把最后一字节置零；PRO 把 24 位页地址编码为中、低、高（并非通常的 u24_be）。
 * 顺序随 profile 变化，所以编解码层不能只写一套。</p>
 *
 * <p>该枚举属于编解码层，领域层 {@code DeviceProfile.ColorPageAddressOrder} 由
 * 上层映射到这里，避免编解码层反向依赖领域层。</p>
 */
public enum PageAddressOrder {

    /** {@code page_mid, page_low, 0}：16 位页地址，高、低在前，末字节置零。 */
    LOW_HIGH_ZERO,

    /** {@code page_mid, page_low, page_high}：PRO 的 24 位「中、低、高」布局。 */
    MID_LOW_HIGH
}
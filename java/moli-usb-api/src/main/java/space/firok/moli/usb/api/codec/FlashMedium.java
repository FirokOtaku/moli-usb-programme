package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：外部存储介质类型，决定 {@code 03 04} 的参数布局。
 *
 * <p>串行 Flash（LITE）与 NAND（PRO）共用子命令 {@code 0x04} 但布局冲突，所以路由
 * 必须依据已确认的介质能力，不能只按型号名称猜测。</p>
 */
public enum FlashMedium {

    /** 串行 Flash：{@code 03 04 start_page_u16 00 00}。 */
    SERIAL_FLASH,

    /** NAND：{@code 03 04 00 block_u16 00}。 */
    NAND
}
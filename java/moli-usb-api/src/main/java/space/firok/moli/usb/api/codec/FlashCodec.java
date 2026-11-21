package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：Flash / NAND 命令 {@code 0x03} 的帧构造。
 *
 * <p>规范：spec/msu2-wire-protocol.md「Flash 与暂存区」。</p>
 *
 * <pre>{@code
 * 03 00 address_u24_be 00             读一个字节；响应 byte 5
 * 03 01 page_u24_be page_count         暂存区写入指定页
 * 03 02 start_page_u16_be page_count_u16_be  擦除连续页
 * 03 03 page_u24_be page_count         快速写（带擦除）
 * 03 04 start_page_u16_be 00 00        串行 Flash 高速写起点（LITE）
 * 03 04 00 block_u16_be 00             NAND 高速写起点（PRO，布局冲突）
 * 03 05 00 00 00 00                    芯片擦除
 * 03 06 block_u16_be 00 00             擦除 NAND 块
 * }</pre>
 *
 * <p>子命令 {@code 0x04} 在两种介质上参数布局不同，因此 {@link #highSpeedWriteStart}
 * 必须显式接收介质类型；不提供「猜一个」的重载。</p>
 */
public final class FlashCodec {

    private FlashCodec() {
    }

    /** {@code 03 00 addr_u24_be 00}：读取一个字节。 */
    public static Frame readByte(int address) {
        Frame.checkU24("address", address);
        return Frame.of(Commands.FLASH, FlashSub.READ_BYTE,
                (address >>> 16) & 0xFF, (address >>> 8) & 0xFF, address & 0xFF, 0);
    }

    /** {@code 03 01 page_u24_be page_count}：把暂存区写入指定页起若干页。 */
    public static Frame writePagesFromScratch(int page, int pageCount) {
        Frame.checkU24("page", page);
        Frame.checkByte("pageCount", pageCount);
        if (pageCount == 0) {
            throw new InvalidArgumentError("FlashCodec.writePagesFromScratch", null,
                    "pageCount 必须大于 0");
        }
        return Frame.of(Commands.FLASH, FlashSub.WRITE_PAGES_FROM_SCRATCH,
                (page >>> 16) & 0xFF, (page >>> 8) & 0xFF, page & 0xFF, pageCount);
    }

    /** {@code 03 02 start_page_u16_be page_count_u16_be}：擦除连续页。 */
    public static Frame erasePages(int startPage, int pageCount) {
        Frame.checkU16("startPage", startPage);
        Frame.checkU16("pageCount", pageCount);
        if (pageCount == 0) {
            throw new InvalidArgumentError("FlashCodec.erasePages", null, "pageCount 必须大于 0");
        }
        return Frame.ofU16Pair(Commands.FLASH, FlashSub.ERASE_PAGES, startPage, pageCount);
    }

    /** {@code 03 03 page_u24_be page_count}：快速写（写前自动擦除）。 */
    public static Frame writePagesFast(int page, int pageCount) {
        Frame.checkU24("page", page);
        Frame.checkByte("pageCount", pageCount);
        if (pageCount == 0) {
            throw new InvalidArgumentError("FlashCodec.writePagesFast", null, "pageCount 必须大于 0");
        }
        return Frame.of(Commands.FLASH, FlashSub.WRITE_PAGES_FAST,
                (page >>> 16) & 0xFF, (page >>> 8) & 0xFF, page & 0xFF, pageCount);
    }

    /**
     * {@code 03 04 ...}：配置连续高速写起点。
     *
     * <p>布局由介质决定，两条路径的参数位置不同：</p>
     *
     * <ul>
     *   <li>串行 Flash：{@code 03 04 start_page_u16_be 00 00}；</li>
     *   <li>NAND：{@code 03 04 00 block_u16_be 00}。</li>
     * </ul>
     */
    public static Frame highSpeedWriteStart(FlashMedium medium, int start) {
        if (medium == null) {
            throw new InvalidArgumentError("FlashCodec.highSpeedWriteStart", null,
                    "medium 不能为 null；0x03/0x04 的参数布局依赖介质，不能推测");
        }
        return switch (medium) {
            case SERIAL_FLASH -> {
                Frame.checkU16("startPage", start);
                yield Frame.of(Commands.FLASH, FlashSub.HIGH_SPEED_WRITE_START,
                        (start >>> 8) & 0xFF, start & 0xFF, 0, 0);
            }
            case NAND -> {
                Frame.checkU16("startBlock", start);
                yield Frame.of(Commands.FLASH, FlashSub.HIGH_SPEED_WRITE_START,
                        0, (start >>> 8) & 0xFF, start & 0xFF, 0);
            }
        };
    }

    /** {@code 03 05 00 00 00 00}：芯片擦除。 */
    public static Frame chipErase() {
        return Frame.of(Commands.FLASH, FlashSub.CHIP_ERASE, 0, 0, 0, 0);
    }

    /** {@code 03 06 block_u16_be 00 00}：擦除 NAND 块。 */
    public static Frame eraseNandBlock(int block) {
        Frame.checkU16("block", block);
        return Frame.of(Commands.FLASH, FlashSub.ERASE_NAND_BLOCK,
                (block >>> 8) & 0xFF, block & 0xFF, 0, 0);
    }
}
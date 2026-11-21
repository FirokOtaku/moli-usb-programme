package space.firok.moli.usb.serial;

import space.firok.moli.usb.api.error.InvalidArgumentError;

import java.util.Locale;
import java.util.Objects;

/**
 * USB 标识对（VID/PID）。
 *
 * <p><b>待实机确认：</b>本仓库 spec 尚未记录 MSU2 任一机型的 VID/PID，因此本类型不提供
 * 任何内置常量，只用于表达"调用方在实机确认后自行填入"的候选列表。</p>
 *
 * @param vendorId  USB Vendor ID（0..0xFFFF）
 * @param productId USB Product ID（0..0xFFFF）
 */
public record UsbId(int vendorId, int productId) {

    public UsbId {
        if (vendorId < 0 || vendorId > 0xFFFF) {
            throw new InvalidArgumentError("UsbId", null,
                    "vendorId 必须落在 0..65535（实际 " + vendorId + "）");
        }
        if (productId < 0 || productId > 0xFFFF) {
            throw new InvalidArgumentError("UsbId", null,
                    "productId 必须落在 0..65535（实际 " + productId + "）");
        }
    }

    /**
     * 解析 {@code VID:PID} 文本，支持 {@code 0x1A86:0x7523}、{@code 1A86:7523}
     * 与十进制三种写法。
     *
     * <p>只解析调用方显式提供的值：不接受空串或单值，避免把端口名误当成 GPU id。</p>
     */
    public static UsbId parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator != trimmed.lastIndexOf(':')
                || separator == trimmed.length() - 1) {
            throw new InvalidArgumentError("UsbId", null,
                    "USB 标识必须形如 VID:PID（实际 " + text + "）");
        }
        return new UsbId(parseComponent(trimmed.substring(0, separator), text),
                parseComponent(trimmed.substring(separator + 1), text));
    }

    private static int parseComponent(String component, String original) {
        String value = component.trim();
        try {
            int parsed;
            if (value.startsWith("0x") || value.startsWith("0X")) {
                parsed = Integer.parseInt(value.substring(2), 16);
            } else if (value.matches("[0-9A-Fa-f]{4}")) {
                // 四位且全为十六进制字符：按 USB 文档的常见写法当十六进制读。
                parsed = Integer.parseInt(value, 16);
            } else {
                parsed = Integer.parseInt(value);
            }
            if (parsed < 0 || parsed > 0xFFFF) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException malformed) {
            throw new InvalidArgumentError("UsbId", null,
                    "无法解析 USB 标识 " + original + " 的组成部分: " + component);
        }
    }

    /** 规范文本，如 {@code 1A86:7523}。 */
    public String text() {
        return String.format(Locale.ROOT, "%04X:%04X", vendorId, productId);
    }

    @Override
    public String toString() {
        return text();
    }
}
package space.firok.moli.usb.serial.probe;

import space.firok.moli.usb.serial.PortDescriptor;
import space.firok.moli.usb.serial.UsbId;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code MoliProbe list} 的输出格式化。
 *
 * <p>与枚举、硬件解耦以便测试：输入是纯元数据列表，输出是纯文本。识别结论只基于
 * VID/PID（spec/verification.md P0：不得仅凭端口名判定），未配置候选时一律标注
 * 「未识别」并在标题下显著提示 VID/PID 待实机确认。</p>
 */
public final class PortListFormatter {

    /** 待实机确认的固定提示，单独提取便于测试断言与后续替换。 */
    public static final String USB_ID_UNCONFIRMED_NOTICE =
            "注意：VID/PID 待实机确认 —— 本仓库 spec 尚未记录 MSU2 各机型的 USB 标识，"
                    + "未配置候选时下列端口一律标注为「未识别」。";

    private PortListFormatter() {
    }

    /**
     * 生成完整输出。
     *
     * @param descriptors 枚举结果
     * @param usbIds      候选 VID/PID；空表示不按 USB 标识过滤
     */
    public static String format(List<PortDescriptor> descriptors, List<UsbId> usbIds) {
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(usbIds, "usbIds");

        StringBuilder out = new StringBuilder();
        out.append("== 串口枚举 ==").append(System.lineSeparator());
        out.append(USB_ID_UNCONFIRMED_NOTICE).append(System.lineSeparator());
        if (usbIds.isEmpty()) {
            out.append("筛选：未配置 VID/PID 候选，列出全部串口。")
                    .append(System.lineSeparator());
        } else {
            List<String> ids = new ArrayList<>();
            for (UsbId id : usbIds) {
                ids.add(id.text());
            }
            out.append("筛选：候选 VID/PID = ").append(String.join(", ", ids))
                    .append("；端口名不参与判定。").append(System.lineSeparator());
        }
        out.append(System.lineSeparator());

        if (descriptors.isEmpty()) {
            out.append("没有发现任何串口。").append(System.lineSeparator());
            out.append("若设备已连接，请检查 USB 线缆、驱动绑定与端口是否被其它程序占用。")
                    .append(System.lineSeparator());
            return out.toString();
        }

        for (PortDescriptor descriptor : descriptors) {
            out.append(descriptor.portName()).append(System.lineSeparator());
            out.append("  描述    ").append(descriptor.descriptionText())
                    .append(System.lineSeparator());
            out.append("  厂商    ").append(blankAs(descriptor.manufacturer(), "—"))
                    .append(System.lineSeparator());
            out.append("  VID/PID ").append(descriptor.usbIdText())
                    .append("  (VID=").append(descriptor.vendorIdText())
                    .append(", PID=").append(descriptor.productIdText()).append(')')
                    .append(System.lineSeparator());
            out.append("  序列号  ").append(blankAs(descriptor.serialNumber(), "—"))
                    .append(System.lineSeparator());
            out.append("  识别    ").append(recognitionText(descriptor, usbIds))
                    .append(System.lineSeparator());
        }
        out.append(System.lineSeparator())
                .append("共 ").append(descriptors.size()).append(" 个串口。")
                .append(System.lineSeparator());
        return out.toString();
    }

    /** 单个端口的识别结论。 */
    static String recognitionText(PortDescriptor descriptor, List<UsbId> usbIds) {
        if (usbIds.isEmpty()) {
            return "未识别（未配置 VID/PID 候选，无法确认是否为 MSU2）";
        }
        if (descriptor.vendorId().isEmpty() || descriptor.productId().isEmpty()) {
            return "未识别（端口未报告 USB VID/PID）";
        }
        int vendor = descriptor.vendorId().get();
        int product = descriptor.productId().get();
        for (UsbId id : usbIds) {
            if (id.vendorId() == vendor && id.productId() == product) {
                return "候选（命中 VID/PID 候选列表，仍需握手确认）";
            }
        }
        return "未识别（VID/PID 不在候选列表内）";
    }

    private static String blankAs(String value, String placeholder) {
        return value == null || value.isBlank() ? placeholder : value;
    }
}
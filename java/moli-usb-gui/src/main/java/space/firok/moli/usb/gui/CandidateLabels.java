package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.domain.DeviceCandidate;

/**
 * 串口下拉框的显示文本。
 *
 * <p>VID/PID 用 {@link DeviceCandidate.OptionalInt}，不是 {@code java.util.OptionalInt}。
 * 缺元数据时仍显示端口：实机 VID/PID 未确认，不能据此隐藏端口。</p>
 */
final class CandidateLabels {

    private CandidateLabels() {
    }

    static String label(DeviceCandidate candidate) {
        StringBuilder text = new StringBuilder(candidate.displayName());
        DeviceCandidate.OptionalInt vendorId = candidate.usbVendorId();
        DeviceCandidate.OptionalInt productId = candidate.usbProductId();
        if (vendorId.isPresent() || productId.isPresent()) {
            text.append(" (");
            text.append(vendorId.isPresent() ? String.format("VID %04X", vendorId.asInt()) : "VID ?");
            text.append(" / ");
            text.append(productId.isPresent() ? String.format("PID %04X", productId.asInt()) : "PID ?");
            text.append(")");
        }
        return text.toString();
    }
}

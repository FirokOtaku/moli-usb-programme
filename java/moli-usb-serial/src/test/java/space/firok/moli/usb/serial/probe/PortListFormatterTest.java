package space.firok.moli.usb.serial.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.firok.moli.usb.serial.PortDescriptor;
import space.firok.moli.usb.serial.UsbId;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code list} 输出格式测试：只依赖纯元数据，不触碰硬件。
 */
class PortListFormatterTest {

    private static PortDescriptor port(String name, Integer vid, Integer pid) {
        return new PortDescriptor(name, "USB 串行设备 (COM" + name + ")", "wch.cn", "SN-1",
                Optional.ofNullable(vid), Optional.ofNullable(pid));
    }

    @Test
    @DisplayName("无串口时输出提示而不是空串")
    void emptyListStillPrintsNotice() {
        String text = PortListFormatter.format(List.of(), List.of());

        assertTrue(text.contains("没有发现任何串口"));
        assertTrue(text.contains(PortListFormatter.USB_ID_UNCONFIRMED_NOTICE));
    }

    @Test
    @DisplayName("未配置候选时所有端口标注未识别，并显著提示 VID/PID 待确认")
    void unfilteredPortsAreMarkedUnrecognized() {
        String text = PortListFormatter.format(
                List.of(port("COM3", 0x1234, 0x5678)), List.of());

        assertTrue(text.contains("COM3"));
        assertTrue(text.contains("1A86:7523") || text.contains("1234:5678"));
        assertTrue(text.contains("未识别"));
        assertTrue(text.contains("VID/PID 待实机确认"));
        assertTrue(text.contains("未配置 VID/PID 候选"));
    }

    @Test
    @DisplayName("配置候选后命中的端口标为候选，未命中的标为未识别")
    void filteredPortsAreClassified() {
        List<UsbId> candidates = List.of(new UsbId(0x1234, 0x5678));
        String text = PortListFormatter.format(
                List.of(port("COM3", 0x1234, 0x5678), port("COM4", 0x9999, 0x0001)),
                candidates);

        assertTrue(text.contains("候选（命中 VID/PID 候选列表"));
        assertTrue(text.contains("未识别（VID/PID 不在候选列表内）"));
        assertTrue(text.contains("1234:5678"));
    }

    @Test
    @DisplayName("端口未报告 VID/PID 时说明原因")
    void missingIdsExplained() {
        String text = PortListFormatter.format(List.of(port("COM9", null, null)),
                List.of(new UsbId(0x1234, 0x5678)));

        assertTrue(text.contains("未识别（端口未报告 USB VID/PID）"));
        assertTrue(text.contains("—"));
        assertFalse(text.contains("候选（命中"));
    }

    @Test
    @DisplayName("末尾打印端口总数")
    void printsTotalCount() {
        String text = PortListFormatter.format(
                List.of(port("COM3", 1, 2), port("COM4", 3, 4)), List.of());

        assertTrue(text.contains("共 2 个串口。"));
    }
}
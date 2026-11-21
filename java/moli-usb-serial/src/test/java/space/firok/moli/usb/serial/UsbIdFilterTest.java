package space.firok.moli.usb.serial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * USB 标识值对象与 VID/PID 过滤逻辑测试。
 *
 * <p>用假的元数据对象验证规则，不依赖真实串口。特别确认：空候选列表表示不过滤，
 * 端口名不参与判定——这与 spec/verification.md 的 P0 要求一致。</p>
 */
class UsbIdFilterTest {

    private static PortDescriptor port(String name, Integer vid, Integer pid) {
        return new PortDescriptor(name, "USB 串行设备", "厂商", "",
                Optional.ofNullable(vid), Optional.ofNullable(pid));
    }

    @Test
    @DisplayName("空候选列表表示不过滤，任何端口都匹配")
    void emptyCandidateListMeansNoFiltering() {
        PortDescriptor descriptor = port("COM3", 0x1234, 0x5678);

        assertTrue(SerialDeviceProvider.matches(descriptor, List.of()));
        assertTrue(SerialDeviceProvider.matches(port("COM4", null, null), List.of()));
    }

    @Test
    @DisplayName("配置候选后必须 VID 与 PID 成对命中")
    void candidatesRequireBothIds() {
        List<UsbId> candidates = List.of(new UsbId(0x1234, 0x5678));

        assertTrue(SerialDeviceProvider.matches(port("COM3", 0x1234, 0x5678), candidates));
        assertFalse(SerialDeviceProvider.matches(port("COM3", 0x1234, 0x0001), candidates));
        assertFalse(SerialDeviceProvider.matches(port("COM3", 0x0001, 0x5678), candidates));
    }

    @Test
    @DisplayName("端口名不参与判定：名字像设备但 VID/PID 不符仍不匹配")
    void portNameIsNotEvidence() {
        List<UsbId> candidates = List.of(new UsbId(0x1234, 0x5678));

        assertFalse(SerialDeviceProvider.matches(port("MSU2", 0x9999, 0x9999), candidates));
        assertTrue(SerialDeviceProvider.matches(port("COM9", 0x1234, 0x5678), candidates));
    }

    @Test
    @DisplayName("缺少 VID/PID 的端口在配置候选后不匹配")
    void missingIdsNeverMatchWhenFiltering() {
        List<UsbId> candidates = List.of(new UsbId(0x1234, 0x5678));

        assertFalse(SerialDeviceProvider.matches(port("COM3", null, null), candidates));
        assertFalse(SerialDeviceProvider.matches(port("COM3", 0x1234, null), candidates));
    }

    @Test
    @DisplayName("UsbId 解析支持十六进制前缀与四位写法，拒绝非法形状")
    void usbIdParsing() {
        assertEquals(new UsbId(0x1A86, 0x7523), UsbId.parse("0x1A86:0x7523"));
        assertEquals(new UsbId(0x1A86, 0x7523), UsbId.parse("1A86:7523"));
        assertEquals(new UsbId(0x1A86, 0x7523), UsbId.parse("  0X1a86 : 7523 "));
        assertEquals(new UsbId(0x0001, 0x0002), UsbId.parse("1:2"));
        assertEquals("1A86:7523", UsbId.parse("1A86:7523").text());

        assertThrows(RuntimeException.class, () -> UsbId.parse("1A86"));
        assertThrows(RuntimeException.class, () -> UsbId.parse("1A86:"));
        assertThrows(RuntimeException.class, () -> UsbId.parse("1A86:7523:0001"));
        assertThrows(RuntimeException.class, () -> UsbId.parse("xyz:7523"));
        assertThrows(RuntimeException.class, () -> UsbId.parse("70000:1"));
    }

    @Test
    @DisplayName("PortDescriptor 的 USB 文本在缺失时用占位符")
    void portDescriptorTexts() {
        PortDescriptor known = port("COM3", 0x1A86, 0x7523);
        assertEquals("1A86:7523", known.usbIdText());
        assertEquals("1A86", known.vendorIdText());
        assertEquals("7523", known.productIdText());

        PortDescriptor unknown = PortDescriptor.ofName("COM4");
        assertEquals("—", unknown.usbIdText());
        assertEquals("—", unknown.vendorIdText());
        assertEquals("（无描述）", unknown.descriptionText());
    }

    @Test
    @DisplayName("候选构造用序列号做 uid，缺失时退回端口名")
    void candidateUidFallsBackToPortName() {
        SerialDeviceProvider provider = SerialDeviceProvider.over(
                List::of, List.of(), SerialPortConfig.of(19200, false, false));

        PortDescriptor withSerial = new PortDescriptor("COM3", "设备", "厂商", "SN-1",
                Optional.of(0x1234), Optional.of(0x5678));
        assertEquals("SN-1", provider.toCandidate(withSerial).id().uid());
        assertEquals("COM3", provider.toCandidate(withSerial).id().transportKey());

        PortDescriptor noSerial = port("COM7", 0x1234, 0x5678);
        assertEquals("COM7", provider.toCandidate(noSerial).id().uid());
    }

    @Test
    @DisplayName("listCandidates 按候选列表过滤注入的假元数据")
    void listCandidatesFiltersInjectedDescriptors() {
        List<PortDescriptor> injected = List.of(
                port("COM3", 0x1234, 0x5678),
                port("COM4", 0x9999, 0x0001),
                port("COM5", null, null));

        SerialDeviceProvider unfiltered = SerialDeviceProvider.over(
                () -> injected, List.of(), SerialPortConfig.of(19200, false, false));
        assertEquals(3, unfiltered.listCandidates().size());

        SerialDeviceProvider filtered = SerialDeviceProvider.over(
                () -> injected, List.of(new UsbId(0x1234, 0x5678)),
                SerialPortConfig.of(19200, false, false));
        assertEquals(1, filtered.listCandidates().size());
        assertEquals("COM3", filtered.listCandidates().getFirst().id().transportKey());
    }
}
package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.serial.SerialDeviceProvider;
import space.firok.moli.usb.serial.SerialPortConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 确认打开路径使用的串口参数来自选中 profile，且默认不按 VID/PID 过滤。
 * 不调用 {@code listCandidates}/{@code open}，因此不会打开端口。
 */
class SerialDeviceSessionGatewayTest {

    @Test
    @DisplayName("provider 默认参数等于 profile.serial()，空 VID/PID 列表表示不过滤")
    void baudAndFlowControlComeFromSelectedProfile() {
        SerialDeviceProvider mini = SerialDeviceSessionGateway.providerFor(DeviceProfile.mini());
        SerialPortConfig miniConfig = SerialPortConfig.from(DeviceProfile.mini().serial());
        assertEquals(miniConfig, mini.defaultConfig());
        assertEquals(19200, mini.defaultConfig().baudRate());
        assertFalse(mini.defaultConfig().rtscts());
        assertFalse(mini.hasUsbIdCandidates());

        SerialDeviceProvider pro = SerialDeviceSessionGateway.providerFor(DeviceProfile.pro());
        assertEquals(SerialPortConfig.from(DeviceProfile.pro().serial()), pro.defaultConfig());
        assertEquals(921600, pro.defaultConfig().baudRate());
        assertTrue(pro.defaultConfig().rtscts());
        assertFalse(pro.hasUsbIdCandidates());

        SerialDeviceProvider lite = SerialDeviceSessionGateway.providerFor(DeviceProfile.lite());
        assertEquals(921600, lite.defaultConfig().baudRate());
        assertTrue(lite.defaultConfig().rtscts());

        SerialDeviceProvider generic = SerialDeviceSessionGateway.providerFor(DeviceProfile.generic());
        assertEquals(19200, generic.defaultConfig().baudRate());
        assertFalse(generic.defaultConfig().rtscts());
        assertTrue(generic.acceptedUsbIds().isEmpty());
    }
}

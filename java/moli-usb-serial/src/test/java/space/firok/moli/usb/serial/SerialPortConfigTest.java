package space.firok.moli.usb.serial;

import com.fazecast.jSerialComm.SerialPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.firok.moli.usb.api.domain.DeviceProfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SerialPortConfig} 的参数映射测试。
 *
 * <p>这些测试只验证「领域层串口参数 -> jSerialComm 常量」的翻译，不打开任何端口。</p>
 */
class SerialPortConfigTest {

    @Test
    @DisplayName("旧版 19200 无流控映射为 8N1 且流控位为 0")
    void legacySettingsMapToNoFlowControl() {
        SerialPortConfig config = SerialPortConfig.from(DeviceProfile.LEGACY_SERIAL);

        assertEquals(19200, config.baudRate());
        assertEquals(8, config.dataBits());
        assertEquals(SerialPort.ONE_STOP_BIT, config.stopBits());
        assertEquals(SerialPort.NO_PARITY, config.parity());
        assertEquals(SerialPort.FLOW_CONTROL_DISABLED, config.flowControl());
        assertFalse(config.rtscts());
        assertFalse(config.xonxoff());
    }

    @Test
    @DisplayName("高速 profile 的 rtscts 同时置位 RTS 与 CTS")
    void highSpeedSettingsEnableBothRtsAndCts() {
        SerialPortConfig config = SerialPortConfig.from(DeviceProfile.HIGH_SPEED_SERIAL);

        assertEquals(921600, config.baudRate());
        assertEquals(SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED,
                config.flowControl());
        assertTrue(config.rtscts());
        assertFalse(config.xonxoff());
    }

    @Test
    @DisplayName("xonxoff 只置位软件流控两个方向")
    void xonxoffFlags() {
        SerialPortConfig config = SerialPortConfig.of(19200, false, true);

        assertEquals(SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                        | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED,
                config.flowControl());
        assertTrue(config.xonxoff());
        assertFalse(config.rtscts());
    }

    @Test
    @DisplayName("硬件与软件流控可以同时启用")
    void bothFlowControlsCombine() {
        SerialPortConfig config = SerialPortConfig.of(921600, true, true);

        assertTrue(config.rtscts());
        assertTrue(config.xonxoff());
        assertEquals(SerialPort.FLOW_CONTROL_RTS_ENABLED
                        | SerialPort.FLOW_CONTROL_CTS_ENABLED
                        | SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                        | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED,
                config.flowControl());
    }

    @Test
    @DisplayName("describe 输出包含波特率、8N1 与启用的流控")
    void describeMentionsSettings() {
        assertEquals("19200 8N1 无流控", SerialPortConfig.of(19200, false, false).describe());
        assertEquals("921600 8N1 rtscts", SerialPortConfig.of(921600, true, false).describe());
        assertEquals("115200 8N1 xonxoff", SerialPortConfig.of(115200, false, true).describe());
    }

    @Test
    @DisplayName("拒绝非正波特率与非 8 位数据")
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> SerialPortConfig.of(0, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new SerialPortConfig(19200, 7, SerialPort.ONE_STOP_BIT,
                        SerialPort.NO_PARITY, 0));
    }
}
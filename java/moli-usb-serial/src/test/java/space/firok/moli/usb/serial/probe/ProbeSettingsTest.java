package space.firok.moli.usb.serial.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.serial.SerialPortConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行波特率/流控/profile 到串口配置的推导测试。
 */
class ProbeSettingsTest {

    @Test
    @DisplayName("19200 默认无流控，921600 默认启用 rtscts")
    void defaultsFollowKnownProfiles() {
        assertFalse(ProbeSettings.resolve(19200, null, false, null).rtscts());
        assertTrue(ProbeSettings.resolve(921600, null, false, null).rtscts());
        // 未在 spec 中记录的波特率默认不加硬件流控，避免凭空启用。
        assertFalse(ProbeSettings.resolve(57600, null, false, null).rtscts());
    }

    @Test
    @DisplayName("--rtscts / --no-rtscts 覆盖推导结果")
    void explicitOverrideWins() {
        assertTrue(ProbeSettings.resolve(19200, Boolean.TRUE, false, null).rtscts());
        assertFalse(ProbeSettings.resolve(921600, Boolean.FALSE, false, null).rtscts());
    }

    @Test
    @DisplayName("xonxoff 独立于 rtscts")
    void xonxoffIndependent() {
        SerialPortConfig config = ProbeSettings.resolve(921600, null, true, null);
        assertTrue(config.rtscts());
        assertTrue(config.xonxoff());
    }

    @Test
    @DisplayName("显式 profile 且波特率一致时采用该 profile 的流控记录")
    void explicitProfileContributesFlowControl() {
        // LITE/PRO 的 HIGH_SPEED_SERIAL 是 rtscts=true。
        assertTrue(ProbeSettings.resolve(921600, null, false, "PRO").rtscts());
        // MINI 的 LEGACY_SERIAL 是 rtscts=false。
        assertFalse(ProbeSettings.resolve(19200, null, false, "MINI").rtscts());
        // 波特率与 profile 不一致时退回按已知高速波特率判断，而不是照抄 profile。
        assertFalse(ProbeSettings.resolve(19200, null, false, "PRO").rtscts());
    }

    @Test
    @DisplayName("未指定 profile 时用通用 profile，指定时按名取")
    void profileSelection() {
        assertEquals(DeviceProfile.GENERIC, ProbeSettings.profile(null).name());
        assertEquals(DeviceProfile.GENERIC, ProbeSettings.profile("  ").name());
        assertEquals(DeviceProfile.MINI, ProbeSettings.profile("mini").name());
        assertEquals(DeviceProfile.PRO, ProbeSettings.profile("PRO").name());
    }
}
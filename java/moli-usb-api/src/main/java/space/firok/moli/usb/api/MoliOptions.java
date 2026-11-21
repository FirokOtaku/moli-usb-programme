package space.firok.moli.usb.api;

import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.session.DeviceSessionRegistry;
import space.firok.moli.usb.api.session.SessionSettings;

import java.util.Objects;

/**
 * 领域 API：打开设备时的可选参数。
 *
 * <p>为什么需要显式 profile：规范（spec/architecture.md「兼容策略」）禁止只按型号名
 * 猜测串口参数与页地址布局。宿主应用在确认 USB VID/PID 或固件身份后选择 profile，
 * 本库据此决定显示几何、能力集默认值和 Flash 彩色页地址顺序。</p>
 *
 * <p>未提供 profile 时使用 {@link DeviceProfile#generic()}：几何与能力依赖设备自描述
 * 报告，缺少报告时相关能力按「不支持」处理，不会猜。</p>
 */
public record MoliOptions(
        DeviceProfile profile,
        SessionSettings sessionSettings,
        DeviceSessionRegistry registry,
        boolean probeSelfDescription) {

    /** 默认：通用 profile、保守超时、进程级占用登记表、尝试读取自描述表。 */
    public static MoliOptions defaults() {
        return new MoliOptions(
                DeviceProfile.generic(),
                SessionSettings.defaults(),
                DeviceSessionRegistry.global(),
                true);
    }

    public MoliOptions {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(sessionSettings, "sessionSettings");
        Objects.requireNonNull(registry, "registry");
    }

    public MoliOptions withProfile(DeviceProfile profile) {
        return new MoliOptions(profile, sessionSettings, registry, probeSelfDescription);
    }

    public MoliOptions withSessionSettings(SessionSettings settings) {
        return new MoliOptions(profile, settings, registry, probeSelfDescription);
    }

    public MoliOptions withRegistry(DeviceSessionRegistry registry) {
        return new MoliOptions(profile, sessionSettings, registry, probeSelfDescription);
    }

    /** 关闭自描述表读取（测试或库外已确认信息时使用）。 */
    public MoliOptions withoutSelfDescriptionProbe() {
        return new MoliOptions(profile, sessionSettings, registry, false);
    }
}
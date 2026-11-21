package space.firok.moli.usb.serial.probe;

import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.serial.SerialPortConfig;

/**
 * 把命令行给出的波特率与流控开关解析成 {@link SerialPortConfig}。
 *
 * <p>默认流控取值遵循 spec 已记录的 profile 常量，而不是凭空决定：</p>
 *
 * <ul>
 *   <li>显式 {@code --profile} 时，使用该 profile 的 {@code SerialSettings}；</li>
 *   <li>未指定 profile 时，波特率与 {@link DeviceProfile#HIGH_SPEED_SERIAL} 相同的用
 *       rtscts=true，否则用 {@link DeviceProfile#LEGACY_SERIAL} 的 rtscts=false；</li>
 *   <li>{@code --rtscts} / {@code --no-rtscts} 覆盖上述任何结果。</li>
 * </ul>
 *
 * <p>8N1 与 profile 串口参数同属 spec/verification.md 待实机确认项，工具只忠实翻译。</p>
 */
public final class ProbeSettings {

    private ProbeSettings() {
    }

    /**
     * 解析串口配置。
     *
     * @param baudRate 命令行波特率
     * @param rtscts   显式流控覆盖；{@code null} 表示按 profile 推定
     * @param xonxoff  是否启用 XON/XOFF
     * @param profile  显式 profile 名；{@code null} 表示未指定
     */
    public static SerialPortConfig resolve(int baudRate, Boolean rtscts, boolean xonxoff,
                                           String profile) {
        boolean rtsctsDefault;
        if (profile != null && !profile.isBlank()) {
            DeviceProfile known = DeviceProfile.byName(profile);
            if (known.serial().baudRate() == baudRate) {
                // 波特率与 profile 一致时，采用该 profile 已记录的流控事实。
                rtsctsDefault = known.serial().rtscts();
            } else {
                rtsctsDefault = baudRate == DeviceProfile.HIGH_SPEED_SERIAL.baudRate();
            }
        } else {
            rtsctsDefault = baudRate == DeviceProfile.HIGH_SPEED_SERIAL.baudRate();
        }
        boolean rtsctsEnabled = rtscts == null ? rtsctsDefault : rtscts;
        return SerialPortConfig.of(baudRate, rtsctsEnabled, xonxoff);
    }

    /**
     * 选择 profile：显式指定时按名字取，否则用 {@link DeviceProfile#generic()}。
     *
     * <p>通用 profile 不提供几何，因此 {@code fill} 在设备未自描述宽高时会明确要求
     * 用户指定 {@code --profile}，而不是猜分辨率。</p>
     */
    public static DeviceProfile profile(String name) {
        if (name == null || name.isBlank()) {
            return DeviceProfile.generic();
        }
        return DeviceProfile.byName(name);
    }
}
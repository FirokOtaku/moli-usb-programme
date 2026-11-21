package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.MoliOptions;
import space.firok.moli.usb.api.MoliUsb;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.serial.SerialDeviceProvider;
import space.firok.moli.usb.serial.SerialPortConfig;

import java.util.List;
import java.util.Objects;

/**
 * 串口版 {@link DeviceSessionGateway}。
 *
 * <p>{@code MoliOptions.withProfile} 只影响握手后的几何与能力默认值，不会改
 * {@link SerialDeviceProvider#open} 使用的波特率与 RTS/CTS。那份参数来自 provider
 * 的 {@code defaultConfig}，所以打开前必须按用户选中的 profile 重新构造 provider。</p>
 *
 * <p>VID/PID 尚未确认。枚举使用 {@code new SerialDeviceProvider()}，列出全部串口，
 * 不做过滤。</p>
 */
public final class SerialDeviceSessionGateway implements DeviceSessionGateway {

    @Override
    public List<DeviceCandidate> listCandidates() {
        return new SerialDeviceProvider().listCandidates();
    }

    @Override
    public MoliDevice open(DeviceCandidate candidate, DeviceProfile profile) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(profile, "profile");
        SerialDeviceProvider provider = providerFor(profile);
        MoliUsb api = MoliUsb.over(provider);
        return api.open(candidate, MoliOptions.defaults().withProfile(profile));
    }

    /**
     * 按 profile 的串口参数构造不过滤 VID/PID 的 provider。
     * 打开路径必须走这里，不能只把 profile 交给 {@link MoliOptions}。
     */
    static SerialDeviceProvider providerFor(DeviceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new SerialDeviceProvider()
                .withDefaultConfig(SerialPortConfig.from(profile.serial()));
    }
}

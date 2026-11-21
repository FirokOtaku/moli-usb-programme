package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceProfile;

import java.util.List;

/**
 * 连接控制器使用的设备入口。
 *
 * <p>把串口实现挡在控制器外面，使状态机可以在没有串口、也没有 JavaFX 的情况下测试。
 * 真实实现见 {@link SerialDeviceSessionGateway}：波特率与 RTS/CTS 来自用户选中的
 * profile，而不是只靠 {@code MoliOptions.withProfile}。</p>
 */
public interface DeviceSessionGateway {

    /** 枚举当前可见的候选。不得在 JavaFX 线程调用。 */
    List<DeviceCandidate> listCandidates();

    /**
     * 按选中的 profile 打开一台设备并完成握手。
     *
     * <p>实现必须把 {@code profile.serial()} 写进 provider 的默认串口参数后再
     * {@code open}。不得在 JavaFX 线程调用。</p>
     */
    MoliDevice open(DeviceCandidate candidate, DeviceProfile profile);
}

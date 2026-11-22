package space.firok.moli.usb.api;

import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.error.DeviceNotFoundError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.session.CancellationToken;
import space.firok.moli.usb.api.session.DeviceSessionRegistry;
import space.firok.moli.usb.api.transport.Transport;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域 API：对外入口。
 *
 * <p>用法：</p>
 *
 * <pre>{@code
 * MoliUsb api = MoliUsb.over(new MySerialDeviceProvider());
 * List<DeviceCandidate> candidates = api.discover();
 * try (MoliDevice device = api.open(candidates.getFirst(),
 *         MoliOptions.defaults().withProfile(DeviceProfile.mini()))) {
 *     device.fillRect(new Rect(0, 0, 160, 80), Rgb565.BLACK);
 * }
 * }</pre>
 *
 * <p>宿主的真实串口实现通过 {@link DeviceProvider} 注入；本库不包含、也不回传串口对象。
 * 枚举只读元数据，不打开端口。</p>
 */
public final class MoliUsb {

    private final DeviceProvider provider;
    private final Set<MoliDevice> openDevices = ConcurrentHashMap.newKeySet();

    private MoliUsb(DeviceProvider provider) {
        this.provider = provider;
    }

    /** 以给定 provider 构造入口。 */
    public static MoliUsb over(DeviceProvider provider) {
        return new MoliUsb(Objects.requireNonNull(provider, "provider"));
    }

    /** 枚举当前可见的候选设备（不打开任何端口）。 */
    public List<DeviceCandidate> discover() {
        return List.copyOf(provider.listCandidates());
    }

    /** 只返回当前可用的候选。 */
    public List<DeviceCandidate> discoverAvailable() {
        return provider.listCandidates().stream()
                .filter(DeviceCandidate::available)
                .toList();
    }

    /**
     * 打开一个候选设备（默认选项：通用 profile、进程级占用登记表）。
     *
     * @throws DeviceBusyError 同一设备已有其它会话
     */
    public MoliDevice open(DeviceCandidate candidate) {
        return open(candidate, MoliOptions.defaults());
    }

    /**
     * 打开一个候选设备。
     *
     * <p>若候选标记为不可用，先重新枚举一次以确认状态，避免用过期快照打开设备。</p>
     */
    public MoliDevice open(DeviceCandidate candidate, MoliOptions options) {
        return open(candidate, options, CancellationToken.none());
    }

    /** 打开一个候选设备，并允许宿主在握手期间取消。 */
    public MoliDevice open(DeviceCandidate candidate, MoliOptions options,
                           CancellationToken token) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled("open", candidate.id().toString());

        DeviceCandidate resolved = refresh(candidate, options.registry());
        token.throwIfCancelled("open", resolved.id().toString());
        Transport transport = provider.open(resolved);
        try {
            MoliDevice device = MoliDeviceImpl.open(resolved, transport, options, token);
            openDevices.add(device);
            return device;
        } catch (RuntimeException failure) {
            // MoliDeviceImpl.open 已在失败时关闭传输；这里只保证 provider 侧的句柄不泄漏。
            try {
                transport.close();
            } catch (java.io.IOException ignored) {
                // 关闭失败不应掩盖原始失败原因。
            }
            throw failure;
        }
    }

    /** 按 {@link DeviceId} 打开设备。 */
    public MoliDevice open(DeviceId id, MoliOptions options) {
        Objects.requireNonNull(id, "id");
        List<DeviceCandidate> candidates = provider.listCandidates();
        DeviceCandidate candidate = candidates.stream()
                .filter(c -> c.id().uid().equals(id.uid()))
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(c -> c.id().transportKey().equals(id.transportKey()))
                        .findFirst())
                .orElseThrow(() -> new DeviceNotFoundError("open", id.toString(),
                        "枚举结果中没有匹配的设备: " + id));
        return open(candidate, options);
    }

    /** 按设备 ID 打开（默认选项）。 */
    public MoliDevice open(DeviceId id) {
        return open(id, MoliOptions.defaults());
    }

    /**
     * 按条件选择唯一一个候选并打开。
     *
     * <p>多个匹配时报 {@code InvalidArgumentError}，要求提供更精确的选择器，不静默挑
     * 第一个。设备可用性同样先重新确认。</p>
     */
    public MoliDevice open(DeviceProfile.Selector selector, MoliOptions options) {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(options, "options");
        List<DeviceCandidate> matched = provider.listCandidates().stream()
                .filter(selector)
                .toList();
        if (matched.isEmpty()) {
            throw new DeviceNotFoundError("open", null, "没有符合选择条件的设备");
        }
        if (matched.size() > 1) {
            throw new InvalidArgumentError("open", null,
                    "选择条件匹配到 " + matched.size() + " 个设备，请提供更精确的选择器");
        }
        return open(matched.getFirst(), options);
    }

    public MoliDevice open(DeviceProfile.Selector selector) {
        return open(selector, MoliOptions.defaults());
    }

    /** 关闭所有由本入口打开且尚未关闭的设备。 */
    public void closeAll() {
        for (MoliDevice device : List.copyOf(openDevices)) {
            try {
                device.close();
            } finally {
                openDevices.remove(device);
            }
        }
    }

    private DeviceCandidate refresh(DeviceCandidate candidate, DeviceSessionRegistry registry) {
        if (registry.isClaimed(candidate.id().transportKey())) {
            throw new DeviceBusyError("open", candidate.id().toString(),
                    "设备 " + candidate.id().transportKey() + " 已被另一个会话占用");
        }
        if (candidate.available()) {
            return candidate;
        }
        return provider.listCandidates().stream()
                .filter(fresh -> fresh.id().equals(candidate.id()))
                .findFirst()
                .filter(DeviceCandidate::available)
                .orElseThrow(() -> new DeviceNotFoundError("open", candidate.id().toString(),
                        "候选设备当前不可用: " + candidate.displayName()));
    }
}
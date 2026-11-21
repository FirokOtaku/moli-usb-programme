package space.firok.moli.usb.api;

import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.transport.Transport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 领域 API：设备发现与打开的注入点。
 *
 * <p>真实 USB/串口实现不在本库内（spec/architecture.md：传输层可注入）。宿主应用提供
 * 一个 {@code DeviceProvider}，库负责枚举、选择、握手和后续协议操作；领域 API 不回传
 * 串口对象或平台句柄。</p>
 *
 * <p>实现要求：</p>
 *
 * <ul>
 *   <li>{@link #listCandidates()} 只枚举、不打开端口（扫描不得靠打开所有串口再读横幅
 *       认设备）；</li>
 *   <li>{@link #open(DeviceCandidate)} 只打开指定候选，不得在调用方未选择的情况下
 *       打开其它设备；</li>
 *   <li>返回的 {@link Transport} 由调用方（本库）负责关闭。</li>
 * </ul>
 */
public interface DeviceProvider {

    /** 枚举当前可见的候选设备，按实现定义的稳定顺序返回。 */
    List<DeviceCandidate> listCandidates();

    /**
     * 打开一个候选设备的传输通道。
     *
     * @throws space.firok.moli.usb.api.error.DeviceNotFoundError 该候选已不可用
     */
    Transport open(DeviceCandidate candidate);

    /**
     * 按 {@link DeviceId} 找到候选并打开。
     *
     * <p>默认实现按 {@code uid} 优先匹配、{@code transportKey} 兜底匹配；匹配唯一性由
     * 实现保证（例如设备已被拔出时抛出 {@code DeviceNotFoundError}）。</p>
     */
    default Transport open(DeviceId id) {
        Objects.requireNonNull(id, "id");
        List<DeviceCandidate> candidates = listCandidates();
        Optional<DeviceCandidate> byUid = candidates.stream()
                .filter(candidate -> candidate.id().uid().equals(id.uid()))
                .findFirst();
        Optional<DeviceCandidate> chosen = byUid.isPresent()
                ? byUid
                : candidates.stream()
                        .filter(candidate -> candidate.id().transportKey().equals(id.transportKey()))
                        .findFirst();
        DeviceCandidate candidate = chosen.orElseThrow(() -> new space.firok.moli.usb.api.error.DeviceNotFoundError(
                "open", id.toString(), "枚举结果中没有匹配的设备: " + id));
        return open(candidate);
    }

    /**
     * 按条件选择唯一一个候选并打开。
     *
     * <p>没有匹配时抛 {@code DeviceNotFoundError}；多个匹配时抛
     * {@code InvalidArgumentError}，要求调用方给出更精确的选择器——不静默挑第一个。</p>
     */
    default Transport open(Predicate<DeviceCandidate> selector) {
        Objects.requireNonNull(selector, "selector");
        List<DeviceCandidate> matched = listCandidates().stream().filter(selector).toList();
        if (matched.isEmpty()) {
            throw new space.firok.moli.usb.api.error.DeviceNotFoundError(
                    "open", null, "没有符合选择条件的设备");
        }
        if (matched.size() > 1) {
            throw new space.firok.moli.usb.api.error.InvalidArgumentError(
                    "open", null, "选择条件匹配到 " + matched.size()
                            + " 个设备，请提供更精确的选择器");
        }
        return open(matched.getFirst());
    }
}
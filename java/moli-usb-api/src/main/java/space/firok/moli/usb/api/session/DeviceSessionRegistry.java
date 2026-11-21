package space.firok.moli.usb.api.session;

import space.firok.moli.usb.api.error.DeviceBusyError;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话层：已占用设备的登记表，用于「同一设备不允许双开」。
 *
 * <p>键是传输层地址（{@code Transport.address()}）。设备没有请求 ID，同一物理设备
 * 被两个会话同时写入会让响应彻底错配，因此占用必须在打开前完成、关闭时释放。</p>
 *
 * <p>登记表按传输地址去重：不同传输实现的同一个地址视为同一设备，这与 USB 串口只能
 * 被一个进程独占的事实一致。库提供一个进程级默认实例
 * {@link #global()}，同时允许测试或宿主应用注入独立实例。</p>
 */
public final class DeviceSessionRegistry {

    private static final DeviceSessionRegistry GLOBAL = new DeviceSessionRegistry();

    private final ConcurrentMap<String, Object> claims = new ConcurrentHashMap<>();

    /** 进程级默认登记表。 */
    public static DeviceSessionRegistry global() {
        return GLOBAL;
    }

    /**
     * 占用一个传输地址。
     *
     * @param transportKey 传输层地址
     * @param owner        占用者（会话实例），用于防止错误释放他人占用
     * @throws DeviceBusyError 该地址已被其它占用者持有
     */
    public void claim(String transportKey, Object owner) {
        Objects.requireNonNull(transportKey, "transportKey");
        Objects.requireNonNull(owner, "owner");
        Object existing = claims.putIfAbsent(transportKey, owner);
        if (existing != null && existing != owner) {
            throw new DeviceBusyError("open", transportKey,
                    "设备 " + transportKey + " 已被另一个会话占用；同一设备不允许双开");
        }
    }

    /** 释放占用；只有当前持有者才能释放。 */
    public void release(String transportKey, Object owner) {
        if (transportKey == null) {
            return;
        }
        claims.remove(transportKey, owner);
    }

    /** 该地址当前是否被占用。 */
    public boolean isClaimed(String transportKey) {
        return transportKey != null && claims.containsKey(transportKey);
    }

    /** 当前占用的地址数量，供测试与诊断使用。 */
    public int size() {
        return claims.size();
    }
}
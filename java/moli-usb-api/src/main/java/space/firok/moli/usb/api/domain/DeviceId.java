package space.firok.moli.usb.api.domain;

import java.util.Objects;

/**
 * 领域层：设备标识。
 *
 * <p>规范（spec/architecture.md）：优先使用设备报告的 {@code MSN_UID}；传输层地址
 * 只能作为本次枚举的临时标识。本值对象同时携带两者：{@link #uid()} 是稳定标识
 * （设备自描述表读取失败时回退为横幅 {@code MSNxx} 推导的临时标识），
 * {@link #transportKey()} 是本次枚举的临时传输地址。</p>
 *
 * <p>此类型不暴露任何串口对象或平台端口句柄。</p>
 */
public record DeviceId(String uid, String transportKey) {

    public DeviceId {
        Objects.requireNonNull(uid, "uid must not be null");
        Objects.requireNonNull(transportKey, "transportKey must not be null");
        if (uid.isBlank()) {
            throw new IllegalArgumentException("uid must not be blank");
        }
        if (transportKey.isBlank()) {
            throw new IllegalArgumentException("transportKey must not be blank");
        }
    }

    @Override
    public String toString() {
        return uid + "@" + transportKey;
    }
}

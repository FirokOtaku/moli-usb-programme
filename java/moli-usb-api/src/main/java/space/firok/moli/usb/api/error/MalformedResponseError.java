package space.firok.moli.usb.api.error;

import java.util.Optional;

/**
 * 响应格式不正确。enum 分类 {@code MalformedResponse}：设备返回的帧长度、内容
 * 与预期不符。
 */
public class MalformedResponseError extends MoliUsbException {

    private final byte[] rawResponse;

    public MalformedResponseError(String operation, String deviceId, String message) {
        this(operation, deviceId, message, null, null);
    }

    public MalformedResponseError(String operation, String deviceId, String message, byte[] rawResponse) {
        this(operation, deviceId, message, rawResponse, null);
    }

    public MalformedResponseError(String operation, String deviceId, String message,
                                  byte[] rawResponse, Throwable cause) {
        super(operation, deviceId, message, cause);
        this.rawResponse = rawResponse == null ? null : rawResponse.clone();
    }

    /** 原始响应字节（若已捕获）。 */
    public Optional<byte[]> rawResponse() {
        return Optional.ofNullable(rawResponse).map(byte[]::clone);
    }
}

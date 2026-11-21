package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：一次响应的判定结果。
 *
 * <p>除结论外还保留原始字节与请求帧，便于会话层构造
 * {@code MalformedResponseError} / {@code ProtocolDesynchronizedError} 时携带上下文。</p>
 */
public final class ResponseCheck {

    private final ResponseOutcome outcome;
    private final Frame request;
    private final byte[] raw;
    private final String detail;

    private ResponseCheck(ResponseOutcome outcome, Frame request, byte[] raw, String detail) {
        this.outcome = outcome;
        this.request = request;
        this.raw = raw == null ? null : raw.clone();
        this.detail = detail;
    }

    static ResponseCheck of(ResponseOutcome outcome, Frame request, byte[] raw, String detail) {
        return new ResponseCheck(outcome, request, raw, detail);
    }

    public ResponseOutcome outcome() {
        return outcome;
    }

    /** 触发本次响应的请求帧。 */
    public Frame request() {
        return request;
    }

    /** 原始响应字节副本；未收到任何字节时为 null。 */
    public byte[] raw() {
        return raw == null ? null : raw.clone();
    }

    public String detail() {
        return detail;
    }

    public boolean isOk() {
        return outcome == ResponseOutcome.OK;
    }

    /**
     * 把 ok 结果当作单个响应帧返回。
     *
     * <p>非 ok 时抛出 {@link IllegalStateException}：调用方必须先用 {@link #isOk()}
     * 判定，避免把失败响应当数据读取。</p>
     */
    public Frame responseFrame() {
        if (outcome != ResponseOutcome.OK || raw == null || raw.length < Frame.LENGTH) {
            throw new IllegalStateException("响应未通过校验，不能读取帧内容: " + outcome + " / " + detail);
        }
        return Frame.fromWire(new byte[]{
                raw[0], raw[1], raw[2], raw[3], raw[4], raw[5]
        });
    }

    /** 成功响应的 byte 5（u8 读取与多数状态回复的约定）。 */
    public int u8AtByte5() {
        byte[] bytes = requireOkRaw();
        return bytes[5] & 0xFF;
    }

    /** 成功响应的 byte 4..5 大端 u16（SFR u16、ADC 的约定）。 */
    public int u16AtBytes4And5() {
        byte[] bytes = requireOkRaw();
        return ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
    }

    /** 成功响应的 byte 2..3 大端 u16。 */
    public int u16AtBytes2And3() {
        byte[] bytes = requireOkRaw();
        return ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
    }

    private byte[] requireOkRaw() {
        if (outcome != ResponseOutcome.OK || raw == null || raw.length < Frame.LENGTH) {
            throw new IllegalStateException("响应未通过校验，不能读取数值: " + outcome + " / " + detail);
        }
        return raw;
    }

    @Override
    public String toString() {
        return "ResponseCheck[" + outcome + ", request=" + (request == null ? "null" : request.hex())
                + ", raw=" + Hex.of(raw) + ", detail=" + detail + "]";
    }
}
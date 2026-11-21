package space.firok.moli.usb.api.codec;

/**
 * 编解码层：响应校验器。
 *
 * <p>规范明确「不能把任意非空数据当成成功」，所以本类只承认三种结论，并且都必须
 * 建立在「收齐 6 字节完整帧」这一前提上：</p>
 *
 * <ul>
 *   <li>{@link ResponseOutcome#OK}——完整帧且与请求命令对应（回显 byte 0/1）；</li>
 *   <li>{@link ResponseOutcome#MALFORMED}——不足一帧、或内容自相矛盾；</li>
 *   <li>{@link ResponseOutcome#DESYNCHRONIZED}——完整帧但命令不对应，或多出字节，
 *       说明主机与设备已经错位。</li>
 * </ul>
 *
 * <p>设备响应是否精确回显请求的 byte 0/1 仍待实机确认（spec/verification.md），因此
 * 同时提供 {@link #checkLenient} 用于「已确认该命令不回显」的场合；即使是宽松模式，
 * 也绝不把不足 6 字节的残片或握手横幅当作成功。</p>
 */
public final class ResponseParser {

    private ResponseParser() {
    }

    /**
     * 严格校验：响应必须完整 6 字节，且 byte 0/1 与请求一致。
     *
     * @param request 已发送的请求帧，用于比对回显
     * @param raw     实际读到的字节（可能为空、残缺或过长）
     */
    public static ResponseCheck check(Frame request, byte[] raw) {
        ResponseCheck structural = checkStructure(request, raw);
        if (structural != null) {
            return structural;
        }
        if ((raw[0] & 0xFF) != request.command() || (raw[1] & 0xFF) != request.subcommand()) {
            return ResponseCheck.of(ResponseOutcome.DESYNCHRONIZED, request, raw,
                    "响应命令不对应请求：期望 " + Hex.of(request.toWire(), 0, 2)
                            + "，实际 " + Hex.of(raw, 0, 2));
        }
        return ResponseCheck.of(ResponseOutcome.OK, request, raw, "回显匹配");
    }

    /**
     * 宽松校验：只要求收齐一个完整 6 字节帧，不比对 byte 0/1。
     *
     * <p>仅用于规范已记录「设备不回显该请求」的操作；不得作为默认策略。</p>
     */
    public static ResponseCheck checkLenient(Frame request, byte[] raw) {
        ResponseCheck structural = checkStructure(request, raw);
        if (structural != null) {
            return structural;
        }
        return ResponseCheck.of(ResponseOutcome.OK, request, raw, "完整帧（未校验回显）");
    }

    /**
     * 结构校验。返回非 null 表示已可定论（畸形或失步），null 表示结构上还是一个
     * 待比对回显的完整帧。
     */
    private static ResponseCheck checkStructure(Frame request, byte[] raw) {
        if (raw == null || raw.length == 0) {
            return ResponseCheck.of(ResponseOutcome.MALFORMED, request, raw,
                    "响应为空，未收到任何字节");
        }
        if (raw.length < Frame.LENGTH) {
            return ResponseCheck.of(ResponseOutcome.MALFORMED, request, raw,
                    "响应不完整：仅收到 " + raw.length + " 字节，需要 " + Frame.LENGTH);
        }
        if (raw.length > Frame.LENGTH) {
            return ResponseCheck.of(ResponseOutcome.DESYNCHRONIZED, request, raw,
                    "响应多于 " + Frame.LENGTH + " 字节（实际 " + raw.length
                            + "），存在上一轮残留或设备主动推送");
        }
        if (HandshakeCodec.hasMsnPrefix(raw, 0)) {
            return ResponseCheck.of(ResponseOutcome.DESYNCHRONIZED, request, raw,
                    "命令阶段收到握手横幅 " + Hex.of(raw) + "，设备已回到握手状态");
        }
        return null;
    }

    /**
     * 校验「写操作」的成功语义：写类命令不读取数值，只看是否收到对应回显。
     * 空响应在实践中也可能表示成功（设备不回显写操作），由 {@code allowEmpty} 显式声明。
     */
    public static ResponseCheck checkWrite(Frame request, byte[] raw, boolean allowEmpty) {
        if (allowEmpty && (raw == null || raw.length == 0)) {
            return ResponseCheck.of(ResponseOutcome.OK, request, raw, "写操作无回显（已声明允许）");
        }
        return check(request, raw);
    }
}
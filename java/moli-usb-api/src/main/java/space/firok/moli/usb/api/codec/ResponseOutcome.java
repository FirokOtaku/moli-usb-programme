package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：响应判定结果分类。
 *
 * <p>规范（spec/msu2-wire-protocol.md「基本帧」）明确：响应长度、完整回显规则和
 * 错误码尚未确认，因此不能把「任意非空数据」当成成功。本枚举把一次响应明确归到
 * 三个互斥结论里，由会话层据此决定报错还是继续。</p>
 */
public enum ResponseOutcome {

    /** 响应帧完整且与请求命令对应。 */
    OK,

    /** 响应长度不对、帧不完整或内容自相矛盾。 */
    MALFORMED,

    /** 响应是一个完整帧，但与当前请求的命令/子命令不对应，说明主机与设备已失步。 */
    DESYNCHRONIZED
}
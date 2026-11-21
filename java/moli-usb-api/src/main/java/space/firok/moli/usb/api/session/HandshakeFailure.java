package space.firok.moli.usb.api.session;

import space.firok.moli.usb.api.error.HandshakeFailedError;

/**
 * 会话层：握手阶段的失败原因细分。
 *
 * <p>所有原因最终都投影为 {@link HandshakeFailedError}（超时见下方注释），但保留细分
 * 便于日志定位与测试断言。错误分类集合由 spec/architecture.md 固定，不新增分类。</p>
 */
public enum HandshakeFailure {

    /** 在握手总超时内没有收到任何 {@code \0MSN} 前缀字节。 */
    NO_BANNER,

    /** 收到了横幅，但两位版本号不是 ASCII 十进制数字。 */
    BAD_BANNER,

    /** 横幅版本与库支持的协议版本不兼容。 */
    UNSUPPORTED_VERSION,

    /** 发送确认后未在超时内收到 {@code \0MSNCN} 回显。 */
    NO_CONFIRM,

    /** 收到了 6 字节响应，但不是 {@code \0MSNCN}。 */
    WRONG_CONFIRM,

    /** 握手过程中传输层断开（此原因仍抛 {@code DisconnectedError}，不抛握手失败）。 */
    DISCONNECTED
}
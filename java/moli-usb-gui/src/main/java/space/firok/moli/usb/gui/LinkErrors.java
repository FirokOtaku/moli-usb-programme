package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.ProtocolDesynchronizedError;
import space.firok.moli.usb.api.error.TimeoutError;

/**
 * 发送过程中「链路已经不能继续」的分类。
 *
 * <p>只用 {@code instanceof}，不看消息文本。消息里写着「断开」的参数错误不是断链；
 * 消息里写着别的内容的 {@link DisconnectedError} 仍然是断链。</p>
 */
final class LinkErrors {

    private LinkErrors() {
    }

    /**
     * 传输断开、命令超时或协议失步。这三类出现在发送中途时，会话不能再安全地发下一帧。
     * 参数错误、能力不支持、取消都不是断链。
     */
    static boolean isLinkLoss(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DisconnectedError
                    || current instanceof TimeoutError
                    || current instanceof ProtocolDesynchronizedError) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}

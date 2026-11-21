package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.error.CancelledError;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.error.DeviceNotFoundError;
import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.HandshakeFailedError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.MalformedResponseError;
import space.firok.moli.usb.api.error.MoliUsbException;
import space.firok.moli.usb.api.error.OutOfBoundsError;
import space.firok.moli.usb.api.error.ProtocolDesynchronizedError;
import space.firok.moli.usb.api.error.ProtocolMismatchError;
import space.firok.moli.usb.api.error.StorageCapacityExceededError;
import space.firok.moli.usb.api.error.StorageOperationFailedError;
import space.firok.moli.usb.api.error.TimeoutError;
import space.firok.moli.usb.api.error.UnsupportedCapabilityError;

/**
 * 把 {@link MoliUsbException} 收成界面可读的中文。
 *
 * <p>分类只用 {@code instanceof}（这里是模式匹配 switch），不解析异常消息。
 * 消息只作为详情附在类别后面。</p>
 */
final class ErrorTexts {

    private ErrorTexts() {
    }

    static String describe(MoliUsbException error) {
        String kind = switch (error) {
            case HandshakeFailedError ignored -> "握手失败";
            case DeviceNotFoundError ignored -> "未找到设备";
            case DeviceBusyError ignored -> "设备忙";
            case TimeoutError ignored -> "超时";
            case CancelledError ignored -> "已取消";
            case DisconnectedError ignored -> "连接已断开";
            case ProtocolMismatchError ignored -> "协议不匹配";
            case ProtocolDesynchronizedError ignored -> "协议失步";
            case UnsupportedCapabilityError ignored -> "不支持的能力";
            case InvalidArgumentError ignored -> "参数无效";
            case OutOfBoundsError ignored -> "越界";
            case StorageCapacityExceededError ignored -> "存储容量不足";
            case StorageOperationFailedError ignored -> "存储操作失败";
            case MalformedResponseError ignored -> "响应格式错误";
            default -> "设备错误";
        };
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return kind;
        }
        return kind + "：" + message;
    }
}

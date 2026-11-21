package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.error.CancelledError;
import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.ProtocolDesynchronizedError;
import space.firok.moli.usb.api.error.TimeoutError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkErrorsTest {

    @Test
    @DisplayName("断链只看类型，不看消息文本")
    void classifiesByTypeNotMessage() {
        assertTrue(LinkErrors.isLinkLoss(new DisconnectedError("streamImage", "COM3", "参数无效")));
        assertTrue(LinkErrors.isLinkLoss(new TimeoutError("fillRect", "COM3", "握手失败")));
        assertTrue(LinkErrors.isLinkLoss(new ProtocolDesynchronizedError("streamImage", "COM3", "设备忙")));
        assertFalse(LinkErrors.isLinkLoss(new InvalidArgumentError("streamImage", "COM3", "设备已断开")));
        assertFalse(LinkErrors.isLinkLoss(new CancelledError("streamImage", "COM3", "连接已断开")));
        assertFalse(LinkErrors.isLinkLoss(new IllegalStateException("设备已断开")));
    }
}

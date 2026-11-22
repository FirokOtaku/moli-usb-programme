package space.firok.moli.usb.serial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SerialReadTimeoutPolicyTest {

    @Test
    @DisplayName("已打开的端口不会把读超时改成 0 ms")
    void openPortDoesNotRetuneReadTimeout() {
        assertFalse(SerialTransport.retunesReadTimeout(),
                "0 ms 会让 jSerialComm 的 setComPortTimeouts 失败并拆掉仍打开的口");
    }
}

package space.firok.moli.usb.serial;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.transport.Transport;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端口占用（禁止双开）测试。
 *
 * <p>只验证登记表语义，不打开真实端口。占用登记先于句柄打开，因此这里可以直接
 * 用一个不存在的端口名验证 {@link DeviceBusyError} 的产生与释放。</p>
 */
class PortClaimTest {

    private static final String PORT = "COM_TEST_CLAIM";

    @AfterEach
    void cleanup() {
        SerialDeviceProvider.releasePortClaim(PORT);
    }

    @Test
    @DisplayName("同一端口第二次占用抛 DeviceBusyError，释放后可以再次占用")
    void duplicateClaimIsRejected() {
        assertFalse(SerialDeviceProvider.isPortClaimed(PORT));

        SerialDeviceProvider.claimPort(PORT);
        assertTrue(SerialDeviceProvider.isPortClaimed(PORT));

        DeviceBusyError busy = assertThrows(DeviceBusyError.class,
                () -> SerialDeviceProvider.claimPort(PORT));
        assertEquals("open", busy.operation());
        assertTrue(busy.getMessage().contains("不允许双开"));

        SerialDeviceProvider.releasePortClaim(PORT);
        assertFalse(SerialDeviceProvider.isPortClaimed(PORT));
        SerialDeviceProvider.claimPort(PORT);
        assertTrue(SerialDeviceProvider.isPortClaimed(PORT));
    }

    @Test
    @DisplayName("打开不存在的端口失败时不会残留占用")
    void failedOpenDoesNotLeakClaim() {
        SerialDeviceProvider provider = new SerialDeviceProvider();

        assertThrows(RuntimeException.class,
                () -> provider.open(PORT, SerialPortConfig.of(19200, false, false)));
        assertFalse(SerialDeviceProvider.isPortClaimed(PORT));
    }

    @Test
    @DisplayName("释放不存在的占用是安全的")
    void releaseIsIdempotent() {
        SerialDeviceProvider.releasePortClaim(PORT);
        SerialDeviceProvider.releasePortClaim(PORT);
        SerialDeviceProvider.releasePortClaim(null);
        assertFalse(SerialDeviceProvider.isPortClaimed(PORT));
        assertFalse(SerialDeviceProvider.isPortClaimed(null));
    }

    @Test
    @DisplayName("构造提供者时默认使用 LEGACY_SERIAL 参数，且默认不筛选 VID/PID")
    void defaultProviderConfig() {
        SerialDeviceProvider provider = new SerialDeviceProvider();

        assertEquals(19200, provider.defaultConfig().baudRate());
        assertTrue(provider.acceptedUsbIds().isEmpty());
        assertFalse(provider.hasUsbIdCandidates());
        assertTrue(SerialDeviceProvider.VID_PID_UNCONFIRMED.isEmpty());
    }

    @Test
    @DisplayName("withDefaultConfig 替换参数并保留候选列表")
    void withDefaultConfigKeepsCandidates() {
        SerialDeviceProvider provider = SerialDeviceProvider.over(
                List::of, List.of(new UsbId(0x1234, 0x5678)),
                SerialPortConfig.of(19200, false, false));

        SerialDeviceProvider updated = provider.withDefaultConfig(
                SerialPortConfig.of(921600, true, false));

        assertEquals(921600, updated.defaultConfig().baudRate());
        assertTrue(updated.defaultConfig().rtscts());
        assertEquals(provider.acceptedUsbIds(), updated.acceptedUsbIds());
    }

    @Test
    @DisplayName("close 幂等由 Transport 契约保证：装饰器关闭两次仍释放占用")
    void closingTwiceIsSafe() throws IOException {
        SerialDeviceProvider.claimPort(PORT);
        Transport transport = new NoopTransport(PORT);
        Transport releasing = new ReleasingForTest(transport, PORT);

        releasing.close();
        assertFalse(SerialDeviceProvider.isPortClaimed(PORT));
        releasing.close();
        assertFalse(SerialDeviceProvider.isPortClaimed(PORT));
    }

    /** 最小传输实现：仅用于关闭语义测试。 */
    private record NoopTransport(String address) implements Transport {

        @Override
        public byte[] read(int maxBytes, int timeoutMs) {
            return new byte[0];
        }

        @Override
        public void write(byte[] data) {
        }

        @Override
        public void clearInput() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void close() {
        }
    }

    /** 与生产装饰器同形状的关闭即释放包装。 */
    private record ReleasingForTest(Transport delegate, String portName) implements Transport {

        @Override
        public byte[] read(int maxBytes, int timeoutMs) throws IOException {
            return delegate.read(maxBytes, timeoutMs);
        }

        @Override
        public void write(byte[] data) throws IOException {
            delegate.write(data);
        }

        @Override
        public void clearInput() throws IOException {
            delegate.clearInput();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                SerialDeviceProvider.releasePortClaim(portName);
            }
        }

        @Override
        public String address() {
            return delegate.address();
        }
    }
}
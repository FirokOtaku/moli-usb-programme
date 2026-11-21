package space.firok.moli.usb.serial;

import com.fazecast.jSerialComm.SerialPort;
import space.firok.moli.usb.api.error.DeviceNotFoundError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.transport.Transport;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link Transport} 的 jSerialComm 实现。
 *
 * <p>只做字节搬运，不理解 6 字节帧或握手语义：帧边界、超时策略与失步恢复都由
 * {@code ProtocolSession} 决定。这里严格遵守 {@link Transport} 的契约：</p>
 *
 * <ul>
 *   <li>{@link #read(int, int)} 在 {@code timeoutMs} 内没有数据时返回空数组；</li>
 *   <li>端口已断开且无数据可读时返回 {@code null}（会话层据此抛 {@code DisconnectedError}）；</li>
 *   <li>{@link #write(byte[])} 要么写完整段字节，要么抛 {@link IOException}。</li>
 * </ul>
 *
 * <p>端口参数在打开时一次性设定为 8N1，并沿用 {@link SerialPortConfig} 指定的流控。
 * 单次读阻塞粒度被限制在 {@link #READ_GRANULARITY_MS}，以便在长超时下仍能及时响应
 * 关闭与取消，同时避免用忙轮询消耗 CPU。</p>
 *
 * <p>本类不负责「同一端口禁止双开」的登记：那是 {@link SerialDeviceProvider} 的职责，
 * 因为它需要在打开句柄之前完成占用。</p>
 */
public final class SerialTransport implements Transport {

    /** 单次底层读阻塞的上限（毫秒）。 */
    static final int READ_GRANULARITY_MS = 20;

    /** 写超时（毫秒）。写入很小的命令帧，超时取保守值即可。 */
    static final int WRITE_TIMEOUT_MS = 1000;

    private static final byte[] EMPTY = new byte[0];

    private final SerialPort port;
    private final String address;
    private final SerialPortConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean closed;

    private SerialTransport(SerialPort port, String address, SerialPortConfig config) {
        this.port = port;
        this.address = address;
        this.config = config;
    }

    /**
     * 打开一个串口并返回传输通道。调用方取得返回实例后即拥有其生命周期，必须
     * {@link #close()}；打开失败时本方法会自行关闭已经打开的句柄。
     *
     * @param descriptor 枚举阶段得到的端口元数据
     * @param config     波特率与流控
     * @throws DeviceNotFoundError 端口不存在、被占用或参数不被平台接受
     */
    public static SerialTransport open(PortDescriptor descriptor, SerialPortConfig config) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(config, "config");

        SerialPort port;
        try {
            port = SerialPort.getCommPort(descriptor.portName());
        } catch (RuntimeException invalidPort) {
            throw new DeviceNotFoundError("open", descriptor.portName(),
                    "无法定位串口 " + descriptor.portName() + "：" + invalidPort.getMessage(),
                    invalidPort);
        }
        if (port == null) {
            throw new DeviceNotFoundError("open", descriptor.portName(),
                    "系统中没有名为 " + descriptor.portName() + " 的串口");
        }
        return openPort(port, descriptor.portName(), config);
    }

    /** 打开指定端口名；供命令行工具与测试辅助代码使用。 */
    public static SerialTransport open(String portName, SerialPortConfig config) {
        return open(PortDescriptor.ofName(portName), config);
    }

    private static SerialTransport openPort(SerialPort port, String portName,
                                            SerialPortConfig config) {
        // 先开端口再设参数：jSerialComm 的 setComPortParameters 作用于已打开的句柄。
        if (!port.openPort()) {
            throw new DeviceNotFoundError("open", portName,
                    "打开串口失败（jSerialComm 错误码 " + port.getLastErrorCode()
                            + "，位置 " + port.getLastErrorLocation() + "）");
        }
        boolean configured = false;
        try {
            if (!port.setComPortParameters(config.baudRate(), config.dataBits(),
                    config.stopBits(), config.parity())) {
                throw new IOException("设置串口参数失败: " + config.describe());
            }
            if (!port.setFlowControl(config.flowControl())) {
                throw new IOException("设置流控失败: " + config.describe());
            }
            if (!port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                    READ_GRANULARITY_MS, WRITE_TIMEOUT_MS)) {
                throw new IOException("设置串口超时失败");
            }
            configured = true;
        } catch (IOException failed) {
            throw new DeviceNotFoundError("open", portName,
                    "串口 " + portName + " 打开后配置失败：" + failed.getMessage(), failed);
        } finally {
            if (!configured && port.isOpen()) {
                port.closePort();
            }
        }
        return new SerialTransport(port, portName, config);
    }

    @Override
    public byte[] read(int maxBytes, int timeoutMs) throws IOException {
        if (maxBytes <= 0) {
            throw new InvalidArgumentError("read", address,
                    "maxBytes 必须为正（实际 " + maxBytes + "）");
        }
        lock.lock();
        try {
            if (closed || !port.isOpen()) {
                return null;
            }
            long deadlineNanos = System.nanoTime()
                    + Math.max(0, timeoutMs) * 1_000_000L;
            byte[] buffer = new byte[maxBytes];
            applyReadTimeout(timeoutMs <= 0 ? 0 : Math.min(timeoutMs, READ_GRANULARITY_MS));

            while (true) {
                int count = port.readBytes(buffer, buffer.length);
                if (count > 0) {
                    return count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
                }
                if (count < 0) {
                    // jSerialComm 用 -1 表示读失败；端口通常已断开，交给会话层判定断线。
                    return null;
                }
                if (closed || !port.isOpen()) {
                    return null;
                }
                if (timeoutMs <= 0) {
                    // 不阻塞：一次非阻塞读取后即返回。
                    return EMPTY;
                }
                long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    return EMPTY;
                }
                applyReadTimeout((int) Math.min(remainingMs, READ_GRANULARITY_MS));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        lock.lock();
        try {
            if (closed || !port.isOpen()) {
                throw new IOException("串口 " + address + " 已关闭，无法写入");
            }
            int offset = 0;
            while (offset < data.length) {
                int written = port.writeBytes(data, data.length - offset, offset);
                if (written < 0) {
                    throw new IOException("写串口 " + address + " 失败（已写 " + offset
                            + "/" + data.length + " 字节）");
                }
                if (written == 0) {
                    // 写超时或缓冲满：视为断开，避免无限重试把会话卡死。
                    throw new IOException("写串口 " + address + " 超时（已写 " + offset
                            + "/" + data.length + " 字节）");
                }
                offset += written;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clearInput() throws IOException {
        lock.lock();
        try {
            if (closed || !port.isOpen()) {
                return;
            }
            // 只清接收缓冲：读空即可，不能用 flushIOBuffers（它同时丢弃待发数据）。
            byte[] sink = new byte[512];
            int guard = 0;
            while (port.bytesAvailable() > 0 && guard++ < 1024) {
                if (port.readBytes(sink, sink.length) <= 0) {
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isConnected() {
        return !closed && port.isOpen();
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (port.isOpen() && !port.closePort()) {
                throw new IOException("关闭串口 " + address + " 失败");
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String address() {
        return address;
    }

    /** 当前使用的串口参数，供探测工具回显。 */
    public SerialPortConfig config() {
        return config;
    }

    private void applyReadTimeout(int timeoutMs) throws IOException {
        if (!port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                timeoutMs, WRITE_TIMEOUT_MS)) {
            throw new IOException("设置读超时 " + timeoutMs + " ms 失败（端口 "
                    + address + "）");
        }
    }
}
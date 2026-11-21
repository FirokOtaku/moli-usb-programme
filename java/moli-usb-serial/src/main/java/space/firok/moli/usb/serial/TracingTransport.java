package space.firok.moli.usb.serial;

import space.firok.moli.usb.api.codec.Hex;
import space.firok.moli.usb.api.transport.Transport;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 传输装饰器：把每次读写的原始字节以十六进制交给回调，便于与
 * {@code spec/msu2-wire-protocol.md} 对照。
 *
 * <p>只用于实机探测与日志，不参与协议处理，也不改变字节内容或时序。回调异常被吞掉，
 * 避免日志故障把通讯打断。</p>
 */
final class TracingTransport implements Transport {

    private final Transport delegate;
    private final Consumer<String> sink;

    TracingTransport(Transport delegate, Consumer<String> sink) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public byte[] read(int maxBytes, int timeoutMs) throws IOException {
        byte[] data = delegate.read(maxBytes, timeoutMs);
        if (data == null) {
            emit("RX  <断开>");
        } else if (data.length > 0) {
            emit("RX  " + Hex.of(data) + "  (" + data.length + " 字节)");
        }
        return data;
    }

    @Override
    public void write(byte[] data) throws IOException {
        delegate.write(data);
        emit("TX  " + Hex.of(data) + "  (" + data.length + " 字节)");
    }

    @Override
    public void clearInput() throws IOException {
        delegate.clearInput();
        emit("RX  清空输入缓冲");
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public String address() {
        return delegate.address();
    }

    private void emit(String line) {
        try {
            sink.accept(line);
        } catch (RuntimeException ignored) {
            // 日志回调不应影响传输。
        }
    }
}
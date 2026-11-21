package space.firok.moli.usb.api.session;

import space.firok.moli.usb.api.transport.Transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 测试用假传输：无硬件、无串口。行为完全由脚本与应答函数决定。
 *
 * <p>语义对齐 {@link Transport} 约定：</p>
 * <ul>
 *   <li>{@link #read} 在超时且无数据时返回空数组；</li>
 *   <li>断开后 {@code read} 返回 {@code null}，{@code write} 抛 {@link IOException}；</li>
 *   <li>{@link #clearInput()} 丢弃尚未被读走的设备输出。</li>
 * </ul>
 */
public final class FakeTransport implements Transport {

    private final String address;
    private final Deque<Byte> incoming = new ArrayDeque<>();
    private final List<byte[]> writes = new ArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();

    private volatile boolean connected = true;
    private volatile Function<byte[], byte[]> responder;
    private volatile IOException nextWriteFailure;
    private volatile int writeCountLimit = -1;

    public FakeTransport(String address) {
        this.address = address;
    }

    /** 设备主动输出（例如握手横幅）。 */
    public synchronized FakeTransport script(byte[]... chunks) {
        for (byte[] chunk : chunks) {
            for (byte b : chunk) {
                incoming.add(b);
            }
        }
        return this;
    }

    /**
     * 安装「虚拟设备」：每次收到一整帧写入就调用 {@code responder}，其返回值立即出现在
     * 接收缓冲中。返回 {@code null} 表示不回应。
     *
     * <p>为简化脚本，写入会被解析为 6 字节帧；不足 6 字节的尾巴忽略。</p>
     */
    public FakeTransport respondWith(Function<byte[], byte[]> responder) {
        this.responder = responder;
        return this;
    }

    /** 第 {@code index}（从 1 开始）次写入后断开，模拟使用中断线。 */
    public FakeTransport disconnectAfterWrites(int index) {
        this.writeCountLimit = index;
        return this;
    }

    /** 下一次写入抛 IO 异常。 */
    public void failNextWrite(IOException failure) {
        this.nextWriteFailure = failure;
    }

    /** 立即断开。 */
    public void disconnect() {
        this.connected = false;
    }

    /** 所有被写入的字节块副本。 */
    public synchronized List<byte[]> writes() {
        List<byte[]> copy = new ArrayList<>(writes.size());
        for (byte[] write : writes) {
            copy.add(write.clone());
        }
        return copy;
    }

    /** 把全部写入拼接成一个字节流，便于按帧切片断言。 */
    public synchronized byte[] allWritten() {
        int total = writes.stream().mapToInt(bytes -> bytes.length).sum();
        byte[] joined = new byte[total];
        int offset = 0;
        for (byte[] write : writes) {
            System.arraycopy(write, 0, joined, offset, write.length);
            offset += write.length;
        }
        return joined;
    }

    /** 写入次数。 */
    public synchronized int writeCount() {
        return writes.size();
    }

    /** 读取次数（用于断言轮询确实发生）。 */
    public int readCount() {
        return reads.get();
    }

    /** 接收缓冲中尚未读走的字节数。 */
    public synchronized int pendingBytes() {
        return incoming.size();
    }

    @Override
    public byte[] read(int maxBytes, int timeoutMs) {
        reads.incrementAndGet();
        if (!connected) {
            return null;
        }
        synchronized (this) {
            if (incoming.isEmpty()) {
                if (timeoutMs <= 0) {
                    return new byte[0];
                }
            } else {
                int count = Math.min(maxBytes, incoming.size());
                byte[] out = new byte[count];
                for (int i = 0; i < count; i++) {
                    out[i] = incoming.removeFirst();
                }
                return out;
            }
        }
        // 无数据：模拟一次受限阻塞，且可以被打断。
        try {
            Thread.sleep(Math.min(Math.max(timeoutMs, 1), 2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return new byte[0];
    }

    @Override
    public void write(byte[] payload) throws IOException {
        IOException failure = nextWriteFailure;
        if (failure != null) {
            nextWriteFailure = null;
            throw failure;
        }
        if (!connected) {
            throw new IOException("假传输已断开");
        }
        Function<byte[], byte[]> current;
        synchronized (this) {
            writes.add(payload.clone());
            if (writeCountLimit > 0 && writes.size() >= writeCountLimit) {
                connected = false;
            }
            current = responder;
        }
        if (current != null) {
            // 写入按 6 字节帧解析，以对齐真实协议行为。
            for (int offset = 0; offset + 6 <= payload.length; offset += 6) {
                byte[] frame = Arrays.copyOfRange(payload, offset, offset + 6);
                byte[] response = current.apply(frame);
                if (response != null && response.length > 0) {
                    script(response);
                }
            }
        }
    }

    @Override
    public synchronized void clearInput() {
        incoming.clear();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        connected = false;
    }

    @Override
    public String address() {
        return address;
    }
}
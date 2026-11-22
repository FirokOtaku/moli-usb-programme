package space.firok.moli.usb.api;

import space.firok.moli.usb.api.codec.HandshakeCodec;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.error.DeviceNotFoundError;
import space.firok.moli.usb.api.transport.Transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用虚拟 MSU2 设备：实现 {@link DeviceProvider}，每台设备是一段确定性的协议应答
 * 逻辑。没有任何真实串口调用。
 *
 * <p>行为要点：</p>
 * <ul>
 *   <li>握手：上电即广播 {@code \0MSN01}，收到 {@code \0MSNCN} 后回同样的确认帧；</li>
 *   <li>命令：默认按 6 字节帧回显，因此响应与请求必然对应；</li>
 *   <li>SFR u8 读与 Flash 读：从可配置映射返回；ADC：返回可配置采样值。</li>
 * </ul>
 */
final class ScriptedDevice implements DeviceProvider {

    private final List<String> addresses;
    private final Map<String, DeviceState> states = new HashMap<>();
    private final List<byte[]> payloadWrites = new ArrayList<>();
    private final Map<String, Integer> openCounts = new HashMap<>();

    ScriptedDevice(String... addresses) {
        this.addresses = List.of(addresses);
        for (String address : addresses) {
            states.put(address, new DeviceState());
        }
    }

    // ------------------------------------------------------------- provider

    @Override
    public List<DeviceCandidate> listCandidates() {
        List<DeviceCandidate> candidates = new ArrayList<>();
        for (String address : addresses) {
            candidates.add(new DeviceCandidate(
                    new DeviceId("UID-" + address, address),
                    "MSU2 " + address,
                    DeviceCandidate.OptionalInt.empty(),
                    DeviceCandidate.OptionalInt.empty(),
                    Optional.empty(),
                    true));
        }
        return candidates;
    }

    @Override
    public Transport open(DeviceCandidate candidate) {
        String key = candidate.id().transportKey();
        DeviceState state = states.get(key);
        if (state == null) {
            throw new DeviceNotFoundError("open", key, "虚拟设备不存在: " + key);
        }
        openCounts.merge(key, 1, Integer::sum);
        ScriptedTransport transport = new ScriptedTransport(key, state);
        state.transports.add(transport);
        return transport;
    }

    // ----------------------------------------------------------- test hooks

    int openCount() {
        int total = 0;
        for (int count : openCounts.values()) {
            total += count;
        }
        return total;
    }

    boolean isOpen(String address) {
        DeviceState state = states.get(address);
        if (state == null) {
            return false;
        }
        return state.transports.stream().anyMatch(ScriptedTransport::isConnected);
    }

    void clearWrites() {
        synchronized (payloadWrites) {
            payloadWrites.clear();
        }
    }

    List<byte[]> payloadWrites() {
        synchronized (payloadWrites) {
            return List.copyOf(payloadWrites);
        }
    }

    /** 把全部写入按 6 字节帧切开，便于逐帧断言（与传输分批无关）。 */
    List<byte[]> frames() {
        List<byte[]> out = new ArrayList<>();
        synchronized (payloadWrites) {
            for (byte[] payload : payloadWrites) {
                for (int offset = 0; offset + 6 <= payload.length; offset += 6) {
                    out.add(Arrays.copyOfRange(payload, offset, offset + 6));
                }
            }
        }
        return out;
    }

    void setSfr(int address, int value) {
        for (DeviceState state : states.values()) {
            state.sfr.put(address, value & 0xFF);
        }
    }

    /** u16 SFR，地址是 8 位。{@code Lcd_X} 在实机上就是地址 0。 */
    void setSfrU16(int address, int value) {
        for (DeviceState state : states.values()) {
            state.sfrU16.put(address & 0xFF, value & 0xFFFF);
        }
    }

    /**
     * 每条命令回显之后再追加的字节。实机上这是已经进入缓冲的 {@code \0MSNxx} 横幅
     * 或其前缀；旧主机会把它粘进响应，自描述读取失败后屏幕尺寸停留在 0x0。
     */
    void setEchoTail(byte[] tail) {
        this.echoTail = tail == null ? null : tail.clone();
    }

    private byte[] echoTail;

    void setFlashByte(int address, int value) {
        for (DeviceState state : states.values()) {
            state.flash.put(address, value & 0xFF);
        }
    }

    void enableKey(int channel, int idleLevel) {
        for (DeviceState state : states.values()) {
            state.keyChannel = channel;
            state.keySample = idleLevel;
        }
    }

    void pressKey(int channel, int sample) {
        for (DeviceState state : states.values()) {
            state.keyChannel = channel;
            state.keySample = sample;
        }
    }

    // ---------------------------------------------------------------- state

    private static final class DeviceState {
        final Map<Integer, Integer> sfr = new HashMap<>();
        final Map<Integer, Integer> sfrU16 = new HashMap<>();
        final Map<Integer, Integer> flash = new HashMap<>();
        final List<ScriptedTransport> transports = new CopyOnWriteArrayList<>();
        volatile int keyChannel = 9;
        volatile int keySample = 1000;
    }

    private final class ScriptedTransport implements Transport {

        private final String address;
        private final DeviceState state;
        private final Deque<Byte> incoming = new ArrayDeque<>();
        private volatile boolean connected = true;
        private volatile boolean handshakeDone;

        ScriptedTransport(String address, DeviceState state) {
            this.address = address;
            this.state = state;
            // 设备一上电即开始广播横幅。
            emit(HandshakeCodec.encodeBanner(1));
        }

        @Override
        public byte[] read(int maxBytes, int timeoutMs) {
            if (!connected) {
                return null;
            }
            synchronized (incoming) {
                if (incoming.isEmpty()) {
                    if (timeoutMs > 0) {
                        try {
                            Thread.sleep(Math.min(timeoutMs, 2));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return new byte[0];
                }
                int count = Math.min(maxBytes, incoming.size());
                byte[] out = new byte[count];
                for (int i = 0; i < count; i++) {
                    out[i] = incoming.removeFirst();
                }
                return out;
            }
        }

        @Override
        public void write(byte[] payload) throws IOException {
            if (!connected) {
                throw new IOException("虚拟设备已断开");
            }
            synchronized (payloadWrites) {
                payloadWrites.add(payload.clone());
            }
            for (int offset = 0; offset + 6 <= payload.length; offset += 6) {
                respond(Arrays.copyOfRange(payload, offset, offset + 6));
            }
        }

        private void respond(byte[] frame) {
            if (Arrays.equals(frame, HandshakeCodec.CHALLENGE)) {
                if (!handshakeDone) {
                    handshakeDone = true;
                    emit(HandshakeCodec.CHALLENGE);
                }
                return;
            }
            if (!handshakeDone) {
                return;
            }
            int command = frame[0] & 0xFF;
            int subcommand = frame[1] & 0xFF;
            switch (command) {
                case 0x00 -> {
                    if (subcommand == 0x30 && (frame[2] & 0xFF) == 0x00) {
                        int addressValue = ((frame[3] & 0xFF) << 8) | (frame[4] & 0xFF);
                        int value = state.sfr.getOrDefault(addressValue, 0);
                        emitResponse(new byte[]{0x00, 0x30, 0x00, frame[3], frame[4], (byte) value});
                    } else if (subcommand == 0x30 && (frame[2] & 0xFF) == 0x20) {
                        int addressValue = frame[3] & 0xFF;
                        int value = state.sfrU16.getOrDefault(addressValue, 0);
                        emitResponse(new byte[]{0x00, 0x30, 0x20, frame[3],
                                (byte) (value >>> 8), (byte) value});
                    } else {
                        emitResponse(frame);
                    }
                }
                case 0x03 -> {
                    if (subcommand == 0x00) {
                        int addressValue = ((frame[2] & 0xFF) << 16)
                                | ((frame[3] & 0xFF) << 8) | (frame[4] & 0xFF);
                        int value = state.flash.getOrDefault(addressValue, 0xFF);
                        emitResponse(new byte[]{0x03, 0x00, frame[2], frame[3], frame[4], (byte) value});
                    } else {
                        emitResponse(frame);
                    }
                }
                case 0x08 -> {
                    int sample = state.keySample;
                    emitResponse(new byte[]{0x08, frame[1], 0x00, 0x00,
                            (byte) (sample >>> 8), (byte) sample});
                }
                // 0x04 暂存区与 0x09 RLE 数据块是批量载荷，真实设备不逐帧回执。
                case 0x04, 0x09 -> {
                }
                case 0x02 -> {
                    // action 0x08 / 0x09 在厂商源码里只写不读。回显它们会掩盖
                    // 「主机还在等回执」的回归。
                    if (subcommand == 0x03) {
                        int action = frame[2] & 0xFF;
                        if (action == 0x08 || action == 0x09) {
                            break;
                        }
                    }
                    emitResponse(frame);
                }
                default -> emitResponse(frame);
            }
        }

        private void emitResponse(byte[] bytes) {
            emit(bytes);
            byte[] tail = echoTail;
            if (tail != null && tail.length > 0) {
                emit(tail);
            }
        }

        private void emit(byte[] bytes) {
            synchronized (incoming) {
                for (byte value : bytes) {
                    incoming.add(value);
                }
            }
        }

        @Override
        public void clearInput() {
            synchronized (incoming) {
                incoming.clear();
            }
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
}
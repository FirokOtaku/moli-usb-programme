package space.firok.moli.usb.api;

import space.firok.moli.usb.api.codec.AdcCodec;
import space.firok.moli.usb.api.codec.AdcKeyDetector;
import space.firok.moli.usb.api.codec.DisplayCodec;
import space.firok.moli.usb.api.codec.FlashCodec;
import space.firok.moli.usb.api.codec.Frame;
import space.firok.moli.usb.api.codec.PageAddressOrder;
import space.firok.moli.usb.api.codec.PixelStreamCodec;
import space.firok.moli.usb.api.codec.RleCodec;
import space.firok.moli.usb.api.codec.ScratchCodec;
import space.firok.moli.usb.api.codec.SfrCodec;
import space.firok.moli.usb.api.codec.SfrTableParser;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceInfo;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.InputEvent;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.domain.StorageGeometry;
import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.MalformedResponseError;
import space.firok.moli.usb.api.error.OutOfBoundsError;
import space.firok.moli.usb.api.error.StorageCapacityExceededError;
import space.firok.moli.usb.api.error.StorageOperationFailedError;
import space.firok.moli.usb.api.error.UnsupportedCapabilityError;
import space.firok.moli.usb.api.session.CancellationToken;
import space.firok.moli.usb.api.session.ProtocolSession;
import space.firok.moli.usb.api.transport.Transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 领域 API 实现：把领域操作翻译成编解码层帧，并通过 {@link ProtocolSession} 串行收发。
 *
 * <p>本类不暴露 {@link Transport}，也不把 6 字节帧或厂商函数名泄漏到公开签名。</p>
 */
final class MoliDeviceImpl implements MoliDevice {

    private final DeviceId id;
    private final DeviceProfile profile;
    private final ProtocolSession session;
    private final DeviceInfo info;
    private final DeviceCapabilities capabilities;
    private final StorageGeometry storage;
    private final DisplayGeometry display;
    private final PageAddressOrder pageOrder;

    private MoliDeviceImpl(DeviceId id, DeviceProfile profile, ProtocolSession session,
                           DeviceInfo info, DeviceCapabilities capabilities,
                           StorageGeometry storage, DisplayGeometry display) {
        this.id = id;
        this.profile = profile;
        this.session = session;
        this.info = info;
        this.capabilities = capabilities;
        this.storage = storage;
        this.display = display;
        this.pageOrder = profile.colorPageAddressOrder() == DeviceProfile.ColorPageAddressOrder.MID_LOW_HIGH
                ? PageAddressOrder.MID_LOW_HIGH
                : PageAddressOrder.LOW_HIGH_ZERO;
    }

    /**
     * 打开设备：握手 → 读取自描述表（尽力）→ 建立能力集。
     *
     * @param candidate 已选中的候选设备
     * @param transport 该候选的传输通道（本方法接管其生命周期）
     */
    static MoliDevice open(DeviceCandidate candidate, Transport transport, MoliOptions options,
                           CancellationToken token) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(options, "options");

        ProtocolSession session = ProtocolSession.open(transport, options.sessionSettings(),
                options.registry(), token);
        boolean established = false;
        try {
            DeviceProfile profile = options.profile();
            // The PRO demo initializes direction on startup. Require evidence the
            // command parser is alive, rather than accepting a challenge echo alone.
            if (DeviceProfile.PRO.equals(profile.name())) {
                session.requireDisplayAction(DisplayCodec.setOrientation(0), "initializeDisplay", token);
            }
            ProbeResult probe = probeSelfDescription(session, options, token);
            token.throwIfCancelled("open", session.deviceKey());

            DeviceInfo info = new DeviceInfo(
                    session.protocolVersion(),
                    probe.uid().orElseGet(() -> fallbackUid(candidate, session)),
                    probe.screenWidth(),
                    probe.screenHeight(),
                    probe.storage(),
                    probe.reportedEntries());

            DeviceCapabilities capabilities = mergeCapabilities(profile, probe);
            StorageGeometry storage = probe.storage()
                    .or(() -> profile.storage())
                    .orElse(null);
            DisplayGeometry display = resolveDisplay(profile, probe);

            MoliDeviceImpl device = new MoliDeviceImpl(candidate.id(), profile, session,
                    info, capabilities, storage, display);
            established = true;
            return device;
        } finally {
            if (!established) {
                session.close();
            }
        }
    }

    // ------------------------------------------------------------- 基本信息

    @Override
    public DeviceId id() {
        return id;
    }

    @Override
    public DeviceInfo info() {
        return info;
    }

    @Override
    public DeviceCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public Optional<DisplayGeometry> display() {
        return Optional.ofNullable(display);
    }

    @Override
    public Optional<StorageGeometry> storage() {
        return Optional.ofNullable(storage);
    }

    @Override
    public String profileName() {
        return profile.name();
    }

    // ----------------------------------------------------------------- 显示

    @Override
    public void setOrientation(DisplayGeometry.Orientation orientation) {
        Objects.requireNonNull(orientation, "orientation");
        requireDisplay("setOrientation");
        if (display != null && !display.orientations().contains(orientation)) {
            throw new UnsupportedCapabilityError("setOrientation", id.toString(),
                    "设备不声明方向 " + orientation + "，仅支持 " + display.orientations());
        }
        dispatch(DisplayCodec.setOrientation(orientation.wireValue()), "setOrientation");
    }

    @Override
    public void setBacklight(int pwm) {
        if (!capabilities.backlight()) {
            throw new UnsupportedCapabilityError("setBacklight", id.toString(),
                    "设备不声明背光能力");
        }
        dispatch(DisplayCodec.setBacklight(pwm), "setBacklight");
    }

    @Override
    public void setLed(int index, int red, int green, int blue) {
        if (!capabilities.rgbLeds()) {
            throw new UnsupportedCapabilityError("setLed", id.toString(),
                    "设备不声明 RGB 灯能力");
        }
        dispatch(DisplayCodec.setLed(index, red, green, blue), "setLed");
    }

    @Override
    public void applyLeds() {
        if (!capabilities.rgbLeds()) {
            throw new UnsupportedCapabilityError("applyLeds", id.toString(),
                    "设备不声明 RGB 灯能力");
        }
        dispatch(DisplayCodec.applyLeds(), "applyLeds");
    }

    @Override
    public void fillRect(Rect rect, Rgb565 color) {
        Objects.requireNonNull(rect, "rect");
        Objects.requireNonNull(color, "color");
        requireDisplay("fillRect");
        requireWithinDisplay(rect, "fillRect");
        session.displayTransaction(List.of(DisplayCodec.setXy(rect.x(), rect.y()),
                        DisplayCodec.setSize(rect.width(), rect.height()),
                        DisplayCodec.setColors(color.value(), color.value())),
                DisplayCodec.fillCurrent(color.value()), List.of(), "fillRect");
    }

    @Override
    public void showStoredColorImage(int page) {
        requireDisplay("showStoredColorImage");
        requireStorage("showStoredColorImage");
        if (page < 0) {
            throw new InvalidArgumentError("showStoredColorImage", id.toString(),
                    "页地址不能为负（实际 " + page + "）");
        }
        dispatch(DisplayCodec.showFlashColor(page, pageOrder), "showStoredColorImage");
    }

    @Override
    public void showStoredMonoImage(int page) {
        requireDisplay("showStoredMonoImage");
        requireStorage("showStoredMonoImage");
        dispatch(DisplayCodec.showFlashMono(page), "showStoredMonoImage");
    }

    @Override
    public void streamImage(Rect rect, int[] pixels) {
        Objects.requireNonNull(rect, "rect");
        Objects.requireNonNull(pixels, "pixels");
        requireDisplay("streamImage");
        requireWithinDisplay(rect, "streamImage");
        long expected = (long) rect.width() * rect.height();
        if (pixels.length != expected) {
            throw new InvalidArgumentError("streamImage", id.toString(),
                    "像素数量必须等于 " + expected + "（实际 " + pixels.length + "）");
        }
        byte[] wire = PixelStreamCodec.toWireBytes(pixels);
        writeImage(rect, PixelStreamCodec.encodeStream(wire), "streamImage");
    }

    @Override
    public void streamImageRle(Rect rect, int[] pixels, int maxFrames) {
        Objects.requireNonNull(rect, "rect");
        Objects.requireNonNull(pixels, "pixels");
        requireDisplay("streamImageRle");
        requireWithinDisplay(rect, "streamImageRle");
        long expected = (long) rect.width() * rect.height();
        if (pixels.length != expected) {
            throw new InvalidArgumentError("streamImageRle", id.toString(),
                    "像素数量必须等于 " + expected + "（实际 " + pixels.length + "）");
        }
        int worstCase = RleCodec.worstCaseFrameCount(pixels.length);
        if (maxFrames > 0 && worstCase > maxFrames) {
            // 规范要求：最坏情况可能膨胀，调用方必须能回退到未压缩路径。
            throw new InvalidArgumentError("streamImageRle", id.toString(),
                    "RLE 最坏情况需要 " + worstCase + " 帧，超过给定上界 " + maxFrames
                            + "；请改用 streamImage 未压缩路径或提高上界");
        }
        writeImage(rect, PixelStreamCodec.encodeRleStream(pixels, maxFrames), "streamImageRle");
    }

    @Override
    public void streamImageCompact(Rect rect, int[] pixels) {
        if (!DeviceProfile.MINI.equals(profile.name())) {
            streamImageRle(rect, pixels, 0);
            return;
        }
        requireDisplay("streamImageCompact");
        requireWithinDisplay(rect, "streamImageCompact");
        if (pixels.length != (long) rect.width() * rect.height()) {
            throw new InvalidArgumentError("streamImageCompact", id.toString(), "像素数量与区域不符");
        }
        writeImage(rect, PixelStreamCodec.encodeMiniCompactStream(pixels), "streamImageCompact");
    }

    private void writeImage(Rect rect, List<Frame> frames, String operation) {
        session.displayTransaction(List.of(DisplayCodec.setXy(rect.x(), rect.y()),
                DisplayCodec.setSize(rect.width(), rect.height())), frames.getFirst(),
                frames.subList(1, frames.size()), operation);
    }

    // ----------------------------------------------------------------- 存储

    @Override
    public byte[] readStorage(long address, int length) {
        requireStorage("readStorage");
        if (length <= 0) {
            throw new InvalidArgumentError("readStorage", id.toString(),
                    "读取长度必须大于 0（实际 " + length + "）");
        }
        checkAddressRange(address, length, "readStorage");
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            Frame request = FlashCodec.readByte((int) (address + i));
            var check = session.exchange(request, "readStorage", CancellationToken.none(), true);
            out[i] = (byte) check.u8AtByte5();
        }
        return out;
    }

    @Override
    public void erasePages(long startPage, long pageCount) {
        requireStorage("erasePages");
        checkPageRange(startPage, pageCount, "erasePages");
        if (pageCount > 0xFFFF) {
            throw new InvalidArgumentError("erasePages", id.toString(),
                    "单次擦除页数受 16 位字段限制（实际 " + pageCount + "）");
        }
        Frame request = FlashCodec.erasePages((int) startPage, (int) pageCount);
        verifyWrite(request, "erasePages");
    }

    @Override
    public void writePages(long startPage, byte[] data) {
        requireStorage("writePages");
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new InvalidArgumentError("writePages", id.toString(), "写入数据不能为空");
        }
        long pageCount = storage.pagesFor(data.length);
        checkWriteCapacity(startPage, data, "writePages");
        checkPageRange(startPage, pageCount, "writePages");
        if (pageCount > 0xFFFF) {
            throw new InvalidArgumentError("writePages", id.toString(),
                    "单次写入页数受 16 位字段限制（实际 " + pageCount + "）");
        }
        if (pageCount > 0xFF) {
            throw new InvalidArgumentError("writePages", id.toString(),
                    "暂存区写页命令的页数字段是 8 位，单次最多 255 页（实际 " + pageCount + "）");
        }

        // 写入前必须先擦除目标页：Flash 只能把 1 写成 0。
        Frame erase = FlashCodec.erasePages((int) startPage, (int) pageCount);
        verifyWrite(erase, "writePages");

        int pageSize = storage.pageSize();
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            long page = startPage + pageIndex;
            int offsetBytes = pageIndex * pageSize;
            int length = Math.min(pageSize, data.length - offsetBytes);
            byte[] pageData = new byte[pageSize];
            System.arraycopy(data, offsetBytes, pageData, 0, length);

            List<Frame> staged = ScratchCodec.writeChunk(pageData);
            session.sendAll(staged, "writePages");
            Frame write = FlashCodec.writePagesFromScratch((int) page, 1);
            verifyWrite(write, "writePages");
        }
    }

    // ----------------------------------------------------------------- 输入

    @Override
    public InputSubscription subscribeInput(Consumer<InputEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (!capabilities.buttonInput()) {
            throw new UnsupportedCapabilityError("subscribeInput", id.toString(),
                    "设备不声明输入能力");
        }
        return InputPoller.start(session, id.toString(), listener,
                AdcKeyDetector.fromIdleLevel(MINI_KEY_ADC_CHANNEL, measureIdleLevel()));
    }

    /**
     * 读自描述表起始地址的一个字节。这是打开设备时已经在用的 SFR 读，不写像素、
     * 背光、方向或灯，也不增加新的操作码。
     */
    @Override
    public void probeLink() {
        session.exchange(SfrCodec.readU8(SfrTableParser.TABLE_ADDRESS), "probeLink",
                CancellationToken.none(), true);
    }

    @Override
    public void discardPending() {
        session.discardPending("discardPending");
    }

    /** MINI 按键的 ADC 通道；规范记为「旧版演示轮询通道 9」，仍需实机确认。 */
    static final int MINI_KEY_ADC_CHANNEL = 9;

    /**
     * 订阅时实测按键空闲电平，再按 Demo 策略推导阈值（均值 - 125）。
     *
     * <p>规范只记录「启动时两次采样均值减 125」这一 Demo 策略，没有给出绝对电平，因此
     * 不在库里写死阈值。两次采样都失败时退回 {@link #MINI_KEY_IDLE_FALLBACK}——这是
     * <b>待实机校准的最后兜底</b>，不是协议常量。</p>
     */
    private int measureIdleLevel() {
        long sum = 0;
        int taken = 0;
        for (int i = 0; i < 2; i++) {
            try {
                var check = session.exchange(AdcCodec.readChannel(MINI_KEY_ADC_CHANNEL),
                        "subscribeInput", CancellationToken.none(), true);
                sum += check.u16AtBytes4And5();
                taken++;
            } catch (RuntimeException ignored) {
                // 采样失败不阻止订阅：用剩下的样本或兜底值继续。
            }
        }
        if (taken == 0) {
            return MINI_KEY_IDLE_FALLBACK;
        }
        int idle = (int) (sum / taken);
        return idle > AdcKeyDetector.DEMO_DELTA ? idle : MINI_KEY_IDLE_FALLBACK;
    }

    /**
     * 空闲电平的兜底值。
     *
     * <p><b>dummy：</b>规范没有记录 ADC 空闲电平的绝对值，只记录了相对阈值策略。正常
     * 路径会用 {@link #measureIdleLevel()} 实测；只有设备连采样都读不到时才用到本值，
     * 实机校准后应替换。</p>
     */
    static final int MINI_KEY_IDLE_FALLBACK = 1000;

    // ------------------------------------------------------------- 内部工具

    private void requireDisplay(String operation) {
        if (!capabilities.display()) {
            throw new UnsupportedCapabilityError(operation, id.toString(), "设备不声明显示能力");
        }
    }

    private void requireStorage(String operation) {
        if (!capabilities.externalFlash()) {
            throw new UnsupportedCapabilityError(operation, id.toString(),
                    "设备不声明外部存储能力");
        }
        if (storage == null) {
            throw new UnsupportedCapabilityError(operation, id.toString(),
                    "设备声明外部存储能力，但未报告存储几何（缺少自描述信息）");
        }
    }

    private void requireWithinDisplay(Rect rect, String operation) {
        if (display == null) {
            throw new UnsupportedCapabilityError(operation, id.toString(),
                    "设备未报告显示几何，无法校验矩形范围");
        }
        if (rect.x() < 0 || rect.y() < 0 || rect.width() <= 0 || rect.height() <= 0) {
            throw new InvalidArgumentError(operation, id.toString(),
                    "矩形必须是正尺寸且坐标非负: " + rect);
        }
        if ((long) rect.x() + rect.width() > display.width()
                || (long) rect.y() + rect.height() > display.height()) {
            throw new InvalidArgumentError(operation, id.toString(),
                    "矩形 " + rect + " 超出显示区域 "
                            + display.width() + "x" + display.height());
        }
    }

    private void checkPageRange(long startPage, long pageCount, String operation) {
        if (startPage < 0 || pageCount <= 0) {
            throw new InvalidArgumentError(operation, id.toString(),
                    "页区间非法: start=" + startPage + ", count=" + pageCount);
        }
        if (startPage + pageCount > storage.totalPages()) {
            throw new OutOfBoundsError(operation, id.toString(),
                    "页区间越界: start=" + startPage + ", count=" + pageCount
                            + ", totalPages=" + storage.totalPages(),
                    startPage, pageCount, storage.totalPages());
        }
    }

    /**
     * 写入前的容量检查。
     *
     * <p>先按地址判越界，再按剩余容量判容量不足：两者是不同错误，调用方需要区分「地址
     * 落在设备存储之外」与「数据本身放不下」。</p>
     */
    private void checkWriteCapacity(long startPage, byte[] data, String operation) {
        long startByte = startPage * storage.pageSize();
        if (startByte >= storage.capacityBytes()) {
            throw new OutOfBoundsError(operation, id.toString(),
                    "起始地址 0x" + Long.toHexString(startByte) + " 超出容量 "
                            + storage.capacityBytes(),
                    startPage, 1, storage.totalPages());
        }
        long remaining = storage.capacityBytes() - startByte;
        if (data.length > remaining) {
            throw new StorageCapacityExceededError(operation, id.toString(),
                    "数据 " + data.length + " 字节，超过自偏移 " + startByte
                            + " 起的剩余容量 " + remaining + " 字节",
                    data.length, remaining);
        }
    }

    private void checkAddressRange(long address, int length, String operation) {
        if (address < 0) {
            throw new InvalidArgumentError(operation, id.toString(),
                    "地址不能为负（实际 " + address + "）");
        }
        long end = address + length;
        if (end > storage.capacityBytes()) {
            throw new OutOfBoundsError(operation, id.toString(),
                    "读取区间越界: [" + address + ", " + end + ") 超出容量 "
                            + storage.capacityBytes(),
                    address / storage.pageSize(),
                    (end + storage.pageSize() - 1) / storage.pageSize() - address / storage.pageSize(),
                    storage.totalPages());
        }
    }

    /** 发送一帧并等待确认；用于需要回执的单个操作。 */
    private void dispatch(Frame request, String operation) {
        session.exchange(request, operation, CancellationToken.none(), true);
    }

    /** 写类操作：要求回显匹配，收到不一致立即失败。 */
    private void verifyWrite(Frame request, String operation) {
        var check = session.exchange(request, operation, CancellationToken.none(), false);
        if (!check.isOk()) {
            throw new StorageOperationFailedError(operation, id.toString(),
                    "设备未确认写操作: " + check.detail());
        }
    }

    // ------------------------------------------------------------- 探测与能力

    private record ProbeResult(
            Optional<String> uid,
            int screenWidth,
            int screenHeight,
            Optional<StorageGeometry> storage,
            Map<String, DeviceInfo.SfrEntry> reportedEntries) {
    }

    /**
     * 解析显示几何。
     *
     * <p>优先使用设备自描述报告的宽高；设备没有报告时退回 profile 的已知几何；两者都
     * 缺失时返回 null，由各显示方法按能力拒绝，而不是在打开阶段就失败——未知型号只要
     * 能完成握手，就应可作为通用 MSU2 设备打开（spec/architecture.md）。</p>
     */
    private static DisplayGeometry resolveDisplay(DeviceProfile profile, ProbeResult probe) {
        Optional<DisplayGeometry> fromProfile = profile.display();
        if (fromProfile.isPresent()) {
            DisplayGeometry existing = fromProfile.get();
            int width = probe.screenWidth() > 0 ? probe.screenWidth() : existing.width();
            int height = probe.screenHeight() > 0 ? probe.screenHeight() : existing.height();
            return new DisplayGeometry(width, height, existing.origin(),
                    existing.orientations(), existing.colorFormat());
        }
        if (probe.screenWidth() > 0 && probe.screenHeight() > 0) {
            return new DisplayGeometry(probe.screenWidth(), probe.screenHeight(),
                    DisplayGeometry.Origin.TOP_LEFT,
                    List.of(DisplayGeometry.Orientation.NORMAL),
                    DisplayGeometry.PixelFormat.RGB565);
        }
        return null;
    }

    private static ProbeResult probeSelfDescription(ProtocolSession session, MoliOptions options,
                                                    CancellationToken token) {
        Map<String, DeviceInfo.SfrEntry> entries = new LinkedHashMap<>();
        Optional<String> uid = Optional.empty();
        int width = 0;
        int height = 0;
        Optional<StorageGeometry> storage = Optional.empty();

        if (!options.probeSelfDescription()) {
            return new ProbeResult(uid, width, height, storage, entries);
        }

        byte[] table;
        try {
            table = readSfrTable(session, token);
        } catch (RuntimeException probeFailed) {
            if (probeFailed instanceof DisconnectedError disconnected) {
                throw disconnected;
            }
            // 自描述表是 P1 待确认项：读取失败时退化为 profile 默认值，不阻塞打开。
            return new ProbeResult(uid, width, height, storage, entries);
        }

        List<SfrTableParser.Entry> parsed;
        try {
            parsed = SfrTableParser.parse(table);
        } catch (MalformedResponseError malformed) {
            return new ProbeResult(uid, width, height, storage, entries);
        }
        for (SfrTableParser.Entry entry : parsed) {
            entries.put(entry.name(), new DeviceInfo.SfrEntry(
                    entry.name(), entry.unit(), entry.family(), toIntegerList(entry.data())));
        }
        uid = entries.containsKey("MSN_UID")
                ? Optional.of(asciiOf(entries.get("MSN_UID").data()))
                : Optional.empty();
        width = scalarOf(session, entries, "Lcd_X", token);
        height = scalarOf(session, entries, "Lcd_Y", token);
        storage = parseFlashInfo(entries.get("Flash_Info"));
        return new ProbeResult(uid, width, height, storage, entries);
    }

    private static byte[] readSfrTable(ProtocolSession session, CancellationToken token) {
        byte[] table = new byte[SfrTableParser.TABLE_LENGTH];
        // 自描述表用 u8 SFR 读，每次只能取一个字节。
        for (int i = 0; i < SfrTableParser.TABLE_LENGTH; i++) {
            Frame request = SfrCodec.readU8(SfrTableParser.TABLE_ADDRESS + i);
            var check = session.exchange(request, "probeSelfDescription",
                    token, true);
            table[i] = (byte) check.u8AtByte5();
            if (i == 0 && table[0] == 0) {
                break;
            }
        }
        return table;
    }

    private static List<Integer> toIntegerList(byte[] data) {
        List<Integer> values = new ArrayList<>(data.length);
        for (byte b : data) {
            values.add(b & 0xFF);
        }
        return List.copyOf(values);
    }

    private static String asciiOf(List<Integer> data) {
        StringBuilder builder = new StringBuilder(data.size());
        for (Integer value : data) {
            if (value != null && value >= 0x20 && value <= 0x7E) {
                builder.append((char) value.intValue());
            }
        }
        return builder.toString();
    }

    private static int scalarOf(ProtocolSession session, Map<String, DeviceInfo.SfrEntry> entries,
                                String name, CancellationToken token) {
        DeviceInfo.SfrEntry entry = entries.get(name);
        if (entry == null || entry.data().isEmpty()) {
            return 0;
        }
        int kind = entry.family() >>> 5;
        return switch (kind) {
            // u8 SFR：数据是 16 位的寄存器地址，数值必须再读一次寄存器。
            case 0 -> {
                int address = ((entry.data().get(0) << 8)
                        | (entry.data().size() > 1 ? entry.data().get(1) : 0)) & 0xFFFF;
                var check = session.exchange(SfrCodec.readU8(address), "probeSelfDescription",
                        token, true);
                yield check.u8AtByte5();
            }
            // u16 SFR：数据是 8 位地址，同样需要再读。
            case 1 -> {
                var check = session.exchange(SfrCodec.readU16(entry.data().get(0)),
                        "probeSelfDescription", token, true);
                yield check.u16AtBytes4And5();
            }
            default -> 0;
        };
    }

    /**
     * 解析 {@code Flash_Info} 条目。
     *
     * <p><b>dummy：</b>规范只记录源码按名称查询过 {@code Flash_Info}，没有记录它的数据
     * 布局（family、字节含义）。这里不猜，返回空，由 profile 的已知存储几何兜底。
     * 实机抓包确认布局后应在此实现解析。</p>
     */
    private static Optional<StorageGeometry> parseFlashInfo(DeviceInfo.SfrEntry entry) {
        return Optional.empty();
    }

    /**
     * 合并 profile 默认能力与设备自描述报告。
     *
     * <p>只有设备报告了对应自描述项时才相信设备；否则沿用 profile 的已知默认值。未知
     * profile（{@link DeviceProfile#generic()}）的默认能力为全 false，即「不猜」。</p>
     */
    private static DeviceCapabilities mergeCapabilities(DeviceProfile profile,
                                                        ProbeResult probe) {
        DeviceCapabilities base = profile.capabilities();
        return DeviceCapabilities.of(
                base.display(),
                base.externalFlash() || probe.storage().isPresent(),
                base.rgbLeds(),
                base.backlight(),
                base.nand(),
                base.buttonInput());
    }

    private static String fallbackUid(DeviceCandidate candidate, ProtocolSession session) {
        // 设备未报告 MSN_UID 时用候选的唯一标识兜底：不改写传输地址，只加协议版本前缀，
        // 让同一设备在同一枚举周期内保持稳定。
        return candidate.id().uid() + "#pv" + session.protocolVersion();
    }

    @Override
    public void close() {
        session.close();
    }
}
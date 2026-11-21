package space.firok.moli.usb.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.error.DeviceNotFoundError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.OutOfBoundsError;
import space.firok.moli.usb.api.error.StorageCapacityExceededError;
import space.firok.moli.usb.api.error.UnsupportedCapabilityError;
import space.firok.moli.usb.api.session.DeviceSessionRegistry;
import space.firok.moli.usb.api.session.SessionSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 领域门面测试：验证枚举/打开、能力门控、显示、存储与输入的对外行为。
 *
 * <p>全部基于 {@link ScriptedDevice}（虚拟 MSU2 设备），不需要真实硬件。</p>
 */
class MoliDeviceTest {

    private static DeviceCandidate candidate(String uid, String transportKey) {
        return new DeviceCandidate(
                new DeviceId(uid, transportKey),
                "MSU2 " + uid,
                DeviceCandidate.OptionalInt.empty(),
                DeviceCandidate.OptionalInt.empty(),
                Optional.empty(),
                true);
    }

    private static MoliOptions options(DeviceProfile profile, ScriptedDevice device) {
        return new MoliOptions(profile,
                new SessionSettings(Duration.ofMillis(300), 2, Duration.ofMillis(120), 2, 1),
                new DeviceSessionRegistry(),
                true);
    }

    private static MoliDevice openMini(ScriptedDevice device) {
        MoliUsb api = MoliUsb.over(device);
        return api.open(candidate("MINI-1", "COM-A"), options(DeviceProfile.mini(), device));
    }

    @Nested
    @DisplayName("枚举与选择")
    class Discovery {

        @Test
        @DisplayName("discover 只枚举，不打开任何端口")
        void discoverDoesNotOpen() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            MoliUsb api = MoliUsb.over(device);
            assertEquals(1, api.discover().size());
            assertEquals(0, device.openCount(), "枚举不得打开端口");
        }

        @Test
        @DisplayName("选择器匹配多个设备时报错，不静默挑第一个")
        void ambiguousSelectorFails() {
            ScriptedDevice device = new ScriptedDevice("COM-A", "COM-B");
            MoliUsb api = MoliUsb.over(device);
            assertThrows(InvalidArgumentError.class,
                    () -> api.open(c -> c.displayName().startsWith("MSU2")));
        }

        @Test
        @DisplayName("选择器无匹配时报 DeviceNotFoundError")
        void missingSelectorFails() {
            MoliUsb api = MoliUsb.over(new ScriptedDevice("COM-A"));
            assertThrows(DeviceNotFoundError.class,
                    () -> api.open(c -> false));
        }

        @Test
        @DisplayName("按 DeviceId 打开时以枚举结果为准：uid 未命中则退回传输地址")
        void opensByDeviceId() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            MoliUsb api = MoliUsb.over(device);
            // uid 命中：打开的就是枚举到的那个候选。
            try (MoliDevice byUid = api.open(new DeviceId("UID-COM-A", "COM-A"),
                    options(DeviceProfile.mini(), device))) {
                assertEquals("UID-COM-A", byUid.id().uid());
                assertEquals("COM-A", byUid.id().transportKey());
            }
            // uid 不在枚举结果里，但传输地址匹配，仍能打开。
            try (MoliDevice byTransport = api.open(new DeviceId("UNKNOWN", "COM-A"),
                    options(DeviceProfile.mini(), device))) {
                assertEquals("COM-A", byTransport.id().transportKey());
                assertEquals("UID-COM-A", byTransport.id().uid());
            }
        }
    }

    @Nested
    @DisplayName("生命周期")
    class Lifecycle {

        @Test
        @DisplayName("同一设备双开报 DeviceBusyError")
        void doubleOpenIsBusy() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            MoliOptions shared = options(DeviceProfile.mini(), device);
            MoliUsb api = MoliUsb.over(device);
            MoliDevice first = api.open(candidate("UID-1", "COM-A"), shared);
            try {
                assertThrows(DeviceBusyError.class,
                        () -> api.open(candidate("UID-1", "COM-A"), shared));
            } finally {
                first.close();
            }
            try (MoliDevice second = api.open(candidate("UID-1", "COM-A"), shared)) {
                assertTrue(second.capabilities().display());
            }
        }

        @Test
        @DisplayName("close 幂等，关闭后设备信息仍可读")
        void closeIsIdempotent() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            MoliDevice moli = openMini(device);
            moli.close();
            moli.close();
            assertEquals("MINI-1", moli.id().uid());
            assertEquals(DeviceProfile.MINI, moli.profileName());
        }

        @Test
        @DisplayName("closeAll 关闭本入口打开的所有设备")
        void closeAllClosesEverything() {
            ScriptedDevice device = new ScriptedDevice("COM-A", "COM-B");
            MoliUsb api = MoliUsb.over(device);
            var list = api.discover();
            MoliOptions opts = options(DeviceProfile.mini(), device);
            MoliDevice a = api.open(list.get(0), opts);
            MoliDevice b = api.open(list.get(1), opts);
            assertTrue(a.capabilities().display() && b.capabilities().display());
            api.closeAll();
            assertFalse(device.isOpen("COM-A"));
            assertFalse(device.isOpen("COM-B"));
        }
    }

    @Nested
    @DisplayName("能力门控")
    class Capabilities {

        @Test
        @DisplayName("不声明背光时 setBacklight 报 UnsupportedCapabilityError")
        void backlightRequiresCapability() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                assertFalse(moli.capabilities().backlight());
                assertThrows(UnsupportedCapabilityError.class, () -> moli.setBacklight(500));
            }
        }

        @Test
        @DisplayName("MINI profile 支持显示、外部存储与按键，不支持 NAND/RGB 灯/背光")
        void miniCapabilities() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                DeviceCapabilities caps = moli.capabilities();
                assertTrue(caps.display());
                assertTrue(caps.externalFlash());
                assertTrue(caps.buttonInput());
                assertFalse(caps.nand());
                assertFalse(caps.rgbLeds());
                assertFalse(caps.backlight());
            }
        }

        @Test
        @DisplayName("通用 profile 未报告几何时拒绝依赖几何的操作，不猜测尺寸")
        void genericWithoutGeometryRejectsGeometryOps() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = MoliUsb.over(device).open(candidate("X", "COM-A"),
                    options(DeviceProfile.generic(), device))) {
                assertTrue(moli.display().isEmpty(), "通用 profile 不得凭空给出屏幕几何");
                assertThrows(UnsupportedCapabilityError.class,
                        () -> moli.fillRect(new Rect(0, 0, 1, 1), Rgb565.RED));
            }
        }
    }

    @Nested
    @DisplayName("显示")
    class Display {

        @Test
        @DisplayName("fillRect 依次发送坐标、尺寸、两色与填充动作")
        void fillRectSendsExpectedFrames() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                device.clearWrites();
                moli.fillRect(new Rect(0, 0, 160, 80), Rgb565.BLACK);
                List<byte[]> writes = device.frames();
                assertEquals(4, writes.size());
                assertArrayEquals(frame(0x02, 0x00, 0x00, 0x00, 0x00, 0x00), writes.get(0));
                assertArrayEquals(frame(0x02, 0x01, 0x00, 0xA0, 0x00, 0x50), writes.get(1));
                assertArrayEquals(frame(0x02, 0x02, 0x00, 0x00, 0x00, 0x00), writes.get(2));
                assertArrayEquals(frame(0x02, 0x03, 0x0B, 0x00, 0x00, 0x00), writes.get(3));
            }
        }

        @Test
        @DisplayName("矩形越出屏幕时报错，且一个字节都不发送")
        void rectOutsidePanelRejected() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                device.clearWrites();
                assertThrows(InvalidArgumentError.class,
                        () -> moli.fillRect(new Rect(100, 0, 100, 10), Rgb565.RED));
                assertThrows(InvalidArgumentError.class,
                        () -> moli.fillRect(new Rect(0, 70, 160, 20), Rgb565.RED));
                // 非法尺寸在 Rect 自身就被拒绝（领域值对象的保护）。
                assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, 0, 10));
                assertEquals(0, device.frames().size());
            }
        }

        @Test
        @DisplayName("彩色素材页地址按 profile 选择编码：PRO 使用 24 位中低高")
        void colorPageEncodingFollowsProfile() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            MoliOptions proOptions = options(DeviceProfile.pro(), device);
            try (MoliDevice moli = MoliUsb.over(device)
                    .open(candidate("PRO-1", "COM-A"), proOptions)) {
                device.clearWrites();
                moli.showStoredColorImage(0x012345);
                assertArrayEquals(frame(0x02, 0x03, 0x00, 0x23, 0x45, 0x01),
                        device.frames().getFirst());
            }
            try (MoliDevice moli = openMini(device)) {
                device.clearWrites();
                moli.showStoredColorImage(0x1234);
                assertArrayEquals(frame(0x02, 0x03, 0x00, 0x12, 0x34, 0x00),
                        device.frames().getFirst());
                // LITE 的 16 位布局无法承载 24 位页。
                assertThrows(InvalidArgumentError.class,
                        () -> moli.showStoredColorImage(0x012345));
            }
        }

        @Test
        @DisplayName("streamImage 生成 0x07 + 暂存区帧 + 0x08 + 0x09")
        void streamImageEmitsPipeline() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                device.clearWrites();
                Rect rect = new Rect(0, 0, 8, 1);
                int[] pixels = new int[8];
                java.util.Arrays.fill(pixels, Rgb565.GREEN.value());
                moli.streamImage(rect, pixels);
                List<byte[]> writes = device.frames();
                // 2 帧区域设置 + 0x07 + 4 帧暂存区（16 字节 / 4）+ 0x08 + 0x09
                assertEquals(9, writes.size());
                assertArrayEquals(frame(0x02, 0x03, 0x07, 0x00, 0x00, 0x00), writes.get(2));
                assertArrayEquals(frame(0x02, 0x03, 0x08, 0x00, 0x10, 0x00), writes.get(7));
                assertArrayEquals(frame(0x02, 0x03, 0x09, 0x00, 0x00, 0x00), writes.get(8));
                // 第一个暂存区帧携带绿色像素的高、低字节。
                assertArrayEquals(frame(0x04, 0x00, 0x07, 0xE0, 0x07, 0xE0), writes.get(3));
            }
        }

        @Test
        @DisplayName("streamImage 像素数不符时报错")
        void streamImageValidatesPixelCount() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                assertThrows(InvalidArgumentError.class,
                        () -> moli.streamImage(new Rect(0, 0, 4, 4), new int[15]));
            }
        }

        @Test
        @DisplayName("RLE 最坏情况超过给定上界时失败并提示改用未压缩路径")
        void rleRejectsOversizedWorstCase() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                int[] pixels = new int[160 * 80];
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = i % 2 == 0 ? Rgb565.RED.value() : Rgb565.BLACK.value();
                }
                InvalidArgumentError error = assertThrows(InvalidArgumentError.class,
                        () -> moli.streamImageRle(new Rect(0, 0, 160, 80), pixels, 64));
                assertTrue(error.getMessage().contains("streamImage"),
                        "错误应提示回退到未压缩路径");
            }
        }

        @Test
        @DisplayName("setOrientation 拒绝设备未声明的方向")
        void orientationMustBeSupported() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                moli.setOrientation(DisplayGeometry.Orientation.NORMAL);
                assertThrows(UnsupportedCapabilityError.class,
                        () -> moli.setOrientation(DisplayGeometry.Orientation.ROTATE_180));
            }
        }
    }

    @Nested
    @DisplayName("存储")
    class Storage {

        @Test
        @DisplayName("页区间越界报 OutOfBoundsError，不发任何命令")
        void pageRangeOutOfBounds() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                var storage = moli.storage().orElseThrow();
                device.clearWrites();
                assertThrows(OutOfBoundsError.class,
                        () -> moli.erasePages(storage.totalPages() - 1, 2));
                assertEquals(0, device.frames().size());
            }
        }

        @Test
        @DisplayName("写入数据超过容量报 StorageCapacityExceededError")
        void capacityExceeded() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                var storage = moli.storage().orElseThrow();
                assertThrows(InvalidArgumentError.class, () -> moli.writePages(-1, new byte[4]));
                assertThrows(InvalidArgumentError.class,
                        () -> moli.writePages(0, new byte[0]));
                // 起始页在范围内，但剩余容量装不下：这是容量不足而不是越界。
                assertThrows(StorageCapacityExceededError.class,
                        () -> moli.writePages(storage.totalPages() - 1,
                                new byte[(int) storage.pageSize() * 2]));
            }
        }

        @Test
        @DisplayName("readStorage 按地址逐字节读取并返回数据")
        void readStorageBytes() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            device.setFlashByte(0x012345, 0x5A);
            device.setFlashByte(0x012346, 0xA5);
            try (MoliDevice moli = openMini(device)) {
                assertArrayEquals(new byte[]{0x5A, (byte) 0xA5},
                        moli.readStorage(0x012345, 2));
            }
        }

        @Test
        @DisplayName("readStorage 越界报 OutOfBoundsError")
        void readStorageOutOfBounds() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                long capacity = moli.storage().orElseThrow().capacityBytes();
                assertThrows(OutOfBoundsError.class,
                        () -> moli.readStorage(capacity - 1, 2));
                assertThrows(InvalidArgumentError.class,
                        () -> moli.readStorage(0, 0));
            }
        }

        @Test
        @DisplayName("writePages 先擦除再按页写入，每页用满 256 字节暂存区")
        void writePagesErasesThenWrites() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            try (MoliDevice moli = openMini(device)) {
                device.clearWrites();
                moli.writePages(4, new byte[512]);
                List<byte[]> frames = device.frames();
                // 一次擦除 + 两页，每页 64 个暂存区帧 + 1 个写页命令。
                assertEquals(1 + 2 * (64 + 1), frames.size());
                assertArrayEquals(frame(0x03, 0x02, 0x00, 0x04, 0x00, 0x02), frames.getFirst());
                assertArrayEquals(frame(0x04, 0x00, 0x00, 0x00, 0x00, 0x00), frames.get(1));
                assertArrayEquals(frame(0x04, 0x3F, 0x00, 0x00, 0x00, 0x00), frames.get(64));
                assertArrayEquals(frame(0x03, 0x01, 0x00, 0x00, 0x04, 0x01), frames.get(65));
                // 第二页从页 5 开始（最后一帧）。
                assertArrayEquals(frame(0x03, 0x01, 0x00, 0x00, 0x05, 0x01), frames.getLast());
            }
        }
    }

    @Nested
    @DisplayName("输入订阅")
    class InputSubscriptionTest {

        @Test
        @DisplayName("MINI 按键由 ADC 采样适配为按下/释放事件")
        void keyEventsFromAdc() throws Exception {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            device.enableKey(9, 1000);
            try (MoliDevice moli = openMini(device)) {
                List<space.firok.moli.usb.api.domain.InputEvent> events = new ArrayList<>();
                try (InputSubscription subscription = moli.subscribeInput(events::add)) {
                    assertTrue(subscription.isActive());
                    device.pressKey(9, 400);
                    waitFor(() -> events.stream()
                            .anyMatch(e -> e.type() == space.firok.moli.usb.api.domain.InputEvent.Type.PRESSED));
                    device.pressKey(9, 1000);
                    waitFor(() -> events.stream()
                            .anyMatch(e -> e.type() == space.firok.moli.usb.api.domain.InputEvent.Type.RELEASED));
                }
                assertTrue(events.size() >= 2);
                assertEquals(space.firok.moli.usb.api.domain.InputEvent.Source.BUTTON_0,
                        events.getFirst().source());
            }
        }

        @Test
        @DisplayName("不声明输入能力时订阅报 UnsupportedCapabilityError")
        void requiresInputCapability() {
            ScriptedDevice device = new ScriptedDevice("COM-A");
            MoliOptions lite = options(DeviceProfile.lite(), device);
            try (MoliDevice moli = MoliUsb.over(device)
                    .open(candidate("LITE-1", "COM-A"), lite)) {
                assertThrows(UnsupportedCapabilityError.class,
                        () -> moli.subscribeInput(event -> { }));
            }
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("等待条件超时");
    }

    private static byte[] frame(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
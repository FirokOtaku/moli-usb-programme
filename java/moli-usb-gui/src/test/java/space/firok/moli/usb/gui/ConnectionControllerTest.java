package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.InputSubscription;
import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceInfo;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.domain.StorageGeometry;
import space.firok.moli.usb.api.error.DeviceBusyError;
import space.firok.moli.usb.api.error.DisconnectedError;
import space.firok.moli.usb.api.error.HandshakeFailedError;
import space.firok.moli.usb.api.error.InvalidArgumentError;
import space.firok.moli.usb.api.error.MoliUsbException;
import space.firok.moli.usb.api.error.ProtocolDesynchronizedError;
import space.firok.moli.usb.api.error.TimeoutError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 连接状态机测试。不启动 JavaFX，也不打开串口。
 *
 * <p>界面层只调用 {@link ConnectionController#connect()}、{@code disconnect()}、
 * {@code refresh()} 与 {@code submit(Runnable)}。控制器不会在调用线程上
 * {@code open}；这些测试在调用线程上直接调公共方法，并断言设备 IO 发生在 worker。</p>
 */
class ConnectionControllerTest {

    private FakeGateway gateway;
    private ConnectionController controller;

    @AfterEach
    void tearDown() {
        if (gateway != null) {
            gateway.release();
        }
        if (controller != null) {
            controller.closeAndAwait(Duration.ofSeconds(3));
        }
    }

    @Test
    @DisplayName("界面日志在记录时加时间、保留最近 200 条，清空不关闭连接")
    void clearLogLeavesConnectionIntact() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        DeviceCandidate selected = candidate("UID-1", "COM3");
        gateway.candidates.add(selected);
        controller.selectCandidate(selected);
        controller.connect();
        awaitIdle();

        for (int i = 0; i <= 200; i++) {
            controller.appendLog("entry " + i);
        }
        ConnectionSnapshot before = controller.snapshot();
        assertEquals(200, before.logLines().size());
        assertTrue(before.logLines().getFirst().matches("\\d{2}:\\d{2}:\\d{2} entry 1"));
        assertTrue(before.logLines().getLast().matches("\\d{2}:\\d{2}:\\d{2} entry 200"));
        assertEquals(before.logLines(), controller.snapshot().logLines());

        controller.clearLog();
        ConnectionSnapshot after = controller.snapshot();
        assertTrue(after.logLines().isEmpty());
        assertEquals(SessionState.CONNECTED, after.state());
        assertEquals(before.statusText(), after.statusText());
        assertSame(selected, after.selected());
        assertEquals(0, gateway.closeCount.get());
        controller.appendLog("after clear");
        assertEquals(1, controller.snapshot().logLines().size());
        assertTrue(controller.snapshot().logLines().getFirst().endsWith(" after clear"));
    }

    @Test
    @DisplayName("连接成功后锁定选择，断开后解锁")
    void lockAfterConnectAndUnlockAfterDisconnect() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        DeviceCandidate first = candidate("UID-1", "COM3");
        DeviceCandidate second = candidate("UID-2", "COM4");
        gateway.candidates.add(first);
        gateway.candidates.add(second);
        controller.selectCandidate(first);
        controller.selectProfile(DeviceProfile.mini());

        controller.connect();
        awaitIdle();

        ConnectionSnapshot connected = controller.snapshot();
        assertEquals(SessionState.CONNECTED, connected.state());
        assertTrue(connected.combosLocked(), "连接成功后必须锁定下拉框");
        assertFalse(connected.connectEnabled());
        assertTrue(connected.disconnectEnabled());
        assertEquals("已连接 UID-1 160x80", connected.statusText());
        assertEquals("已连接，尚未发送画面", connected.placeholderText());
        assertSame(first, connected.selected());

        controller.selectCandidate(second);
        controller.selectProfile(DeviceProfile.pro());
        assertSame(first, controller.snapshot().selected(), "锁定期间不得改选中的设备");
        assertEquals(DeviceProfile.MINI, controller.snapshot().profileName());

        gateway.candidates.clear();
        gateway.candidates.add(second);
        controller.refresh();
        awaitIdle();
        assertSame(first, controller.snapshot().selected(), "刷新不得改掉锁定的选择");
        assertTrue(controller.snapshot().combosLocked());

        controller.disconnect();
        awaitIdle();

        ConnectionSnapshot disconnected = controller.snapshot();
        assertEquals(SessionState.DISCONNECTED, disconnected.state());
        assertFalse(disconnected.combosLocked(), "断开完成后必须解锁");
        assertTrue(disconnected.connectEnabled());
        assertFalse(disconnected.disconnectEnabled());
        assertEquals("已断开", disconnected.statusText());
        assertEquals("未连接", disconnected.placeholderText());
        assertEquals(1, gateway.closeCount.get());
        assertSame(first, disconnected.selected());
    }

    @Test
    @DisplayName("握手失败后回到未连接、解锁，并按异常类型记录日志")
    void failureReturnsToDisconnected() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        DeviceCandidate port = candidate("UID-1", "COM3");
        gateway.candidates.add(port);
        gateway.failure = new HandshakeFailedError("open", "COM3", "握手失败");
        controller.selectCandidate(port);

        controller.connect();
        awaitIdle();

        ConnectionSnapshot snapshot = controller.snapshot();
        assertEquals(SessionState.DISCONNECTED, snapshot.state());
        assertFalse(snapshot.combosLocked());
        assertTrue(snapshot.connectEnabled());
        assertFalse(snapshot.disconnectEnabled());
        assertTrue(snapshot.statusText().startsWith("已断开："));
        assertTrue(snapshot.statusText().contains("握手失败"));
        assertTrue(snapshot.logLines().stream().anyMatch(line -> line.contains("握手失败")));
        assertEquals(0, gateway.closeCount.get(), "open 失败时控制器没有设备可关");
        gateway.failure = null;
        controller.connect();
        awaitIdle();
        assertEquals(SessionState.CONNECTED, controller.snapshot().state());
    }

    @Test
    @DisplayName("已连接时拒绝第二次连接")
    void secondConnectRefusedWhileConnected() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();
        assertEquals(1, gateway.openCount.get());

        controller.connect();
        awaitIdle();

        assertEquals(SessionState.CONNECTED, controller.snapshot().state());
        assertEquals(1, gateway.openCount.get(), "第二次连接不得再次 open");
        assertTrue(controller.snapshot().combosLocked());
        assertTrue(controller.snapshot().logLines().stream().anyMatch(line -> line.contains("拒绝连接")));
    }

    @Test
    @DisplayName("连接、断开、刷新和 submit 都不在调用线程上做设备 IO")
    void deviceIoRunsOnWorker() throws Exception {
        gateway = new FakeGateway();
        gateway.forbiddenCaller = Thread.currentThread();
        gateway.blockOpen = true;
        controller = controller(Duration.ofMillis(40));
        DeviceCandidate port = candidate("UID-1", "COM3");
        controller.selectCandidate(port);

        long started = System.nanoTime();
        controller.connect();
        long connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(connectMs < 300, "connect 不得在调用线程等待握手，耗时 " + connectMs + "ms");
        assertTrue(gateway.entered.await(2, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread(), gateway.openThread.get());
        assertEquals(ConnectionController.WORKER_NAME, gateway.openThread.get().getName());

        long disconnectStarted = System.nanoTime();
        controller.disconnect();
        long disconnectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - disconnectStarted);
        assertTrue(disconnectMs < 200, "disconnect 不得阻塞调用线程，耗时 " + disconnectMs + "ms");
        assertEquals(SessionState.DISCONNECTING, controller.snapshot().state());
        gateway.release();
        awaitIdle();
        assertEquals(SessionState.DISCONNECTED, controller.snapshot().state());
        assertFalse(controller.snapshot().combosLocked());
        assertEquals(1, gateway.closeCount.get());

        controller.refresh();
        awaitIdle();
        assertNotEquals(Thread.currentThread(), gateway.listThread.get());
        assertEquals(ConnectionController.WORKER_NAME, gateway.listThread.get().getName());

        AtomicReference<Thread> submitted = new AtomicReference<>();
        controller.submit(() -> submitted.set(Thread.currentThread()));
        awaitIdle();
        assertEquals(ConnectionController.WORKER_NAME, submitted.get().getName());
        assertNotEquals(Thread.currentThread(), submitted.get());
    }

    @Test
    @DisplayName("连接中轮询不改锁定选择；未连接时才轮询")
    void pollOnlyWhileDisconnectedAndKeepsLockedSelection() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofMillis(30));
        DeviceCandidate first = candidate("UID-1", "COM3");
        gateway.candidates.add(first);
        controller.selectCandidate(first);
        controller.start();
        awaitListCountAtLeast(1);

        controller.connect();
        awaitIdle();
        int listedWhileConnected = gateway.listCount.get();
        Thread.sleep(120);
        assertEquals(listedWhileConnected, gateway.listCount.get(), "已连接时不应继续轮询枚举");

        gateway.candidates.clear();
        gateway.candidates.add(candidate("UID-2", "COM9"));
        assertSame(first, controller.snapshot().selected());
    }

    @Test
    @DisplayName("屏幕尺寸为 0 时使用 profile 几何，不写死 160x80 或 64x64")
    void zeroSizeFallsBackToProfileGeometry() throws Exception {
        gateway = new FakeGateway();
        gateway.width = 0;
        gateway.height = 0;
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("PRO-1", "COM7"));
        controller.selectProfile(DeviceProfile.pro());
        controller.connect();
        awaitIdle();

        ConnectionSnapshot pro = controller.snapshot();
        assertEquals(320, pro.screenWidth());
        assertEquals(172, pro.screenHeight());
        assertTrue(pro.geometryFallback());
        assertEquals("已连接 PRO-1 320x172", pro.statusText());
        assertTrue(pro.warning().contains("320x172"));
        assertTrue(pro.warning().contains("PRO"));
        assertTrue(pro.warning().contains("没有给出"));
        assertFalse(pro.warning().contains("设备报告的屏幕尺寸"));
        assertFalse(pro.statusText().contains("64x64"));
        controller.disconnect();
        awaitIdle();

        gateway.width = 0;
        gateway.height = 0;
        controller.selectProfile(DeviceProfile.generic());
        controller.connect();
        awaitIdle();
        ConnectionSnapshot generic = controller.snapshot();
        assertEquals(0, generic.screenWidth());
        assertEquals(0, generic.screenHeight());
        assertFalse(generic.geometryFallback());
        assertEquals("已连接 PRO-1 0x0", generic.statusText());
        assertTrue(generic.warning().contains("不猜测"));
        assertFalse(generic.statusText().contains("160x80"));
        controller.disconnect();
        awaitIdle();

        gateway.width = 200;
        gateway.height = 100;
        controller.selectProfile(DeviceProfile.mini());
        controller.connect();
        awaitIdle();
        assertEquals("已连接 PRO-1 200x100", controller.snapshot().statusText());
        assertFalse(controller.snapshot().geometryFallback());
        assertNull(controller.snapshot().warning());
    }

    @Test
    @DisplayName("异常分类看类型，不看消息文本")
    void classifiesByTypeNotMessage() {
        DeviceBusyError error = new DeviceBusyError("open", "COM3", "握手失败");
        String text = ErrorTexts.describe(error);
        assertTrue(text.startsWith("设备忙"));
        assertFalse(text.startsWith("握手失败"));
    }

    @Test
    @DisplayName("候选标签使用 DeviceCandidate.OptionalInt，不按 VID/PID 过滤")
    void labelUsesNestedOptionalInt() {
        DeviceCandidate withIds = new DeviceCandidate(
                new DeviceId("UID", "COM5"),
                "COM5 某串口",
                DeviceCandidate.OptionalInt.of(0x303A),
                DeviceCandidate.OptionalInt.empty(),
                Optional.empty(),
                true);
        String label = CandidateLabels.label(withIds);
        assertTrue(label.contains("COM5"));
        assertTrue(label.contains("VID 303A"));
        assertTrue(label.contains("PID ?"));

        DeviceCandidate plain = candidate("UID", "COM6");
        assertEquals("COM6", CandidateLabels.label(plain));
    }

    @Test
    @DisplayName("submitDevice 只在已连接时于 worker 上拿到设备，未连接则空操作")
    void submitDeviceRunsOnWorkerOnlyWhileConnected() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        AtomicInteger skipped = new AtomicInteger();
        assertFalse(controller.submitDevice(device -> skipped.incrementAndGet()));
        assertEquals(0, skipped.get());

        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();

        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<MoliDevice> seen = new AtomicReference<>();
        assertTrue(controller.submitDevice(device -> {
            thread.set(Thread.currentThread());
            seen.set(device);
        }));
        awaitIdle();
        assertNotNull(seen.get());
        assertEquals(ConnectionController.WORKER_NAME, thread.get().getName());
        assertNotEquals(Thread.currentThread(), thread.get());

        controller.disconnect();
        awaitIdle();
        assertFalse(controller.submitDevice(device -> skipped.incrementAndGet()));
        assertEquals(0, skipped.get());
        assertFalse(controller.snapshot().combosLocked());
    }

    @Test
    @DisplayName("发送中的断链按类型关闭会话、解锁选择并写入日志")
    void linkLossDuringSendClosesSessionByType() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();
        assertTrue(controller.snapshot().combosLocked());

        assertTrue(controller.submitDevice(device -> {
            throw new DisconnectedError("streamImage", "COM3", "参数无效");
        }));
        awaitIdle();

        ConnectionSnapshot snapshot = controller.snapshot();
        assertEquals(SessionState.DISCONNECTED, snapshot.state());
        assertFalse(snapshot.combosLocked(), "断链后必须解锁设备和 profile");
        assertTrue(snapshot.connectEnabled());
        assertTrue(snapshot.disconnectEnabled(), "等待重连时仍可显式断开");
        assertTrue(snapshot.statusText().contains("连接已断开"));
        assertFalse(snapshot.statusText().startsWith("参数无效"), "不能按消息文本把断链当成参数错误");
        assertEquals(1, gateway.closeCount.get());
        assertTrue(snapshot.logLines().stream().anyMatch(line -> line.contains("连接已断开")));
    }

    @Test
    void reconnectsSameCandidateAfterInvoluntaryLoss() throws Exception {
        gateway = new FakeGateway();
        var target = candidate("UID-1", "COM3");
        gateway.candidates.add(target);
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(target);
        controller.selectProfile(DeviceProfile.mini());
        controller.connect();
        awaitIdle();
        CountDownLatch reconnected = new CountDownLatch(1);
        controller.setListener(snapshot -> {
            if (snapshot.state() == SessionState.CONNECTED && gateway.openCount.get() == 2) {
                reconnected.countDown();
            }
        });
        assertTrue(controller.submitDevice(device -> {
            throw new DisconnectedError("streamImage", "COM3", "写入失败");
        }));
        awaitIdle();
        assertEquals(1, gateway.openCount.get());
        assertTrue(reconnected.await(3, TimeUnit.SECONDS));
        assertEquals(DeviceProfile.MINI, controller.snapshot().profileName());
    }

    @Test
    @DisplayName("超时也按断链关闭；消息写成握手失败也不能改分类")
    void timeoutDuringSendClosesSession() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();

        assertTrue(controller.submitDevice(device -> {
            throw new TimeoutError("fillRect", "COM3", "握手失败");
        }));
        awaitIdle();

        ConnectionSnapshot snapshot = controller.snapshot();
        assertEquals(SessionState.DISCONNECTED, snapshot.state());
        assertFalse(snapshot.combosLocked());
        assertTrue(snapshot.statusText().startsWith("已断开：超时"));
        assertFalse(snapshot.statusText().startsWith("握手失败"));
        assertEquals(1, gateway.closeCount.get());
    }

    @Test
    @DisplayName("参数错误即使消息写着断开，也不关闭会话")
    void argumentErrorDoesNotDisconnect() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();

        assertTrue(controller.submitDevice(device -> {
            throw new InvalidArgumentError("streamImage", "COM3", "设备已断开");
        }));
        awaitIdle();

        ConnectionSnapshot snapshot = controller.snapshot();
        assertEquals(SessionState.CONNECTED, snapshot.state());
        assertTrue(snapshot.combosLocked());
        assertEquals(0, gateway.closeCount.get());
        assertTrue(snapshot.logLines().stream().anyMatch(line -> line.contains("参数无效")));
        assertFalse(snapshot.statusText().startsWith("已断开"));
    }

    @Test
    @DisplayName("连接后发布设备能力与方向，断开后清空，不按 profile 名猜测")
    void publishesCapabilitiesAndClearsThemOnDisconnect() throws Exception {
        gateway = new FakeGateway();
        gateway.capabilities = DeviceCapabilities.of(true, false, true, false, false, true);
        gateway.orientations = List.of(
                DisplayGeometry.Orientation.NORMAL,
                DisplayGeometry.Orientation.ROTATE_90);
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.selectProfile(DeviceProfile.mini());
        controller.connect();
        awaitIdle();

        ConnectionSnapshot connected = controller.snapshot();
        assertEquals(DeviceProfile.MINI, connected.profileName());
        assertNotNull(connected.capabilities());
        assertTrue(connected.capabilities().rgbLeds());
        assertFalse(connected.capabilities().backlight());
        assertTrue(connected.capabilities().buttonInput());
        assertEquals(List.of(DisplayGeometry.Orientation.NORMAL, DisplayGeometry.Orientation.ROTATE_90),
                connected.orientations());

        controller.disconnect();
        awaitIdle();
        ConnectionSnapshot disconnected = controller.snapshot();
        assertNull(disconnected.capabilities());
        assertTrue(disconnected.orientations().isEmpty());
    }

    @Test
    @DisplayName("发送进行中跳过排空；空闲排空不调用 probeLink")
    void probeTickSkippedWhileSendInFlight() throws Exception {
        gateway = new FakeGateway();
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(controller.submitDevice(device -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertFalse(controller.probeTick(), "发送进行中不得把排空排进 worker");
        assertEquals(0, gateway.drainCount.get());
        assertEquals(0, gateway.probeCount.get());

        release.countDown();
        awaitIdle();
        controller.markContentSendInFlight(true);
        assertFalse(controller.probeTick(), "内容发送尚未结束时也要跳过");
        assertEquals(0, gateway.drainCount.get());
        assertEquals(0, gateway.probeCount.get());

        controller.markContentSendInFlight(false);
        assertTrue(controller.probeTick());
        awaitIdle();
        assertEquals(1, gateway.drainCount.get());
        assertEquals(0, gateway.probeCount.get(), "空闲不得发送 00 30 00 01 00 00");
    }

    @Test
    @DisplayName("空闲排空超时不断开；传输断开才关闭会话")
    void probeLinkLossUsesExistingPath() throws Exception {
        gateway = new FakeGateway();
        gateway.drainError.set(new TimeoutError("discardPending", "COM3", "在 800 ms 内未收到响应"));
        controller = controller(Duration.ofHours(1));
        controller.selectCandidate(candidate("UID-1", "COM3"));
        controller.connect();
        awaitIdle();

        assertTrue(controller.probeTick());
        awaitIdle();
        assertEquals(SessionState.CONNECTED, controller.snapshot().state());
        assertEquals(0, gateway.closeCount.get());
        assertEquals(0, gateway.probeCount.get());

        gateway.drainError.set(new ProtocolDesynchronizedError("discardPending", "COM3", "参数无效"));
        assertTrue(controller.probeTick());
        awaitIdle();

        ConnectionSnapshot snapshot = controller.snapshot();
        assertEquals(SessionState.DISCONNECTED, snapshot.state());
        assertFalse(snapshot.combosLocked());
        assertTrue(snapshot.connectEnabled());
        assertTrue(snapshot.statusText().contains("协议失步"));
        assertFalse(snapshot.statusText().startsWith("参数无效"));
        assertNull(snapshot.capabilities());
        assertEquals(1, gateway.closeCount.get());
    }

    private ConnectionController controller(Duration pollInterval) {
        return new ConnectionController(gateway, Runnable::run, pollInterval);
    }

    private void awaitIdle() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        controller.submit(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "worker 没有在时限内结束已提交任务");
    }

    private void awaitListCountAtLeast(int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (gateway.listCount.get() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("枚举次数没有达到 " + count + "，实际 " + gateway.listCount.get());
            }
            Thread.sleep(15);
        }
    }

    private static DeviceCandidate candidate(String uid, String port) {
        return new DeviceCandidate(
                new DeviceId(uid, port),
                port,
                DeviceCandidate.OptionalInt.empty(),
                DeviceCandidate.OptionalInt.empty(),
                Optional.empty(),
                true);
    }

    private static final class FakeGateway implements DeviceSessionGateway {
        final List<DeviceCandidate> candidates = new CopyOnWriteArrayList<>();
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger listCount = new AtomicInteger();
        final AtomicReference<Thread> openThread = new AtomicReference<>();
        final AtomicReference<Thread> listThread = new AtomicReference<>();
        final List<DeviceProfile> openedProfiles = new CopyOnWriteArrayList<>();
        volatile Thread forbiddenCaller;
        volatile MoliUsbException failure;
        volatile int width = 160;
        volatile int height = 80;
        volatile DeviceCapabilities capabilities = DeviceCapabilities.of(true, false, false, false, false, false);
        volatile List<DisplayGeometry.Orientation> orientations = List.of();
        final AtomicInteger probeCount = new AtomicInteger();
        final AtomicReference<MoliUsbException> probeError = new AtomicReference<>();
        final AtomicInteger drainCount = new AtomicInteger();
        final AtomicReference<MoliUsbException> drainError = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean blockOpen;

        @Override
        public List<DeviceCandidate> listCandidates() {
            rejectCallerThread();
            listThread.set(Thread.currentThread());
            listCount.incrementAndGet();
            return List.copyOf(candidates);
        }

        @Override
        public MoliDevice open(DeviceCandidate candidate, DeviceProfile profile) {
            rejectCallerThread();
            openThread.set(Thread.currentThread());
            openCount.incrementAndGet();
            openedProfiles.add(profile);
            entered.countDown();
            if (blockOpen) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new HandshakeFailedError("open", candidate.id().toString(), "测试等待超时");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new HandshakeFailedError("open", candidate.id().toString(), interrupted);
                }
            }
            if (failure != null) {
                throw failure;
            }
            return new FakeDevice(candidate, profile.name(), width, height, closeCount,
                    capabilities, List.copyOf(orientations), probeCount, probeError, drainCount, drainError);
        }

        void release() {
            release.countDown();
        }

        private void rejectCallerThread() {
            if (Thread.currentThread() == forbiddenCaller) {
                throw new AssertionError("设备 IO 跑在了调用线程上: " + Thread.currentThread().getName());
            }
        }
    }

    private static final class FakeDevice implements MoliDevice {
        private final DeviceId id;
        private final String profileName;
        private final int width;
        private final int height;
        private final AtomicInteger closeCount;
        private final DeviceCapabilities capabilities;
        private final List<DisplayGeometry.Orientation> orientations;
        private final AtomicInteger probeCount;
        private final AtomicReference<MoliUsbException> probeError;
        private final AtomicInteger drainCount;
        private final AtomicReference<MoliUsbException> drainError;
        private boolean closed;

        private FakeDevice(DeviceId id, String profileName, int width, int height, AtomicInteger closeCount,
                           DeviceCapabilities capabilities, List<DisplayGeometry.Orientation> orientations,
                           AtomicInteger probeCount, AtomicReference<MoliUsbException> probeError,
                           AtomicInteger drainCount, AtomicReference<MoliUsbException> drainError) {
            this.id = id;
            this.profileName = profileName;
            this.width = width;
            this.height = height;
            this.closeCount = closeCount;
            this.capabilities = capabilities;
            this.orientations = orientations;
            this.probeCount = probeCount;
            this.probeError = probeError;
            this.drainCount = drainCount;
            this.drainError = drainError;
        }

        private FakeDevice(DeviceCandidate candidate, String profileName, int width, int height,
                           AtomicInteger closeCount, DeviceCapabilities capabilities,
                           List<DisplayGeometry.Orientation> orientations, AtomicInteger probeCount,
                           AtomicReference<MoliUsbException> probeError, AtomicInteger drainCount,
                           AtomicReference<MoliUsbException> drainError) {
            this(candidate.id(), profileName, width, height, closeCount, capabilities, orientations,
                    probeCount, probeError, drainCount, drainError);
        }

        @Override
        public DeviceId id() {
            return id;
        }

        @Override
        public DeviceInfo info() {
            return new DeviceInfo(1, id.uid(), width, height, Optional.empty(), Map.of());
        }

        @Override
        public DeviceCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public Optional<DisplayGeometry> display() {
            if (orientations.isEmpty() || width <= 0 || height <= 0) {
                return Optional.empty();
            }
            return Optional.of(new DisplayGeometry(width, height, DisplayGeometry.Origin.TOP_LEFT,
                    orientations, DisplayGeometry.PixelFormat.RGB565));
        }

        @Override
        public Optional<StorageGeometry> storage() {
            return Optional.empty();
        }

        @Override
        public String profileName() {
            return profileName;
        }

        @Override
        public void setOrientation(DisplayGeometry.Orientation orientation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setBacklight(int pwm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLed(int index, int red, int green, int blue) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void applyLeds() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void probeLink() {
            probeCount.incrementAndGet();
            MoliUsbException error = probeError.get();
            if (error != null) {
                throw error;
            }
        }

        @Override
        public void discardPending() {
            drainCount.incrementAndGet();
            MoliUsbException error = drainError.get();
            if (error != null) {
                throw error;
            }
        }

        @Override
        public void fillRect(Rect rect, Rgb565 color) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showStoredColorImage(int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showStoredMonoImage(int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void streamImage(Rect rect, int[] pixels) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void streamImageRle(Rect rect, int[] pixels, int maxFrames) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] readStorage(long address, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void erasePages(long startPage, long pageCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writePages(long startPage, byte[] data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputSubscription subscribeInput(Consumer<space.firok.moli.usb.api.domain.InputEvent> listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            closeCount.incrementAndGet();
        }
    }
}

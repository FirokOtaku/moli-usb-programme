package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceInfo;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.error.MoliUsbException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 单设备连接状态机。
 *
 * <p>本类不引用 JavaFX。枚举（会碰到 jSerialComm）、打开/握手、关闭都只在名为
 * {@code moli-usb-gui-io} 的单线程 worker 上执行，公共方法只改内存状态并把任务
 * {@link #submit(Runnable)} 提交给该 worker。界面层必须始终这样调用，不得在
 * JavaFX 线程上直接 {@code open}。监听器经构造时传入的 {@link Executor} 投递；
 * JavaFX 层应传入 {@code Platform::runLater}。</p>
 *
 * <p>画面发送走 {@link #submitDevice(java.util.function.Consumer)}，与握手共用这一条
 * worker，避免并发命令错配响应。调用方拿不到 {@link MoliDevice}，也不能在
 * JavaFX 线程上使用它。断链异常按类型关闭会话并解锁选择。空闲探活也走这条
 * worker，但 worker 正忙时直接跳过，不把探活排进队列。</p>
 */
public final class ConnectionController implements AutoCloseable {

    /** 未连接时轮询候选设备的间隔。 */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(1500);

    /** 已连接且设备空闲时的探活间隔。 */
    public static final Duration DEFAULT_PROBE_INTERVAL = Duration.ofSeconds(2);

    private static final int MAX_LOG_LINES = 200;
    static final String WORKER_NAME = "moli-usb-gui-io";

    private enum IdleKind {
        NEVER,
        CLEAN,
        FAILED
    }

    private final DeviceSessionGateway gateway;
    private final ScheduledExecutorService worker;
    private final Executor listenerExecutor;
    private final Duration pollInterval;
    private final Object monitor = new Object();

    private Consumer<ConnectionSnapshot> listener;
    private volatile Thread workerThread;
    private ScheduledFuture<?> pollTask;
    private boolean started;
    private boolean closed;

    private SessionState state = SessionState.DISCONNECTED;
    private IdleKind idleKind = IdleKind.NEVER;
    private String idleReason;
    private int sessionEpoch;
    private boolean cancelConnect;

    private List<DeviceCandidate> candidates = List.of();
    private DeviceCandidate selected;
    private DeviceProfile profile = DeviceProfile.mini();
    private MoliDevice device;
    private String connectedUid;
    private int screenWidth;
    private int screenHeight;
    private boolean geometryFallback;
    private String warning;
    private DeviceCapabilities capabilities;
    private List<DisplayGeometry.Orientation> orientations = List.of();
    private volatile boolean contentSendInFlight;
    private final AtomicInteger capabilityCommands = new AtomicInteger();
    private final AtomicInteger workerBusy = new AtomicInteger();
    private ScheduledExecutorService probeScheduler;
    private final List<String> logLines = new ArrayList<>();

    public ConnectionController(DeviceSessionGateway gateway, Executor listenerExecutor) {
        this(gateway, listenerExecutor, DEFAULT_POLL_INTERVAL);
    }

    public ConnectionController(DeviceSessionGateway gateway, Executor listenerExecutor, Duration pollInterval) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.listenerExecutor = Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("pollInterval must be positive");
        }
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, WORKER_NAME);
            thread.setDaemon(false);
            return thread;
        });
    }

    public void setListener(Consumer<ConnectionSnapshot> listener) {
        synchronized (monitor) {
            this.listener = listener;
        }
        publish();
    }

    public ConnectionSnapshot snapshot() {
        synchronized (monitor) {
            return buildSnapshotLocked();
        }
    }

    /**
     * 开始在未连接时轮询候选。只提交到 worker，不在调用线程枚举。
     */
    public void start() {
        synchronized (monitor) {
            if (closed || started) {
                return;
            }
            started = true;
            executeOnWorker(this::pollTick);
        }
    }

    /**
     * 手动刷新候选。连接锁定期间仍可列出端口，但不会改掉已锁定的选择。
     */
    public void refresh() {
        submit(this::refreshOnWorker);
    }

    /**
     * 仅在未连接时接受选择。锁定期间忽略，避免刷新或界面回写改掉正在使用的设备。
     */
    public void selectCandidate(DeviceCandidate candidate) {
        synchronized (monitor) {
            if (closed || state != SessionState.DISCONNECTED) {
                return;
            }
            selected = candidate;
        }
        publish();
    }

    /** 仅在未连接时接受 profile。锁定期间忽略。 */
    public void selectProfile(DeviceProfile profile) {
        Objects.requireNonNull(profile, "profile");
        synchronized (monitor) {
            if (closed || state != SessionState.DISCONNECTED) {
                return;
            }
            this.profile = profile;
        }
        publish();
    }

    /**
     * 请求连接。立刻返回；握手在 worker 上执行，可能阻塞数秒。
     * 已连接或正在连接时拒绝第二次连接，不会再调用 {@code open}。
     */
    public void connect() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            if (state != SessionState.DISCONNECTED) {
                addLogLocked("拒绝连接：当前状态为 " + state + "，一次只能连接一台设备");
            } else if (selected == null) {
                addLogLocked("拒绝连接：未选择串口");
            } else {
                sessionEpoch++;
                int epoch = sessionEpoch;
                cancelConnect = false;
                state = SessionState.CONNECTING;
                warning = null;
                DeviceCandidate candidate = selected;
                DeviceProfile chosen = profile;
                // 与 disconnect 同锁提交，避免断开任务排到 open 前面，或第二次 open 插队。
                executeOnWorker(() -> {
                    try {
                        openOnWorker(epoch, candidate, chosen);
                    } catch (RuntimeException error) {
                        failUnexpected(epoch, error);
                    }
                });
            }
        }
        publish();
    }

    /**
     * 请求断开。立刻返回，不在调用线程等待握手结束或 {@code close}。
     * 若仍在连接中，worker 在 {@code open} 返回后关闭设备。
     */
    public void disconnect() {
        synchronized (monitor) {
            if (closed || state == SessionState.DISCONNECTED || state == SessionState.DISCONNECTING) {
                return;
            }
            cancelConnect = true;
            state = SessionState.DISCONNECTING;
            int epoch = sessionEpoch;
            executeOnWorker(() -> finishDisconnect(epoch));
        }
        publish();
    }

    /**
     * 把任务提交到同一条设备 worker。不暴露设备；发送画面用 {@link #submitDevice(Consumer)}。
     */
    public void submit(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (monitor) {
            if (closed) {
                throw new IllegalStateException("连接控制器已关闭");
            }
            executeOnWorker(task);
        }
    }

    /**
     * 仅在已连接时，于设备 worker 上调用 {@code action}。
     * 未连接或已关闭时不调用 {@code action}，立即返回 false。
     * {@code action} 不会在调用线程执行。
     *
     * @return 任务已进入 worker 时为 true
     */
    public boolean submitDevice(Consumer<MoliDevice> action) {
        return submitDevice(action, null);
    }

    /**
     * 同 {@link #submitDevice(Consumer)}。任务被接受后，{@code onComplete} 一定在 worker 上执行，
     * 无论 {@code action} 有没有跑、有没有抛异常。未接受时不调用 {@code onComplete}。
     */
    boolean submitDevice(Consumer<MoliDevice> action, Runnable onComplete) {
        Objects.requireNonNull(action, "action");
        synchronized (monitor) {
            if (closed || state != SessionState.CONNECTED || device == null) {
                return false;
            }
            int epoch = sessionEpoch;
            try {
                executeOnWorker(() -> {
                    try {
                        runDeviceAction(epoch, action);
                    } finally {
                        if (onComplete != null) {
                            onComplete.run();
                        }
                    }
                });
            } catch (IllegalStateException rejected) {
                return false;
            }
            return true;
        }
    }

    /**
     * 记一条界面日志。可从内容线程调用，不做设备 IO。
     */
    void appendLog(String line) {
        Objects.requireNonNull(line, "line");
        synchronized (monitor) {
            if (closed) {
                return;
            }
            addLogLocked(line);
        }
        publish();
    }

    /** 调用方已持有 {@link #monitor}。只入队，不在当前线程执行设备 IO。 */
    private void executeOnWorker(Runnable task) {
        workerBusy.incrementAndGet();
        try {
            worker.execute(() -> {
                try {
                    workerThread = Thread.currentThread();
                    task.run();
                } finally {
                    workerBusy.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException rejected) {
            workerBusy.decrementAndGet();
            throw new IllegalStateException("连接控制器已关闭", rejected);
        }
    }

    /**
     * 已连接后大约每 {@code interval} 探活一次。定时器不在设备 worker 上跑；
     * 这一拍若设备正忙就跳过，不把 {@code probeLink} 排进队列。
     */
    public void startIdleProbe(Duration interval) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        ScheduledExecutorService created = null;
        synchronized (monitor) {
            if (closed || probeScheduler != null) {
                return;
            }
            created = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "moli-usb-gui-probe");
                thread.setDaemon(true);
                return thread;
            });
            probeScheduler = created;
        }
        try {
            created.scheduleWithFixedDelay(this::scheduledProbe, interval.toMillis(), interval.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // close 已经停掉探活定时器。
        }
    }

    /**
     * 内容线程在准备或发送画面时置位。探活看到此标志就跳过，即使 worker 还没排上任务。
     */
    void markContentSendInFlight(boolean inFlight) {
        contentSendInFlight = inFlight;
    }

    /**
     * 能力命令（方向、背光、灯）与画面共用设备 worker。入队前就计入在途，避免探活插队。
     *
     * @return 任务已进入 worker 时为 true
     */
    boolean submitCapability(Consumer<MoliDevice> action, Runnable onComplete) {
        Objects.requireNonNull(action, "action");
        synchronized (monitor) {
            if (closed || state != SessionState.CONNECTED || device == null) {
                return false;
            }
            capabilityCommands.incrementAndGet();
            int epoch = sessionEpoch;
            try {
                executeOnWorker(() -> {
                    try {
                        runDeviceAction(epoch, action);
                    } finally {
                        try {
                            if (onComplete != null) {
                                onComplete.run();
                            }
                        } finally {
                            capabilityCommands.updateAndGet(value -> Math.max(0, value - 1));
                        }
                    }
                });
            } catch (IllegalStateException rejected) {
                capabilityCommands.updateAndGet(value -> Math.max(0, value - 1));
                return false;
            }
            return true;
        }
    }

    /**
     * 空闲时提交一次 {@link MoliDevice#probeLink()}。画面发送、能力命令或 worker
     * 上已有任务时返回 false，且不入队。
     */
    boolean probeTick() {
        synchronized (monitor) {
            if (closed || state != SessionState.CONNECTED || device == null) {
                return false;
            }
            if (workerBusy.get() > 0 || contentSendInFlight || capabilityCommands.get() > 0) {
                return false;
            }
            int epoch = sessionEpoch;
            try {
                executeOnWorker(() -> runDeviceAction(epoch, MoliDevice::probeLink));
            } catch (IllegalStateException rejected) {
                return false;
            }
            return true;
        }
    }

    private void scheduledProbe() {
        try {
            probeTick();
        } catch (RuntimeException ignored) {
            // 一次检查失败不能停掉后续探活。
        }
    }

    /**
     * 关闭控制器并在 worker 上关闭设备。不等待，避免卡住调用线程。
     */
    @Override
    public void close() {
        ScheduledFuture<?> pendingPoll;
        ScheduledExecutorService probe;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            cancelConnect = true;
            pendingPoll = pollTask;
            pollTask = null;
            probe = probeScheduler;
            probeScheduler = null;
        }
        if (pendingPoll != null) {
            pendingPoll.cancel(false);
        }
        if (probe != null) {
            probe.shutdownNow();
        }
        try {
            worker.execute(() -> {
                workerThread = Thread.currentThread();
                MoliDevice toClose;
                synchronized (monitor) {
                    toClose = device;
                    device = null;
                    state = SessionState.DISCONNECTED;
                }
                closeQuietly(toClose);
            });
        } catch (RejectedExecutionException ignored) {
            // 已经关闭。
        }
        worker.shutdown();
    }

    /**
     * 窗口退出时等待 worker 结束，使串口占用被释放。不要从「断开」按钮调用。
     */
    public void closeAndAwait(Duration timeout) {
        close();
        try {
            worker.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void pollTick() {
        workerBusy.incrementAndGet();
        try {
            pollTickBody();
        } finally {
            workerBusy.decrementAndGet();
            schedulePoll();
        }
    }

    private void pollTickBody() {
        try {
            assertOnWorker();
            boolean poll;
            synchronized (monitor) {
                poll = !closed && state == SessionState.DISCONNECTED;
            }
            if (poll) {
                List<DeviceCandidate> found = gateway.listCandidates();
                synchronized (monitor) {
                    if (!closed && state == SessionState.DISCONNECTED) {
                        replaceCandidatesLocked(found, false);
                    }
                }
                publish();
            }
        } catch (RuntimeException error) {
            synchronized (monitor) {
                if (!closed) {
                    addLogLocked("枚举串口失败：" + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : "：" + error.getMessage()));
                }
            }
            publish();
        }
    }

    private void schedulePoll() {
        try {
            ScheduledFuture<?> next = worker.schedule(this::pollTick, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
            synchronized (monitor) {
                if (closed || !started) {
                    next.cancel(false);
                    return;
                }
                pollTask = next;
            }
        } catch (RejectedExecutionException ignored) {
            // 关闭过程中不再排队。
        }
    }

    private void refreshOnWorker() {
        assertOnWorker();
        try {
            List<DeviceCandidate> found = gateway.listCandidates();
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                replaceCandidatesLocked(found, state != SessionState.DISCONNECTED);
            }
            publish();
        } catch (RuntimeException error) {
            synchronized (monitor) {
                if (!closed) {
                    addLogLocked("枚举串口失败：" + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : "：" + error.getMessage()));
                }
            }
            publish();
        }
    }

    private void openOnWorker(int epoch, DeviceCandidate candidate, DeviceProfile chosen) {
        assertOnWorker();
        if (abortIfCancelled(epoch)) {
            publish();
            return;
        }
        MoliDevice opened;
        try {
            opened = gateway.open(candidate, chosen);
        } catch (MoliUsbException error) {
            fail(epoch, error);
            return;
        } catch (RuntimeException error) {
            failUnexpected(epoch, error);
            return;
        }
        if (abortIfCancelled(epoch)) {
            closeQuietly(opened);
            publish();
            return;
        }
        try {
            applyConnected(epoch, opened, chosen);
        } catch (MoliUsbException error) {
            closeQuietly(opened);
            fail(epoch, error);
        } catch (RuntimeException error) {
            closeQuietly(opened);
            failUnexpected(epoch, error);
        }
    }

    private void applyConnected(int epoch, MoliDevice opened, DeviceProfile chosen) {
        DeviceInfo info = opened.info();
        int width = info.screenWidth();
        int height = info.screenHeight();
        boolean fallback = false;
        String nextWarning = null;
        if (width == 0 || height == 0) {
            Optional<DisplayGeometry> geometry = chosen.display();
            if (geometry.isPresent()) {
                width = geometry.get().width();
                height = geometry.get().height();
                fallback = true;
                nextWarning = "设备报告的屏幕尺寸为 " + info.screenWidth() + "x" + info.screenHeight()
                        + "，已改用 profile " + chosen.name() + " 的显示几何 " + width + "x" + height;
            } else {
                nextWarning = "设备报告的屏幕尺寸为 " + info.screenWidth() + "x" + info.screenHeight()
                        + "，profile " + chosen.name() + " 没有可用的显示几何，不猜测画面尺寸";
            }
        }
        DeviceCapabilities nextCapabilities = opened.capabilities();
        List<DisplayGeometry.Orientation> nextOrientations = opened.display()
                .map(DisplayGeometry::orientations)
                .orElse(List.of());
        boolean publishNeeded;
        synchronized (monitor) {
            if (sessionEpoch != epoch || cancelConnect || state == SessionState.DISCONNECTING || closed) {
                publishNeeded = false;
            } else {
                device = opened;
                opened = null;
                connectedUid = info.uid();
                screenWidth = width;
                screenHeight = height;
                geometryFallback = fallback;
                warning = nextWarning;
                capabilities = nextCapabilities;
                orientations = List.copyOf(nextOrientations);
                if (nextWarning != null) {
                    addLogLocked(nextWarning);
                }
                state = SessionState.CONNECTED;
                idleKind = IdleKind.NEVER;
                idleReason = null;
                publishNeeded = true;
            }
        }
        if (opened != null) {
            closeQuietly(opened);
            synchronized (monitor) {
                if (sessionEpoch == epoch && state != SessionState.CONNECTED) {
                    becomeDisconnectedLocked(IdleKind.CLEAN, "用户取消连接");
                }
            }
            publish();
            return;
        }
        if (publishNeeded) {
            publish();
        }
    }

    private void finishDisconnect(int epoch) {
        assertOnWorker();
        MoliDevice toClose = null;
        boolean changed = false;
        synchronized (monitor) {
            if (closed || sessionEpoch != epoch) {
                return;
            }
            if (state == SessionState.CONNECTING) {
                // open 还没开始，或正占着这条 worker（此时本任务不会同时运行）。
                // 取消标志已置位，openOnWorker 返回后会关闭。
                cancelConnect = true;
                return;
            }
            if (state == SessionState.DISCONNECTING || state == SessionState.CONNECTED) {
                toClose = device;
                device = null;
                becomeDisconnectedLocked(IdleKind.CLEAN, null);
                changed = true;
            }
        }
        closeQuietly(toClose);
        if (changed) {
            publish();
        }
    }

    private boolean abortIfCancelled(int epoch) {
        synchronized (monitor) {
            if (closed || sessionEpoch != epoch) {
                return true;
            }
            if (cancelConnect || state == SessionState.DISCONNECTING) {
                becomeDisconnectedLocked(IdleKind.CLEAN, "用户取消连接");
                return true;
            }
            return state != SessionState.CONNECTING;
        }
    }

    private void fail(int epoch, MoliUsbException error) {
        synchronized (monitor) {
            if (closed || sessionEpoch != epoch) {
                return;
            }
            device = null;
            becomeDisconnectedLocked(IdleKind.FAILED, ErrorTexts.describe(error));
        }
        publish();
    }

    private void runDeviceAction(int epoch, Consumer<MoliDevice> action) {
        assertOnWorker();
        MoliDevice current;
        synchronized (monitor) {
            if (closed || sessionEpoch != epoch || state != SessionState.CONNECTED || device == null) {
                return;
            }
            current = device;
        }
        try {
            action.accept(current);
        } catch (MoliUsbException error) {
            if (LinkErrors.isLinkLoss(error)) {
                failLink(epoch, error);
            } else {
                logUnlocked(ErrorTexts.describe(error));
            }
        } catch (RuntimeException error) {
            logUnlocked("发送画面失败：" + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage()));
        }
    }

    /**
     * 发送中途断链：关掉设备，走与握手失败相同的解锁和日志路径。必须在 worker 上调用。
     */
    private void failLink(int epoch, MoliUsbException error) {
        assertOnWorker();
        MoliDevice toClose;
        synchronized (monitor) {
            if (closed || sessionEpoch != epoch || state == SessionState.DISCONNECTED) {
                return;
            }
            toClose = device;
            device = null;
            becomeDisconnectedLocked(IdleKind.FAILED, ErrorTexts.describe(error));
        }
        closeQuietly(toClose);
        publish();
    }

    private void logUnlocked(String line) {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            addLogLocked(line);
        }
        publish();
    }

    private void failUnexpected(int epoch, RuntimeException error) {
        synchronized (monitor) {
            if (closed || sessionEpoch != epoch) {
                return;
            }
            device = null;
            String reason = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : "：" + error.getMessage());
            becomeDisconnectedLocked(IdleKind.FAILED, reason);
        }
        publish();
    }

    private void becomeDisconnectedLocked(IdleKind kind, String reason) {
        state = SessionState.DISCONNECTED;
        cancelConnect = false;
        connectedUid = null;
        screenWidth = 0;
        screenHeight = 0;
        geometryFallback = false;
        warning = null;
        capabilities = null;
        orientations = List.of();
        idleKind = kind;
        idleReason = reason;
        if (kind == IdleKind.FAILED) {
            addLogLocked(reason == null ? "已断开：未知错误" : "已断开：" + reason);
        } else if (kind == IdleKind.CLEAN) {
            addLogLocked(reason == null ? "已断开" : "已断开：" + reason);
        }
    }

    private void replaceCandidatesLocked(List<DeviceCandidate> found, boolean locked) {
        if (!locked) {
            String key = selected == null ? null : selected.id().transportKey();
            candidates = List.copyOf(found);
            if (key == null) {
                selected = null;
                return;
            }
            selected = found.stream()
                    .filter(candidate -> candidate.id().transportKey().equals(key))
                    .findFirst()
                    .orElse(null);
            return;
        }
        DeviceCandidate keep = selected;
        List<DeviceCandidate> merged = new ArrayList<>();
        if (keep != null) {
            merged.add(keep);
        }
        for (DeviceCandidate candidate : found) {
            if (keep == null || !candidate.id().transportKey().equals(keep.id().transportKey())) {
                merged.add(candidate);
            }
        }
        candidates = List.copyOf(merged);
    }

    private void closeQuietly(MoliDevice toClose) {
        if (toClose == null) {
            return;
        }
        try {
            toClose.close();
        } catch (RuntimeException error) {
            synchronized (monitor) {
                addLogLocked("关闭设备时出错：" + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : "：" + error.getMessage()));
            }
        }
    }

    private void assertOnWorker() {
        Thread current = Thread.currentThread();
        if (!WORKER_NAME.equals(current.getName())) {
            throw new IllegalStateException(
                    "设备 IO 必须在 " + WORKER_NAME + " 上执行，当前线程: " + current.getName());
        }
    }

    private void addLogLocked(String line) {
        logLines.add(line);
        if (logLines.size() > MAX_LOG_LINES) {
            logLines.subList(0, logLines.size() - MAX_LOG_LINES).clear();
        }
    }

    private ConnectionSnapshot buildSnapshotLocked() {
        boolean locked = state != SessionState.DISCONNECTED;
        return new ConnectionSnapshot(
                state,
                locked,
                state == SessionState.DISCONNECTED && selected != null,
                state == SessionState.CONNECTING || state == SessionState.CONNECTED,
                candidates,
                selected,
                profile.name(),
                statusTextLocked(),
                placeholderTextLocked(),
                logLines,
                state == SessionState.CONNECTED ? screenWidth : 0,
                state == SessionState.CONNECTED ? screenHeight : 0,
                state == SessionState.CONNECTED && geometryFallback,
                state == SessionState.CONNECTED ? warning : null,
                state == SessionState.CONNECTED ? capabilities : null,
                state == SessionState.CONNECTED ? orientations : List.of());
    }

    private String statusTextLocked() {
        return switch (state) {
            case CONNECTING -> "连接中";
            case CONNECTED -> "已连接 " + connectedUid + " " + screenWidth + "x" + screenHeight;
            case DISCONNECTING -> "断开中";
            case DISCONNECTED -> switch (idleKind) {
                case NEVER -> "未连接";
                case CLEAN -> idleReason == null ? "已断开" : "已断开：" + idleReason;
                case FAILED -> "已断开：" + (idleReason == null ? "未知错误" : idleReason);
            };
        };
    }

    private String placeholderTextLocked() {
        return switch (state) {
            case CONNECTING -> "连接中";
            case CONNECTED -> "已连接，尚未发送画面";
            case DISCONNECTING -> "正在断开";
            case DISCONNECTED -> "未连接";
        };
    }

    private void publish() {
        ConnectionSnapshot snapshot;
        Consumer<ConnectionSnapshot> current;
        synchronized (monitor) {
            snapshot = buildSnapshotLocked();
            current = listener;
        }
        if (current == null) {
            return;
        }
        listenerExecutor.execute(() -> current.accept(snapshot));
    }
}

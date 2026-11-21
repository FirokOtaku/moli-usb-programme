package space.firok.moli.usb.serial.probe;

import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.MoliOptions;
import space.firok.moli.usb.api.MoliUsb;
import space.firok.moli.usb.api.codec.Hex;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceInfo;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.error.MoliUsbException;
import space.firok.moli.usb.api.session.DeviceSessionRegistry;
import space.firok.moli.usb.api.session.SessionSettings;
import space.firok.moli.usb.api.transport.Transport;
import space.firok.moli.usb.serial.PortDescriptor;
import space.firok.moli.usb.serial.SerialDeviceProvider;
import space.firok.moli.usb.serial.SerialPortConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * MSU2 串口探测命令行工具。
 *
 * <p>面向人工实机验证：先用 {@code list} 确认端口，再 {@code handshake} 看原始字节，
 * 最后 {@code info}／{@code fill} 做基础命令与屏幕验证。所有收到的原始字节都用
 * {@link Hex} 以十六进制打印，便于逐字节对照 {@code spec/msu2-wire-protocol.md}。</p>
 *
 * <p>退出码：{@code 0} 成功，{@code 1} 探测失败，{@code 2} 参数错误。不打印异常堆栈，
 * 只打印可读原因；需要堆栈时用 {@code --debug}。</p>
 *
 * <p>启动方式：{@code java -jar moli-usb-serial-<version>-jar-with-dependencies.jar <子命令>}，
 * 例如 {@code java -jar ... list}、{@code java -jar ... --help}。入口在 {@link #main(String[])}
 * 内部已把标准输出与标准错误固定为 UTF-8，因此<b>不需要</b>额外添加
 * {@code -Dstdout.encoding=UTF-8} 之类的 JVM 参数；中文提示在任何受支持的控制台上都能正常显示。</p>
 */
public final class MoliProbe {

    /** 成功。 */
    public static final int EXIT_OK = 0;
    /** 探测失败：握手失败、设备不支持、端口打不开等。 */
    public static final int EXIT_FAILED = 1;
    /** 参数错误。 */
    public static final int EXIT_USAGE = 2;

    /** 握手总超时。spec 只记录横幅约每 100 ms 一次，命令响应无实测值，取保守值。 */
    static final int HANDSHAKE_TIMEOUT_MS = 3000;

    private final PrintStream out;
    private final PrintStream err;
    private final boolean debug;

    /** 便于测试注入输出流；生产入口用 {@link #main(String[])}。 */
    public MoliProbe(PrintStream out, PrintStream err, boolean debug) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.debug = debug;
    }

    public static void main(String[] argv) {
        // 中文提示必须在任何输出之前固定成 UTF-8。zh_CN 机器上 JDK 25 的默认
        // stdout.encoding 通常是 GBK，而控制台代码页可能是 65001（UTF-8）；两者
        // 不一致时中文会被按 GBK 编码写入，表现为乱码。这里显式换成 UTF-8，
        // 使 `java -jar <jar> <子命令>` 无需额外 JVM 参数即可正常显示中文。
        // 用 FileDescriptor.out/err 而不是原来的 System.out 包装：控制台直写，
        // 不落到文件式的缓冲目标，也不改变重定向（> file、管道）的目标。
        // 已经是 UTF-8（例如显式传了 -Dstdout.encoding=UTF-8）时不重复包装，
        // 避免双重编码。
        System.setOut(Utf8Console.wrap(System.out));
        System.setErr(Utf8Console.wrapErr(System.err));

        boolean debug = false;
        for (String arg : argv) {
            if ("--debug".equals(arg)) {
                debug = true;
            }
        }
        String[] effective = java.util.Arrays.stream(argv)
                .filter(arg -> !"--debug".equals(arg))
                .toArray(String[]::new);
        int code = new MoliProbe(System.out, System.err, debug).run(argv.length == 0
                ? new String[]{"--help"} : effective);
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    /**
     * 执行一条命令并返回退出码。不调用 {@code System.exit}，便于测试。
     */
    public int run(String[] argv) {
        ProbeArgs args;
        try {
            args = ProbeArgs.parse(argv);
        } catch (ProbeUsageException usage) {
            err.println("参数错误: " + usage.getMessage());
            err.println();
            err.println(ProbeArgs.usage());
            return EXIT_USAGE;
        }
        try {
            return switch (args.command()) {
                case HELP -> {
                    out.println(ProbeArgs.usage());
                    yield EXIT_OK;
                }
                case LIST -> runList(args);
                case HANDSHAKE -> runHandshake(args);
                case INFO -> runInfo(args);
                case FILL -> runFill(args);
            };
        } catch (ProbeUsageException usage) {
            err.println("参数错误: " + usage.getMessage());
            return EXIT_USAGE;
        } catch (MoliUsbException moli) {
            err.println("探测失败 [" + moli.getClass().getSimpleName() + "] "
                    + describe(moli));
            if (debug) {
                moli.printStackTrace(err);
            }
            return EXIT_FAILED;
        } catch (IOException io) {
            err.println("串口读写失败: " + messageOf(io));
            if (debug) {
                io.printStackTrace(err);
            }
            return EXIT_FAILED;
        } catch (RuntimeException unexpected) {
            err.println("探测失败: " + messageOf(unexpected));
            if (debug) {
                unexpected.printStackTrace(err);
            }
            return EXIT_FAILED;
        }
    }

    // ------------------------------------------------------------------ list

    private int runList(ProbeArgs args) {
        SerialDeviceProvider provider = new SerialDeviceProvider(args.usbIds());
        List<PortDescriptor> descriptors = provider.descriptors();
        out.println(PortListFormatter.format(descriptors, args.usbIds()));
        if (args.usbIds().isEmpty()) {
            out.println("提示：用 --usb VID:PID 传入实机确认后的候选标识即可启用筛选，"
                    + "例如 MoliProbe list --usb 1234:5678（数值必须实机确认，勿猜）。");
        }
        return EXIT_OK;
    }

    // ------------------------------------------------------------- handshake

    private int runHandshake(ProbeArgs args) throws IOException {
        SerialPortConfig config = ProbeSettings.resolve(args.baudRate(), args.rtscts(),
                args.xonxoff(), args.profile());

        out.println("== 握手探测 ==");
        out.println("端口    " + args.port());
        out.println("参数    " + config.describe());
        out.println("超时    " + HANDSHAKE_TIMEOUT_MS + " ms");
        out.println("协议    横幅 \\0MSN + 两位数字；主机发送 \\0MSNCN，期望同字节回显");
        out.println();

        Transport opened = new SerialDeviceProvider(args.usbIds()).open(args.port(), config);
        try {
            HandshakeProbe.Result result = HandshakeProbe.handshake(opened, HANDSHAKE_TIMEOUT_MS);
            out.println("RX 全部  " + Hex.of(result.rawReceived())
                    + "  (" + result.rawReceived().length + " 字节)");
            if (result.banner() != null) {
                out.println("横幅    " + Hex.of(result.banner()));
            }
            if (result.echo().length > 0) {
                out.println("回显    " + Hex.of(result.echo()));
            }
            out.println();
            if (result.ok()) {
                out.println("握手成功：协议版本 " + result.protocolVersion()
                        + "（横幅两位数字按十进制解析；其是否同时表示固件主次版本"
                        + "仍待实机确认）");
                return EXIT_OK;
            }
            err.println("握手失败: " + result.failureText(HANDSHAKE_TIMEOUT_MS));
            err.println("排查建议:");
            err.println("  1) 确认端口未被其它程序占用，且设备确实在广播横幅；");
            err.println("  2) 试另一个波特率（spec 记录 19200 / 921600 两种，"
                    + "适用机型待实机确认）；");
            err.println("  3) 921600 通常需要 --rtscts；19200 通常不需要流控；");
            err.println("  4) 把上面的 RX 十六进制与 spec/msu2-wire-protocol.md 的握手章节对照。");
            return EXIT_FAILED;
        } finally {
            closeQuietly(opened);
        }
    }

    // ------------------------------------------------------------------ info

    private int runInfo(ProbeArgs args) {
        SerialPortConfig config = ProbeSettings.resolve(args.baudRate(), args.rtscts(),
                args.xonxoff(), args.profile());
        DeviceProfile profile = ProbeSettings.profile(args.profile());

        out.println("== 设备信息 ==");
        out.println("端口    " + args.port());
        out.println("参数    " + config.describe());
        out.println("profile " + profile.name());
        out.println();

        SerialDeviceProvider provider = new SerialDeviceProvider(args.usbIds())
                .withDefaultConfig(config)
                .withTrace(line -> out.println("  [raw] " + line));
        MoliUsb api = MoliUsb.over(provider);
        DeviceCandidate candidate = provider.toCandidate(PortDescriptor.ofName(args.port()));

        MoliOptions options = new MoliOptions(profile, sessionSettings(),
                DeviceSessionRegistry.global(), true);
        try (MoliDevice device = api.open(candidate, options)) {
            printInfo(device);
            return EXIT_OK;
        } finally {
            api.closeAll();
        }
    }

    private void printInfo(MoliDevice device) {
        DeviceInfo info = device.info();
        DeviceCapabilities capabilities = device.capabilities();

        out.println();
        out.println("协议版本    " + info.protocolVersion()
                + "（来自横幅；与固件版本的对应关系待实机确认）");
        out.println("UID         " + info.uid());
        out.println("屏幕        " + (info.screenWidth() > 0 && info.screenHeight() > 0
                ? info.screenWidth() + "x" + info.screenHeight()
                : "设备未报告（待实机确认）"));
        out.println("profile     " + device.profileName());

        DisplayGeometry geometry = device.display().orElse(null);
        out.println("显示几何    " + (geometry == null
                ? "不可用（profile 与自描述都没有给出宽高，fill 需 --profile）"
                : geometry.width() + "x" + geometry.height()
                        + " origin=" + geometry.origin()
                        + " format=" + geometry.colorFormat()
                        + " orientations=" + geometry.orientations()));

        device.storage().ifPresentOrElse(
                storage -> out.println("存储        " + storage.type()
                        + " capacity=" + storage.capacityBytes() + " B"
                        + " pageSize=" + storage.pageSize() + " B"
                        + " erase=" + storage.eraseGranularityBytes() + " B"
                        + " pages=" + storage.totalPages()),
                () -> out.println("存储        不可用（设备未声明或未报告）"));

        out.println("能力        display=" + capabilities.display()
                + " externalFlash=" + capabilities.externalFlash()
                + " rgbLeds=" + capabilities.rgbLeds()
                + " backlight=" + capabilities.backlight()
                + " nand=" + capabilities.nand()
                + " buttonInput=" + capabilities.buttonInput());

        Map<String, DeviceInfo.SfrEntry> entries = info.reportedEntries();
        if (entries.isEmpty()) {
            out.println("自描述表    空（设备未报告，或读取失败；spec/verification.md 仍列为待确认项）");
            return;
        }
        out.println("自描述表    " + entries.size() + " 项");
        entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    DeviceInfo.SfrEntry value = entry.getValue();
                    out.println("  " + value.name()
                            + " unit=" + (value.unit().isBlank() ? "—" : value.unit())
                            + " family=" + value.family()
                            + " data=" + value.data());
                });
    }

    // ------------------------------------------------------------------ fill

    private int runFill(ProbeArgs args) {
        SerialPortConfig config = ProbeSettings.resolve(args.baudRate(), args.rtscts(),
                args.xonxoff(), args.profile());
        DeviceProfile profile = ProbeSettings.profile(args.profile());
        Rgb565 color = Rgb565.fromRgb888(
                (args.color() >> 16) & 0xFF, (args.color() >> 8) & 0xFF, args.color() & 0xFF);

        out.println("== 填充屏幕 ==");
        out.println("端口    " + args.port());
        out.println("参数    " + config.describe());
        out.println("profile " + profile.name());
        out.printf(Locale.ROOT, "颜色    #%06X -> RGB565 0x%04X%n",
                args.color(), color.value());
        out.println();

        SerialDeviceProvider provider = new SerialDeviceProvider(args.usbIds())
                .withDefaultConfig(config)
                .withTrace(line -> out.println("  [raw] " + line));
        MoliUsb api = MoliUsb.over(provider);
        DeviceCandidate candidate = provider.toCandidate(PortDescriptor.ofName(args.port()));
        MoliOptions options = new MoliOptions(profile, sessionSettings(),
                DeviceSessionRegistry.global(), true);

        try (MoliDevice device = api.open(candidate, options)) {
            DisplayGeometry geometry = device.display().orElse(null);
            if (geometry == null) {
                err.println("填充失败: 设备没有可用的显示几何。"
                        + "请用 --profile MINI|LITE|PRO 指定已确认的机型，"
                        + "或等待设备报告 Lcd_X/Lcd_Y。");
                err.println("说明：未确认的分辨率不做推测，避免按错误尺寸写入像素。");
                return EXIT_FAILED;
            }
            Rect full = new Rect(0, 0, geometry.width(), geometry.height());
            out.println("填充区域  (" + full.x() + "," + full.y() + ") "
                    + full.width() + "x" + full.height());
            device.fillRect(full, color);
            out.println("已发送填充命令。若屏幕未变化，请检查：背光是否开启、"
                    + "是否需要先切换方向、profile 几何是否与实际机型一致。");
            return EXIT_OK;
        } finally {
            api.closeAll();
        }
    }

    private static SessionSettings sessionSettings() {
        // 探测工具用手动观察代替重试：握手失败时要看到明确的阶段原因，而不是被自动重试掩盖。
        return new SessionSettings(Duration.ofMillis(HANDSHAKE_TIMEOUT_MS),
                1, Duration.ofSeconds(1), 25, 1);
    }

    private static String describe(MoliUsbException error) {
        String deviceId = error.deviceId().orElse("(未确定)");
        return error.getMessage() + " [operation=" + error.operation()
                + ", device=" + deviceId + "]";
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static void closeQuietly(Transport transport) {
        try {
            transport.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已打印的探测结论。
        }
    }
}
package space.firok.moli.usb.serial.probe;

import space.firok.moli.usb.serial.UsbId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code MoliProbe} 的命令行参数。
 *
 * <p>从 {@code main} 里抽出来单独解析，使参数规则可以在没有真实串口的环境下测试
 * （见 {@code ProbeArgsTest}）。解析只做语法与取值校验，不触碰硬件。</p>
 *
 * <p>支持的形状：</p>
 *
 * <pre>{@code
 * MoliProbe list
 * MoliProbe handshake <PORT> <baud> [--rtscts|--no-rtscts] [--xonxoff]
 * MoliProbe info <PORT> <baud> [--profile MINI|LITE|PRO|GENERIC]
 * MoliProbe fill <PORT> <baud> [--color RRGGBB] [--profile ...]
 * MoliProbe --help
 * }</pre>
 *
 * <p>通用开关：{@code --usb VID:PID}（可重复，用于配置候选 USB 标识；缺省表示不按
 * VID/PID 过滤）、{@code --rtscts}、{@code --no-rtscts}、{@code --xonxoff}。</p>
 *
 * @param command   子命令
 * @param port      端口名；{@code list}/{@code help} 时为空字符串
 * @param baudRate  波特率；{@code list}/{@code help} 时为 {@code null}
 * @param rtscts    是否启用 RTS/CTS；{@code null} 表示未在命令行显式指定，由 profile 决定
 * @param xonxoff   是否启用 XON/XOFF
 * @param usbIds    VID/PID 候选；空表示不过滤（因为 spec 尚未确认取值）
 * @param profile   显式指定的 profile 名；{@code null} 表示使用通用 profile
 * @param color     填充颜色（RGB888，0xRRGGBB）；仅 {@code fill} 使用
 */
public record ProbeArgs(
        Command command,
        String port,
        Integer baudRate,
        Boolean rtscts,
        boolean xonxoff,
        List<UsbId> usbIds,
        String profile,
        int color) {

    /** 子命令。 */
    public enum Command {
        HELP("help"),
        LIST("list"),
        HANDSHAKE("handshake"),
        INFO("info"),
        FILL("fill");

        private final String token;

        Command(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** 该子命令是否需要 {@code <PORT> <baud>} 参数。 */
        public boolean requiresPort() {
            return this == HANDSHAKE || this == INFO || this == FILL;
        }

        static Command byToken(String token) {
            for (Command command : values()) {
                if (command.token.equals(token)) {
                    return command;
                }
            }
            return null;
        }
    }

    /** 默认填充颜色：品红，肉眼易与黑底区分。 */
    public static final int DEFAULT_FILL_COLOR = 0xFF00FF;

    public ProbeArgs {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(port, "port");
        usbIds = List.copyOf(usbIds);
        if (color < 0 || color > 0xFFFFFF) {
            throw new ProbeUsageException("颜色必须落在 0x000000..0xFFFFFF（实际 "
                    + Integer.toHexString(color) + "）");
        }
    }

    /** 是否需要一个端口名与波特率。 */
    public boolean requiresPort() {
        return command == Command.HANDSHAKE || command == Command.INFO || command == Command.FILL;
    }

    /** 用法文本（ASCII 与中文混排，直接从控制台复制即可运行）。 */
    public static String usage() {
        return """
                MoliProbe —— MSU2 串口探测工具

                用法:
                  MoliProbe list
                      列出系统串口与 USB 元数据，标注未识别端口
                  MoliProbe handshake <PORT> <baud> [开关]
                      做一次握手，打印原始收发字节与解析结果
                  MoliProbe info <PORT> <baud> [开关]
                      握手后读取设备信息与能力并打印
                  MoliProbe fill <PORT> <baud> [开关]
                      握手后填充一片已知颜色，用于肉眼验证屏幕
                  MoliProbe --help
                      显示本帮助

                开关:
                  --rtscts         启用 RTS/CTS 硬件流控（覆盖 profile 默认值）
                  --no-rtscts      禁用 RTS/CTS 硬件流控（覆盖 profile 默认值）
                  --xonxoff        启用 XON/XOFF 软件流控
                  --profile NAME   使用 MINI / LITE / PRO / GENERIC 的默认显示几何
                  --color RRGGBB   填充颜色，默认 FF00FF（品红）；仅 fill 使用
                  --usb VID:PID    候选 USB 标识，可重复；缺省表示不按 VID/PID 过滤

                注意:
                  - 本仓库 spec 尚未记录 MSU2 的 USB VID/PID，因此默认不加过滤，
                    list 会把所有串口都标注为「未识别」。实机确认后用 --usb 传入。
                  - 8N1 帧格式与 19200/921600 的适用机型同为待实机确认项。

                退出码:
                  0 成功；1 探测失败（握手失败、设备不支持等）；2 参数错误
                """;
    }

    /**
     * 解析命令行。
     *
     * @throws ProbeUsageException 参数形状或取值不合法
     */
    public static ProbeArgs parse(String[] argv) {
        Objects.requireNonNull(argv, "argv");
        List<String> tokens = new ArrayList<>();
        for (String arg : argv) {
            if (arg != null) {
                tokens.add(arg);
            }
        }

        Command command = null;
        String port = "";
        Integer baudRate = null;
        Boolean rtscts = null;
        boolean xonxoff = false;
        List<UsbId> usbIds = new ArrayList<>();
        String profile = null;
        int color = DEFAULT_FILL_COLOR;

        List<String> positional = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("--help") || token.equals("-h") || token.equals("-?")) {
                return new ProbeArgs(Command.HELP, "", null, null, false, List.of(), null, color);
            }
            switch (token) {
                case "--rtscts" -> {
                    rtscts = Boolean.TRUE;
                    continue;
                }
                case "--no-rtscts" -> {
                    rtscts = Boolean.FALSE;
                    continue;
                }
                case "--xonxoff" -> {
                    xonxoff = true;
                    continue;
                }
                case "--profile" -> {
                    profile = requireValue(tokens, ++i, "--profile");
                    String normalized = profile.toUpperCase(Locale.ROOT);
                    if (!normalized.equals("MINI") && !normalized.equals("LITE")
                            && !normalized.equals("PRO") && !normalized.equals("GENERIC")) {
                        throw new ProbeUsageException(
                                "--profile 只接受 MINI/LITE/PRO/GENERIC（实际 " + profile + "）");
                    }
                    profile = normalized;
                    continue;
                }
                case "--usb" -> {
                    usbIds.add(UsbId.parse(requireValue(tokens, ++i, "--usb")));
                    continue;
                }
                case "--color" -> {
                    color = parseColor(requireValue(tokens, ++i, "--color"));
                    continue;
                }
                default -> {
                    if (token.startsWith("-") && token.length() > 1 && !isNumeric(token)) {
                        throw new ProbeUsageException("未知选项: " + token);
                    }
                    positional.add(token);
                }
            }
        }

        if (!positional.isEmpty()) {
            command = Command.byToken(positional.getFirst());
            if (command == null) {
                throw new ProbeUsageException("未知子命令: " + positional.getFirst()
                        + "（可用: list / handshake / info / fill）");
            }
        }
        if (command == null) {
            command = Command.HELP;
        }

        if (command.requiresPort()) {
            if (positional.size() < 3) {
                throw new ProbeUsageException(command.token()
                        + " 需要 <PORT> 与 <baud> 两个参数");
            }
            if (positional.size() > 3) {
                throw new ProbeUsageException(command.token() + " 只接受 <PORT> <baud>"
                        + "（多余参数: " + String.join(" ", positional.subList(3, positional.size()))
                        + "）");
            }
            port = positional.get(1);
            if (port.startsWith("-") || port.isBlank()) {
                throw new ProbeUsageException("端口名不合法: " + port);
            }
            baudRate = parseInt(positional.get(2), "波特率");
            if (baudRate <= 0) {
                throw new ProbeUsageException("波特率必须为正（实际 " + baudRate + "）");
            }
        } else if (positional.size() > 1) {
            throw new ProbeUsageException(command.token() + " 不接受额外参数: "
                    + String.join(" ", positional.subList(1, positional.size())));
        }

        return new ProbeArgs(command, port, baudRate, rtscts, xonxoff,
                List.copyOf(usbIds), profile, color);
    }

    private static String requireValue(List<String> tokens, int index, String option) {
        if (index >= tokens.size()) {
            throw new ProbeUsageException(option + " 缺少取值");
        }
        return tokens.get(index);
    }

    private static int parseInt(String text, String what) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException malformed) {
            throw new ProbeUsageException(what + " 不是整数: " + text);
        }
    }

    /** 解析 {@code RRGGBB} 或 {@code #RRGGBB}。 */
    static int parseColor(String text) {
        String value = text.startsWith("#") ? text.substring(1) : text;
        if (!value.matches("[0-9A-Fa-f]{6}")) {
            throw new ProbeUsageException("颜色必须形如 RRGGBB（实际 " + text + "）");
        }
        return Integer.parseInt(value, 16);
    }

    private static boolean isNumeric(String token) {
        return token.length() > 1 && token.chars().allMatch(Character::isDigit);
    }
}
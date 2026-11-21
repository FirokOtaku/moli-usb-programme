package space.firok.moli.usb.serial.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.firok.moli.usb.serial.UsbId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命令行参数解析测试：只验证语法，不触碰串口。
 */
class ProbeArgsTest {

    @Test
    @DisplayName("无参数视为 --help")
    void emptyArgsBecomeHelp() {
        assertEquals(ProbeArgs.Command.HELP, ProbeArgs.parse(new String[0]).command());
    }

    @Test
    @DisplayName("--help 在任意位置都优先")
    void helpAnywhereWins() {
        assertEquals(ProbeArgs.Command.HELP,
                ProbeArgs.parse(new String[]{"handshake", "COM3", "19200", "--help"}).command());
        assertEquals(ProbeArgs.Command.HELP, ProbeArgs.parse(new String[]{"-h"}).command());
    }

    @Test
    @DisplayName("list 不接受额外参数")
    void listRejectsExtras() {
        ProbeArgs args = ProbeArgs.parse(new String[]{"list"});
        assertEquals(ProbeArgs.Command.LIST, args.command());
        assertFalse(args.requiresPort());
        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"list", "COM3"}));
    }

    @Test
    @DisplayName("handshake 需要 PORT 与 baud")
    void handshakeRequiresPortAndBaud() {
        ProbeArgs args = ProbeArgs.parse(new String[]{"handshake", "COM3", "921600"});
        assertEquals(ProbeArgs.Command.HANDSHAKE, args.command());
        assertEquals("COM3", args.port());
        assertEquals(921600, args.baudRate());
        assertNull(args.rtscts());

        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"handshake", "COM3"}));
    }

    @Test
    @DisplayName("拒绝非数字波特率与非正波特率")
    void rejectsBadBaud() {
        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"info", "COM3", "fast"}));
        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"info", "COM3", "0"}));
    }

    @Test
    @DisplayName("--rtscts 与 --no-rtscts 分别记录为 true/false")
    void flowControlOverrides() {
        assertEquals(Boolean.TRUE,
                ProbeArgs.parse(new String[]{"info", "COM3", "921600", "--rtscts"}).rtscts());
        assertEquals(Boolean.FALSE,
                ProbeArgs.parse(new String[]{"info", "COM3", "921600", "--no-rtscts"}).rtscts());
        assertTrue(ProbeArgs.parse(new String[]{"info", "COM3", "19200", "--xonxoff"}).xonxoff());
    }

    @Test
    @DisplayName("--usb 可重复并解析为 UsbId 列表")
    void usbIdsAreRepeatable() {
        ProbeArgs args = ProbeArgs.parse(new String[]{
                "list", "--usb", "0x1234:0x5678", "--usb", "9ABC:0001"});

        assertEquals(2, args.usbIds().size());
        assertEquals(new UsbId(0x1234, 0x5678), args.usbIds().get(0));
        assertEquals(new UsbId(0x9ABC, 0x0001), args.usbIds().get(1));
    }

    @Test
    @DisplayName("--profile 只接受已知机型名并归一化为大写")
    void profileValidation() {
        assertEquals("PRO",
                ProbeArgs.parse(new String[]{"info", "COM3", "921600", "--profile", "pro"})
                        .profile());
        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"info", "COM3", "921600",
                        "--profile", "MINI2"}));
    }

    @Test
    @DisplayName("--color 支持 RRGGBB 与 #RRGGBB")
    void colorParsing() {
        assertEquals(0xFF00FF,
                ProbeArgs.parse(new String[]{"fill", "COM3", "19200"}).color());
        assertEquals(0x00FF00,
                ProbeArgs.parse(new String[]{"fill", "COM3", "19200",
                        "--color", "00FF00"}).color());
        assertEquals(0x123456,
                ProbeArgs.parse(new String[]{"fill", "COM3", "19200",
                        "--color", "#123456"}).color());
        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"fill", "COM3", "19200",
                        "--color", "12345"}));
    }

    @Test
    @DisplayName("未知选项与未知子命令都报参数错误")
    void unknownTokensRejected() {
        assertThrows(ProbeUsageException.class, () -> ProbeArgs.parse(new String[]{"--bogus"}));
        assertThrows(ProbeUsageException.class, () -> ProbeArgs.parse(new String[]{"scan"}));
        assertThrows(ProbeUsageException.class,
                () -> ProbeArgs.parse(new String[]{"--profile"}));
        assertThrows(ProbeUsageException.class, () -> ProbeArgs.parse(new String[]{"--usb"}));
    }

    @Test
    @DisplayName("用法文本包含全部子命令与退出码说明")
    void usageCoversCommands() {
        String usage = ProbeArgs.usage();
        assertTrue(usage.contains("list"));
        assertTrue(usage.contains("handshake"));
        assertTrue(usage.contains("info"));
        assertTrue(usage.contains("fill"));
        assertTrue(usage.contains("--rtscts"));
        assertTrue(usage.contains("VID/PID"));
        assertTrue(usage.contains("退出码"));
    }
}
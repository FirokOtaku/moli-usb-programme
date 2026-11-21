package space.firok.moli.usb.serial.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code MoliProbe} 退出码与输出测试。
 *
 * <p>只跑不需要真实设备的分支（{@code --help}、参数错误、{@code list}）。{@code list}
 * 在无串口的机器上必须正常返回空列表提示，而不是崩溃。</p>
 */
class MoliProbeTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... argv) {
        return new MoliProbe(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), false).run(argv);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("--help 退出码为 0 并打印用法")
    void helpExitsZero() {
        assertEquals(MoliProbe.EXIT_OK, run("--help"));
        assertTrue(stdout().contains("MoliProbe"));
        assertTrue(stdout().contains("handshake"));
        assertTrue(stderr().isEmpty());
    }

    @Test
    @DisplayName("参数错误退出码为 2，错误写 stderr 并附用法")
    void usageErrorExitsTwo() {
        assertEquals(MoliProbe.EXIT_USAGE, run("handshake", "COM3"));
        assertTrue(stderr().contains("参数错误"));
        assertTrue(stderr().contains("list"));
        assertFalse(stderr().contains("Exception"));
    }

    @Test
    @DisplayName("未知子命令退出码为 2")
    void unknownCommandExitsTwo() {
        assertEquals(MoliProbe.EXIT_USAGE, run("scan"));
        assertTrue(stderr().contains("未知子命令"));
    }

    @Test
    @DisplayName("list 在无设备环境下不崩溃，输出提示与待确认声明")
    void listWorksWithoutHardware() {
        int code = run("list");

        assertEquals(MoliProbe.EXIT_OK, code);
        assertTrue(stdout().contains("串口枚举"));
        assertTrue(stdout().contains(PortListFormatter.USB_ID_UNCONFIRMED_NOTICE));
        assertTrue(stderr().isEmpty());
    }

    @Test
    @DisplayName("list --usb 会打印已配置的候选标识")
    void listWithUsbFilterPrintsCandidates() {
        assertEquals(MoliProbe.EXIT_OK, run("list", "--usb", "1234:5678"));
        assertTrue(stdout().contains("1234:5678"));
    }
}
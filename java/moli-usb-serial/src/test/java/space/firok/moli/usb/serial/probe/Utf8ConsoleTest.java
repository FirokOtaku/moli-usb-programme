package space.firok.moli.usb.serial.probe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Utf8Console} 测试。
 *
 * <p>全部使用内存中的 {@link ByteArrayOutputStream}，且只通过可注入输出目标的重载
 * 进行包装，不触碰真实控制台与 {@code System.out}／{@code System.err}，因此可在
 * 无终端环境（CI、IDE 测试运行器）下稳定运行。</p>
 */
class Utf8ConsoleTest {

    private static final String CHINESE = "== 串口枚举 ==";

    /** 模拟 zh_CN 机器上 JDK 默认的 GBK 标准输出。 */
    private static PrintStream gbkStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, Charset.forName("GBK"));
    }

    @Test
    @DisplayName("非 UTF-8 输出流包装后写中文得到正确的 UTF-8 字节")
    void wrapsNonUtf8Stream() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = gbkStream(buffer);
        assertFalse(Utf8Console.isUtf8(original), "前置条件：原流不能是 UTF-8");

        PrintStream wrapped = Utf8Console.wrap(original, () -> buffer);
        assertTrue(Utf8Console.isUtf8(wrapped), "包装后应为 UTF-8");

        wrapped.print(CHINESE);
        wrapped.flush();

        byte[] actual = buffer.toByteArray();
        assertEquals(CHINESE, new String(actual, StandardCharsets.UTF_8),
                "按 UTF-8 解码应还原中文");
        assertArrayEquals(CHINESE.getBytes(StandardCharsets.UTF_8),
                Arrays.copyOf(actual, actual.length),
                "字节应与「== 串口枚举 ==」的 UTF-8 编码一致");
        // GBK 写出的字节流无法按 UTF-8 严格解码还原；包装后必须不再出现替换字符。
        assertFalse(new String(actual, StandardCharsets.UTF_8).contains("\uFFFD"),
                "不应出现 EF BF BD 替换字符导致的乱码");
    }

    @Test
    @DisplayName("已是 UTF-8 的流原样返回，不重复包装也不打开新的输出目标")
    void keepsUtf8StreamUntouched() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        AtomicInteger sinkCalls = new AtomicInteger();
        Supplier<OutputStream> sink = () -> {
            sinkCalls.incrementAndGet();
            return buffer;
        };

        PrintStream wrapped = Utf8Console.wrap(original, sink);

        assertSame(original, wrapped, "已是 UTF-8 时不应重新包装");
        assertEquals(0, sinkCalls.get(), "不应打开新的输出目标");

        wrapped.print(CHINESE);
        wrapped.flush();
        assertEquals(CHINESE, buffer.toString(StandardCharsets.UTF_8),
                "原流编码保持不变，不应发生双重编码");
    }

    @Test
    @DisplayName("标准错误的包装沿用同一判断规则，且 UTF-8 流不会被重包")
    void errStreamFollowsSameRule() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream alreadyUtf8 = new PrintStream(buffer, true, StandardCharsets.UTF_8);

        // 已是 UTF-8：wrapErr 必须原样返回，绝不能打开真实标准错误。
        assertSame(alreadyUtf8, Utf8Console.wrapErr(alreadyUtf8),
                "已是 UTF-8 的标准错误不应重新包装");
        assertTrue(buffer.size() == 0, "原样返回时不应产生任何输出");

        // 非 UTF-8 的标准错误：用可注入目标验证同一套包装逻辑。
        ByteArrayOutputStream gbkBuffer = new ByteArrayOutputStream();
        PrintStream nonUtf8 = gbkStream(gbkBuffer);
        PrintStream wrapped = Utf8Console.wrap(nonUtf8, () -> gbkBuffer);
        wrapped.print("串口读写失败");
        wrapped.flush();
        assertEquals("串口读写失败", gbkBuffer.toString(StandardCharsets.UTF_8));
    }
}
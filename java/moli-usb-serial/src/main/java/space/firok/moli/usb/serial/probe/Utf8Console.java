package space.firok.moli.usb.serial.probe;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 把标准输出／标准错误固定为 UTF-8 的辅助方法。
 *
 * <p>MSU2 在 zh_CN 机器上运行时，JDK 默认的 {@code stdout.encoding} 往往是 GBK
 * （可用 {@code -XshowSettings:properties} 查看），而现代 Windows 控制台代码页是
 * 65001（UTF-8）。此时中文提示会按 GBK 编码成字节写进 UTF-8 控制台，表现为乱码
 * （输出字节里出现 {@code EF BF BD} 替换字符与 {@code C3 B6} 这类组合）。源码文件
 * 本身是合法 UTF-8，所以修复点只在输出端。</p>
 *
 * <p>本类只调整编码，不改任何输出内容。只在当前流不是 UTF-8 时才重新包装，已经是
 * UTF-8（例如命令行显式传入 {@code -Dstdout.encoding=UTF-8}）时原样返回，避免同一
 * 份字符被编码两次。</p>
 */
final class Utf8Console {

    private Utf8Console() {
    }

    /** 判断流当前是否已按 UTF-8 编码输出。 */
    static boolean isUtf8(PrintStream stream) {
        Charset charset = Objects.requireNonNull(stream, "stream").charset();
        return StandardCharsets.UTF_8.equals(charset);
    }

    /**
     * 若标准输出不是 UTF-8，则包装成显式 UTF-8 的 {@link PrintStream}；否则原样返回。
     *
     * <p>包装目标使用 {@link FileDescriptor#out}：控制台直写，不落到文件缓冲区，
     * 也不受 {@code System.out} 原有编码设置影响。</p>
     */
    static PrintStream wrap(PrintStream stream) {
        return wrap(stream, () -> new FileOutputStream(FileDescriptor.out));
    }

    /** 同 {@link #wrap(PrintStream)}，但针对标准错误（{@link FileDescriptor#err}）。 */
    static PrintStream wrapErr(PrintStream stream) {
        return wrap(stream, () -> new FileOutputStream(FileDescriptor.err));
    }

    /**
     * 可注入输出目标的包装方法，便于单元测试（不触碰真实控制台）。
     *
     * <p>{@code sink} 只在确实需要重新包装时才会被调用。</p>
     */
    static PrintStream wrap(PrintStream stream, Supplier<OutputStream> sink) {
        if (isUtf8(stream)) {
            return stream;
        }
        Objects.requireNonNull(sink, "sink");
        // PrintStream(OutputStream, boolean, Charset) 是 JDK 10+ 的类型安全写法，
        // 不使用已废弃的 PrintStream(OutputStream, boolean, String) 构造器。
        return new PrintStream(sink.get(), true, StandardCharsets.UTF_8);
    }
}
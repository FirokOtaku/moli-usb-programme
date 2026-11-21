package space.firok.moli.usb.serial.probe;

/**
 * 命令行参数错误：由 {@link ProbeArgs#parse(String[])} 抛出，{@code MoliProbe} 捕获后
 * 打印用法并以退出码 2 结束。
 *
 * <p>单独一个类型而不是复用核心库的 {@code InvalidArgumentError}：这是工具层的输入
 * 问题，不是协议会话的错误，不应被当成 MSU2 设备错误统计。</p>
 */
public class ProbeUsageException extends RuntimeException {

    public ProbeUsageException(String message) {
        super(message);
    }
}
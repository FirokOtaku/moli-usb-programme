package space.firok.moli.usb.api.transport;

import java.io.IOException;

/**
 * 传输层：字节读写抽象。
 *
 * <p>会话层与编解码层只依赖本接口。串口、测试假实现以及将来的其它传输方式都通过
 * 该层注入；库本身不绑定具体 USB/串口调用方式，也不向上层暴露串口对象。</p>
 *
 * <p>实现务必是线程安全的或只被单一线程使用：会话层在单个会话内串行调用。</p>
 */
public interface Transport extends AutoCloseable {

    /**
     * 读取当前可用的字节，最多 {@code maxBytes} 个，至少等待到有数据、超时或断开。
     *
     * @param maxBytes  本次最多读取的字节数
     * @param timeoutMs 本次读的超时（毫秒）；&lt;= 0 表示不阻塞立即返回
     * @return 已读字节；超时返回空数组；断开且无数据可读时返回 {@code null}
     * @throws IOException 底层读写故障
     */
    byte[] read(int maxBytes, int timeoutMs) throws IOException;

    /**
     * 写出一段字节。帧边界由调用方保证（可一次写入多个完整 6 字节帧），
     * 本方法不得自行拆分或改写帧。
     *
     * @param data 待写字节
     * @throws IOException 底层写故障（含断开）
     */
    void write(byte[] data) throws IOException;

    /** 丢弃接收缓冲中的现有字节（清空输入）。 */
    void clearInput() throws IOException;

    /**
     * 传输层当前是否仍然连接。会话层据此检测断线，避免自动重放未确认写入。
     */
    boolean isConnected();

    /**
     * 关闭传输。实现必须幂等。
     */
    @Override
    void close() throws IOException;

    /**
     * 传输层地址（如串口名或 USB 设备路径），仅用于本次枚举的临时标识与错误信息。
     */
    String address();
}

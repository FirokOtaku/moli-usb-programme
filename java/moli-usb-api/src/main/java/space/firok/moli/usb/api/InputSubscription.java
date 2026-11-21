package space.firok.moli.usb.api;

import space.firok.moli.usb.api.error.MoliUsbException;

import java.util.Optional;

/**
 * 领域 API：输入事件订阅句柄。
 *
 * <p>关闭后内部轮询线程停止，不再回调。若轮询因断线或协议错误中止，原因通过
 * {@link #failure()} 暴露，调用方不必解析异常字符串。</p>
 */
public interface InputSubscription extends AutoCloseable {

    /** 订阅是否仍在工作。 */
    boolean isActive();

    /** 订阅异常终止的原因；正常关闭或仍在工作时为空。 */
    Optional<MoliUsbException> failure();

    /** 停止订阅，幂等。 */
    @Override
    void close();
}
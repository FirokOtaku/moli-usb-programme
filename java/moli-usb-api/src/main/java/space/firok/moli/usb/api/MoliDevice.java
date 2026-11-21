package space.firok.moli.usb.api;

import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceInfo;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.domain.StorageGeometry;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 领域 API：一台已打开设备的稳定入口。
 *
 * <p>本接口不暴露传输对象、串口句柄、厂商 Python 函数名或 6 字节帧。所有参数与返回值
 * 都是领域模型；协议细节（帧布局、页地址顺序、ADC 阈值）留在下层。</p>
 *
 * <p>一个实例独占一台设备。同一传输地址的第二个实例会在打开时报
 * {@code DeviceBusyError}（spec/architecture.md「会话约束」）。</p>
 *
 * <p>实例不是线程安全的入口，但内部操作会串行化：设备没有请求 ID，同一会话内的命令
 * 不允许并发，否则响应会错配。</p>
 */
public interface MoliDevice extends AutoCloseable {

    /** 设备标识。 */
    DeviceId id();

    /** 设备信息：协议版本、UID、屏幕与存储几何、自描述原始条目。 */
    DeviceInfo info();

    /** 设备能力集。业务代码按能力判断，不以型号枚举代替。 */
    DeviceCapabilities capabilities();

    /** 已确认的显示几何；设备未报告或 profile 未提供时为空。 */
    Optional<DisplayGeometry> display();

    /** 外部存储几何；设备不声明外部存储时为空。 */
    Optional<StorageGeometry> storage();

    /** 本会话采用的 profile 名称（{@code MINI}/{@code LITE}/{@code PRO}/{@code GENERIC}）。 */
    String profileName();

    /**
     * 设置显示方向。
     *
     * @throws space.firok.moli.usb.api.error.UnsupportedCapabilityError 设备未声明显示能力或
     *         不支持该方向
     */
    void setOrientation(DisplayGeometry.Orientation orientation);

    /**
     * 设置背光。
     *
     * @param pwm 0..1000，0 为关闭
     * @throws space.firok.moli.usb.api.error.UnsupportedCapabilityError 设备不声明背光能力
     * @throws space.firok.moli.usb.api.error.InvalidArgumentError       pwm 越界
     */
    void setBacklight(int pwm);

    /**
     * 用单色填充矩形区域。
     *
     * @throws space.firok.moli.usb.api.error.InvalidArgumentError 矩形越出屏幕或参数非法
     */
    void fillRect(Rect rect, Rgb565 color);

    /**
     * 显示外部存储中的彩色素材。
     *
     * <p>页地址的字节布局随 profile 变化（PRO 与旧源码/LITE 不同），由本方法按已确认
     * 的 profile 选择，调用方无需关心。</p>
     *
     * @param page 素材起始页
     */
    void showStoredColorImage(int page);

    /** 显示外部存储中的单色素材。 */
    void showStoredMonoImage(int page);

    /**
     * 以未压缩 RGB565 流式写入一个矩形区域。
     *
     * @param rect   目标区域，必须在屏幕范围内
     * @param pixels 逐行排列的 RGB565 像素，长度必须等于 {@code rect.width * rect.height}
     */
    void streamImage(Rect rect, int[] pixels);

    /**
     * 以两色 RLE 流式写入一个矩形区域。
     *
     * <p>编码前会按最坏情况上界检查帧数；超过 {@code maxFrames} 时失败并提示改用未压缩
     * 路径，而不是无限膨胀。</p>
     */
    void streamImageRle(Rect rect, int[] pixels, int maxFrames);

    /** 读取外部存储的连续字节。地址越界抛 {@code OutOfBoundsError}。 */
    byte[] readStorage(long address, int length);

    /** 擦除连续页。页区间越界抛 {@code OutOfBoundsError}。 */
    void erasePages(long startPage, long pageCount);

    /**
     * 把数据写进外部存储的连续页。
     *
     * <p>写入前按存储几何做容量与页范围检查：超出容量抛
     * {@code StorageCapacityExceededError}，页区间越界抛 {@code OutOfBoundsError}。</p>
     */
    void writePages(long startPage, byte[] data);

    /**
     * 订阅输入事件。
     *
     * <p>MINI 的按键由 ADC 采样适配为 {@code InputEvent}；ADC 通道与阈值不暴露给上层。
     * 监听回调在库内部的守护线程上执行，回调里不要做阻塞操作。</p>
     */
    InputSubscription subscribeInput(Consumer<space.firok.moli.usb.api.domain.InputEvent> listener);

    /** 关闭会话并释放设备占用。幂等。 */
    @Override
    void close();
}
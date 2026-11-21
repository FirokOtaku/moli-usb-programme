package space.firok.moli.usb.gui;

/**
 * 系统状态采样。实现可以碰硬件；测试注入假读数，不需要真实计数器。
 *
 * <p>应在 JavaFX 线程之外调用。一次采样失败不得让调用方卡死。</p>
 */
interface SystemSampler {

    SystemSample sample();
}

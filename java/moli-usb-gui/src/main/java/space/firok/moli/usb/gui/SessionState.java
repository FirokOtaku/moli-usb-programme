package space.firok.moli.usb.gui;

/**
 * 一个窗口、一次会话的连接状态。
 *
 * <p>离开 {@link #DISCONNECTED} 后设备与 profile 选择锁定，直到断开完成回到
 * {@link #DISCONNECTED}。</p>
 */
public enum SessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

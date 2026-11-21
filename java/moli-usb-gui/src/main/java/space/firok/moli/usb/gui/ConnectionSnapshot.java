package space.firok.moli.usb.gui;

import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DisplayGeometry;

import java.util.List;

/**
 * 连接状态的不可变快照。控制器在锁内复制后交给监听器，监听器不得再改它。
 *
 * @param combosLocked      非未连接时为 true：设备与 profile 下拉框必须禁用
 * @param connectEnabled    仅未连接且已选中候选时为 true
 * @param disconnectEnabled 连接中或已连接时为 true
 * @param screenWidth       已连接时的显示宽度；未连接或设备未报告且无 profile 几何时为 0
 * @param screenHeight      已连接时的显示高度
 * @param geometryFallback  设备报告的宽或高为 0，已改用 profile 显示几何
 * @param warning           需要展示的警告；没有时为 null
 * @param capabilities      已连接时的设备能力；断开后清空，为 null。界面按此门控，不看 profile 名
 * @param orientations      已连接几何实际报告的方向；没有几何或已断开时为空列表
 */
public record ConnectionSnapshot(
        SessionState state,
        boolean combosLocked,
        boolean connectEnabled,
        boolean disconnectEnabled,
        List<DeviceCandidate> candidates,
        DeviceCandidate selected,
        String profileName,
        String statusText,
        String placeholderText,
        List<String> logLines,
        int screenWidth,
        int screenHeight,
        boolean geometryFallback,
        String warning,
        DeviceCapabilities capabilities,
        List<DisplayGeometry.Orientation> orientations) {

    public ConnectionSnapshot {
        candidates = List.copyOf(candidates);
        logLines = List.copyOf(logLines);
        orientations = List.copyOf(orientations);
    }
}

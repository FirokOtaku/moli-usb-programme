package space.firok.moli.usb.gui;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 系统盘选择与占用百分比。
 *
 * <p>只解析 {@code user.dir} 的盘符，或在没有盘符时回退 {@code C:\}。
 * 不调用 {@code File.listRoots}，也不枚举网络盘或可移动盘；那些查询可能卡住。
 * 真正读容量时只对选出的这一个根路径调用 {@link File#getTotalSpace()}。</p>
 */
final class SystemDrive {

    static final String FALLBACK_ROOT = "C:\\";

    private SystemDrive() {
    }

    /**
     * 从路径字符串取出本地盘根，例如 {@code D:\projects\app} → {@code D:\}。
     * 相对路径、空值和 UNC 都回退 {@code C:\}，避免去查询网络路径。
     * 本方法不访问磁盘。
     */
    static String rootOf(String userDir) {
        String letter = driveLetter(userDir);
        return letter == null ? FALLBACK_ROOT : letter + ":\\";
    }

    /**
     * 由已经读到的字节数计算占用百分比。不访问磁盘。
     * {@code freeBytes} 大于总量时视为 0% 占用。
     */
    static double usedPercent(long totalBytes, long freeBytes) {
        if (totalBytes <= 0) {
            throw new IllegalArgumentException("容量必须为正");
        }
        long free = Math.max(0, freeBytes);
        long used = free >= totalBytes ? 0 : totalBytes - free;
        return SystemSamples.clampPercent(used * 100.0 / totalBytes);
    }

    /**
     * 只查询 {@code root} 这一个路径。不要改成枚举全部文件存储。
     */
    static double queryUsedPercent(String root) {
        File volume = new File(root);
        long total = volume.getTotalSpace();
        if (total <= 0) {
            throw new IllegalStateException("系统盘容量无效: " + root);
        }
        return usedPercent(total, volume.getFreeSpace());
    }

    private static String driveLetter(String userDir) {
        if (userDir == null || userDir.isBlank()) {
            return null;
        }
        try {
            Path root = Path.of(userDir).getRoot();
            if (root == null) {
                return null;
            }
            String text = root.toString();
            if (text.length() < 2 || text.charAt(1) != ':') {
                return null;
            }
            char letter = text.charAt(0);
            if (!Character.isLetter(letter)) {
                return null;
            }
            return String.valueOf(Character.toUpperCase(letter));
        } catch (InvalidPathException ignored) {
            return null;
        }
    }
}

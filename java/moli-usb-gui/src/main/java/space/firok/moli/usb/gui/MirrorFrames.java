package space.firok.moli.usb.gui;

/**
 * 把主屏捕获缩进面板。默认走已有的 contain；铺满时走已有的 cover。
 * 照片不经过这里，留边颜色与图片模式的 contain 相同。
 */
final class MirrorFrames {

    private MirrorFrames() {
    }

    static int[] scale(int srcWidth, int srcHeight, int[] rgb,
                       int panelWidth, int panelHeight, boolean coverCrop) {
        if (coverCrop) {
            return FrameOps.scaleCover(srcWidth, srcHeight, rgb, panelWidth, panelHeight);
        }
        return FrameOps.scaleContain(srcWidth, srcHeight, rgb, panelWidth, panelHeight, 0);
    }
}

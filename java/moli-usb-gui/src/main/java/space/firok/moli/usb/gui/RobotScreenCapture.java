package space.firok.moli.usb.gui;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * 用 {@link Robot} 捕获主显示器。不捕获虚拟桌面，也不遍历 {@code getScreenDevices()}。
 * 调用方负责不要在上一帧捕获或发送尚未结束时再进来。
 */
final class RobotScreenCapture implements ScreenCapture {

    @Override
    public CapturedScreen capturePrimary() {
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (device == null) {
            throw new IllegalStateException("没有主显示器");
        }
        Rectangle bounds = device.getDefaultConfiguration().getBounds();
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw new IllegalStateException("主显示器尺寸无效");
        }
        try {
            Robot robot = new Robot(device);
            BufferedImage image = robot.createScreenCapture(bounds);
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("主显示器捕获为空");
            }
            int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
            return new CapturedScreen(width, height, rgb);
        } catch (AWTException error) {
            throw new IllegalStateException("无法捕获主显示器", error);
        }
    }
}

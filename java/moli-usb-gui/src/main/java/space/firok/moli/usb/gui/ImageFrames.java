package space.firok.moli.usb.gui;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 用 ImageIO 读取 jpg/png/bmp，再交给 {@link FrameOps} 缩放。不经过 JavaFX。
 */
final class ImageFrames {

    private ImageFrames() {
    }

    static Rgb565Image load(Path file, int panelWidth, int panelHeight, boolean coverCrop) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            throw new IOException("无法读取图片（仅支持 jpg、png、bmp）");
        }
        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IOException("图片尺寸无效");
        }
        BufferedImage rgb = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, srcWidth, srcHeight);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        int[] src = rgb.getRGB(0, 0, srcWidth, srcHeight, null, 0, srcWidth);
        int[] scaled = coverCrop
                ? FrameOps.scaleCover(srcWidth, srcHeight, src, panelWidth, panelHeight)
                : FrameOps.scaleContain(srcWidth, srcHeight, src, panelWidth, panelHeight, 0);
        return new Rgb565Image(panelWidth, panelHeight, scaled);
    }
}

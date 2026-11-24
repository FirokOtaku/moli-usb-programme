package space.firok.moli.usb.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import space.firok.moli.usb.api.domain.Rgb565;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

class ImageFrameConverterTest {

    @Test
    void colorHelpersAcceptRgbArgbAndAwtColor() {
        assertEquals(Rgb565.RED, Rgb565.fromRgb(0xFF0000));
        assertEquals(Rgb565.GREEN, Rgb565.fromArgb(0x0000FF00));
        assertEquals(Rgb565.BLUE, Rgb565.fromColor(new Color(0, 0, 255, 20)));
    }

    @Test
    void compositesAlphaAndFitsWithPadding() {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x80FF0000);
        image.setRGB(1, 0, 0x000000FF);
        ImageWriteOptions options = new ImageWriteOptions(
                ImageWriteOptions.AlphaMode.COMPOSITE, Rgb565.BLUE,
                ImageWriteOptions.ScaleMode.FIT, Rgb565.BLACK,
                ImageWriteOptions.Compression.NONE);

        int[] pixels = ImageFrameConverter.convert(image, 2, 2, options);

        assertArrayEquals(new int[]{Rgb565.fromRgb888(128, 0, 127).value(), Rgb565.BLUE.value(),
                        Rgb565.BLACK.value(), Rgb565.BLACK.value()},
                pixels);
    }

    @Test
    void fillCropsTheWideImageAndStretchUsesTheWholeSource() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        image.setRGB(1, 0, Color.GREEN.getRGB());
        image.setRGB(2, 0, Color.BLUE.getRGB());
        ImageWriteOptions fill = new ImageWriteOptions(
                ImageWriteOptions.AlphaMode.IGNORE, Rgb565.BLACK,
                ImageWriteOptions.ScaleMode.FILL, Rgb565.BLACK,
                ImageWriteOptions.Compression.NONE);

        assertArrayEquals(new int[]{Rgb565.GREEN.value()},
                ImageFrameConverter.convert(image, 1, 1, fill));
        assertArrayEquals(new int[]{Rgb565.RED.value(), Rgb565.GREEN.value(), Rgb565.BLUE.value()},
                ImageFrameConverter.convert(image, 3, 1, new ImageWriteOptions(
                        ImageWriteOptions.AlphaMode.IGNORE, Rgb565.BLACK,
                        ImageWriteOptions.ScaleMode.STRETCH, Rgb565.BLACK,
                        ImageWriteOptions.Compression.NONE)));
    }
}

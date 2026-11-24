package space.firok.moli.usb.api;

import space.firok.moli.usb.api.domain.Rgb565;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Objects;

final class ImageFrameConverter {

    private ImageFrameConverter() {
    }

    static int[] convert(BufferedImage image, int width, int height, ImageWriteOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("target size must be positive: " + width + "x" + height);
        }

        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        int[] source = image.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth);
        int[] output = new int[Math.multiplyExact(width, height)];
        int padding = options.paddingColor().value();
        Arrays.fill(output, padding);

        int drawWidth = width;
        int drawHeight = height;
        int sourceX = 0;
        int sourceY = 0;
        int sampleWidth = sourceWidth;
        int sampleHeight = sourceHeight;
        if (options.scaleMode() != ImageWriteOptions.ScaleMode.STRETCH) {
            boolean fit = options.scaleMode() == ImageWriteOptions.ScaleMode.FIT;
            boolean widthLimited = (long) width * sourceHeight <= (long) height * sourceWidth;
            if (widthLimited == fit) {
                drawHeight = Math.max(1, (int) ((long) sourceHeight * width / sourceWidth));
                drawWidth = width;
            } else {
                drawWidth = Math.max(1, (int) ((long) sourceWidth * height / sourceHeight));
                drawHeight = height;
            }
            drawWidth = Math.min(width, drawWidth);
            drawHeight = Math.min(height, drawHeight);
            if (fit) {
                sourceX = 0;
                sourceY = 0;
            } else {
                if (widthLimited) {
                    sampleWidth = Math.max(1, (int) ((long) sourceHeight * width / height));
                    sourceX = (sourceWidth - sampleWidth) / 2;
                } else {
                    sampleHeight = Math.max(1, (int) ((long) sourceWidth * height / width));
                    sourceY = (sourceHeight - sampleHeight) / 2;
                }
            }
        }

        int offsetX = options.scaleMode() == ImageWriteOptions.ScaleMode.FIT ? (width - drawWidth) / 2 : 0;
        int offsetY = options.scaleMode() == ImageWriteOptions.ScaleMode.FIT ? (height - drawHeight) / 2 : 0;
        int background = options.alphaBackground().value();
        for (int y = 0; y < drawHeight; y++) {
            int sourceRow = sourceY + (int) ((long) y * sampleHeight / drawHeight);
            int targetRow = (offsetY + y) * width + offsetX;
            for (int x = 0; x < drawWidth; x++) {
                int sourceColumn = sourceX + (int) ((long) x * sampleWidth / drawWidth);
                int argb = source[sourceRow * sourceWidth + sourceColumn];
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (options.alphaMode() == ImageWriteOptions.AlphaMode.COMPOSITE) {
                    int alpha = argb >>> 24;
                    red = composite(red, (background >>> 11 & 0x1F) * 255 / 31, alpha);
                    green = composite(green, (background >>> 5 & 0x3F) * 255 / 63, alpha);
                    blue = composite(blue, (background & 0x1F) * 255 / 31, alpha);
                }
                output[targetRow + x] = Rgb565.fromRgb888(red, green, blue).value();
            }
        }
        return output;
    }

    private static int composite(int foreground, int background, int alpha) {
        return (foreground * alpha + background * (255 - alpha) + 127) / 255;
    }
}

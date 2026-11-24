package space.firok.moli.usb.api;

import space.firok.moli.usb.api.domain.Rgb565;

import java.util.Objects;

/** Options for converting and sending a host-rendered image. */
public record ImageWriteOptions(
        AlphaMode alphaMode,
        Rgb565 alphaBackground,
        ScaleMode scaleMode,
        Rgb565 paddingColor,
        Compression compression) {

    public static ImageWriteOptions defaults() {
        return new ImageWriteOptions(AlphaMode.COMPOSITE, Rgb565.BLACK,
                ScaleMode.FIT, Rgb565.BLACK, Compression.PROFILE_COMPACT);
    }

    public ImageWriteOptions {
        Objects.requireNonNull(alphaMode, "alphaMode");
        Objects.requireNonNull(alphaBackground, "alphaBackground");
        Objects.requireNonNull(scaleMode, "scaleMode");
        Objects.requireNonNull(paddingColor, "paddingColor");
        Objects.requireNonNull(compression, "compression");
    }

    public enum AlphaMode {
        /** Discard alpha and use the source RGB components unchanged. */
        IGNORE,
        /** Composite translucent pixels onto {@link #alphaBackground()}. */
        COMPOSITE
    }

    /** How the source image is mapped to the target rectangle. */
    public enum ScaleMode {
        /** Preserve aspect ratio, padding unused space with {@link #paddingColor()}. */
        FIT,
        /** Preserve aspect ratio, cropping the centered overflow. */
        FILL,
        /** Resize width and height independently. */
        STRETCH
    }

    /** Selects the wire encoding; it does not change the target resolution. */
    public enum Compression {
        NONE,
        RLE,
        /** Use the compact format associated with the selected device profile. */
        PROFILE_COMPACT
    }
}

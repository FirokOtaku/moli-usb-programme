// Public entry point of `package:space_firok_moli_usb_protocol`.
//
// Only the stable surface is exported here. Internal helpers (byte level
// staging, RLE block details, SFR table entry layout, the serial queue) are
// reachable through `src/` for tests but are not part of the published API.
//
// Layering enforced by this file:
//   domain  -> session -> model -> codec -> transport
//
// Nothing exported here imports dart:io, dart:html, dart:ffi, dart:js_interop
// or package:flutter, so the same code compiles for the Dart VM (Windows) and
// for the browser via `dart compile js`.

library;

// ---------------------------------------------------------------- error model
export 'src/errors.dart' show MoliErrorKind, MoliException;

// ----------------------------------------------------------- domain value API
export 'src/model/device_candidate.dart' show DeviceCandidate;
export 'src/model/device_capabilities.dart' show DeviceCapabilities;
export 'src/model/device_id.dart' show DeviceId;
export 'src/model/device_info.dart' show DeviceInfo;
export 'src/model/device_profile.dart'
    show DeviceProfile, FlashMediumProfileKind;
export 'src/model/display_geometry.dart'
    show DisplayGeometry, DisplayOrigin, PixelFormat;
export 'src/model/image_frame.dart'
    show
        RgbaImage,
        ImageWriteOptions,
        ImageAlphaMode,
        ImageScaleMode,
        ImageCompression;
export 'src/model/input_event.dart' show InputEvent, InputSource, InputState;

// ---------------------------------------------------------- domain operations
export 'src/domain/device_manager.dart'
    show MoliDeviceManager, MoliDeviceApi, DeviceSelector;

// ------------------------------------------------------------------- session
export 'src/session/session.dart'
    show MoliSession, ImageWriteQueue, ImageOverflowPolicy, ImageSendResult;
export 'src/session/session_options.dart' show SessionOptions;

// ----------------------------------------------------------------- transport
export 'src/transport/transport.dart'
    show
        TransportPort,
        TransportProvider,
        TransportPortDescriptor,
        TransportUsbMetadata,
        CancelToken,
        ByteAccumulator,
        TransportPortExtension;
export 'src/transport/serial_settings.dart'
    show
        SerialSettings,
        SerialParity,
        SerialStopBits,
        SerialPortProfile;

// ------------------------------------------------------------------- codec
// The codec layer is public where a caller legitimately needs it: frame
// construction for custom commands, the geometry/color value types, the
// hand-built encoders used by the tests, and the value types callers pass
// around. Byte-level internal helpers stay unexported.
export 'src/codec/binary.dart'
    show Rgb565Color, Rgb565Colors, Rect, BigEndianCodec, toHex, parseHex;
export 'src/codec/frame.dart'
    show Frame, ResponseReader, kFrameLength;
export 'src/codec/display.dart'
    show
        DisplayCodec,
        DisplaySub,
        DisplayAction,
        FlashColorPageEncoding,
        ScratchCodec,
        PixelStreamEncoder,
        Rgb565Pixels;
export 'src/codec/flash.dart'
    show
        StorageGeometry,
        RangeValues,
        FlashCodec,
        FlashSub,
        FlashMediumProfile,
        HighSpeedWriteStart,
        StorageCapabilities;
export 'src/codec/adc_rle.dart'
    show AdcCodec, RleCodec, KeyState, KeyEvent, AdcKeyDetector;
export 'src/codec/handshake.dart'
    show HandshakeCodec, HandshakeResult, kHandshakeChallenge, kMsnPrefix;
export 'src/codec/sfr.dart'
    show SfrCodec, SfrOp;

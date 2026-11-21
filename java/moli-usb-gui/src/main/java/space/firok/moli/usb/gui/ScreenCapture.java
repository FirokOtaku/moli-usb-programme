package space.firok.moli.usb.gui;

/**
 * 主显示器捕获。只捕获主屏，不遍历其它显示器。应在 JavaFX 线程之外调用。
 */
interface ScreenCapture {

    CapturedScreen capturePrimary();
}

package space.firok.moli.usb.gui;

/**
 * 上位机画面模式。包含系统状态和桌面镜像。不含灯、背光、方向、按键、Flash 烧录或 probeLink。
 */
enum ContentMode {
    SOLID("纯色"),
    IMAGE("图片"),
    STATIC_TEXT("静态文本"),
    SCROLL("滚动文本"),
    CLOCK("时钟"),
    STATS("系统状态"),
    MIRROR("桌面镜像");

    private final String label;

    ContentMode(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

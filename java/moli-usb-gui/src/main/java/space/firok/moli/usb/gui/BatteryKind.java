package space.firok.moli.usb.gui;

/** 电池是否存在。读失败与没有电池要分开显示。 */
enum BatteryKind {
    PRESENT,
    ABSENT,
    UNAVAILABLE
}

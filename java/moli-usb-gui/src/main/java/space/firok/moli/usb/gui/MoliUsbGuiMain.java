package space.firok.moli.usb.gui;

import javafx.application.Application;

/**
 * 进程入口。
 *
 * <p>{@link MoliUsbGuiApp} 继承 {@code javafx.application.Application}。
 * JDK 若直接启动这种主类，会先在启动模块层里找 {@code javafx.graphics}；
 * 找不到就报“缺少 JavaFX 运行时组件”，不会进入 {@code main}。
 * JavaFX 已经由 {@code org.openjfx:javafx-controls} 放在 classpath 上，
 * 因此从这里调用 {@link Application#launch(Class, String[])}。</p>
 */
public final class MoliUsbGuiMain {

    private MoliUsbGuiMain() {
    }

    public static void main(String[] args) {
        Application.launch(MoliUsbGuiApp.class, args);
    }
}

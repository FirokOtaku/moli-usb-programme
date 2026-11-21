package space.firok.moli.usb.gui;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import javafx.util.StringConverter;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.InputEvent;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 方向、背光、RGB 灯和按键。是否可操作只看快照里的能力与几何，不看 profile 名。
 *
 * <p>控件回调只调用 {@link DeviceControlModel}。设备 IO 经
 * {@link ConnectionController#submitCapability} 进入 {@code moli-usb-gui-io}。
 * 按键库回调先切到界面线程，再改标签和日志。</p>
 */
final class CapabilityPanel {

    private static final Duration BACKLIGHT_DEBOUNCE = Duration.millis(200);

    private final ConnectionController controller;
    private final DeviceControlModel model;
    private final Parent root;
    private final ComboBox<DisplayGeometry.Orientation> orientations = new ComboBox<>();
    private final Label orientationHint = hintLabel();
    private final Slider backlight = new Slider(0, 1000, 1000);
    private final Label backlightValue = new Label("1000");
    private final Label backlightHint = hintLabel();
    private final ColorPicker ledColor = new ColorPicker(Color.web("#E04040"));
    private final Spinner<Integer> lampCount = new Spinner<>(1, 256, DeviceControlModel.DEFAULT_LAMP_COUNT);
    private final Button applyLeds = new Button("应用");
    private final Label ledsHint = hintLabel();
    private final Label buttonState = new Label("等待按键");
    private final Label buttonHint = hintLabel();
    private final PauseTransition backlightDebounce = new PauseTransition(BACKLIGHT_DEBOUNCE);

    private ConnectionSnapshot snapshot;
    private boolean sessionLive;
    private boolean applying;
    private int pendingPwm = 1000;

    CapabilityPanel(ConnectionController controller, Executor uiExecutor) {
        this.controller = controller;
        this.model = new DeviceControlModel(controller::submitCapability, uiExecutor);
        this.root = build();
    }

    Parent root() {
        return root;
    }

    /** 断开、断链或窗口退出时调用。不等待正在进行的串口写。 */
    void stop() {
        backlightDebounce.stop();
        model.cancelBacklight();
        model.stopButtons();
    }

    void applyConnection(ConnectionSnapshot next) {
        boolean connected = next.state() == SessionState.CONNECTED;
        boolean became = connected && !sessionLive;
        boolean left = !connected && sessionLive;
        sessionLive = connected;
        snapshot = next;
        if (left) {
            stop();
        }
        updateOrientation(next);
        updateBacklight(next);
        updateLeds(next);
        updateButton(next, became);
        if (became && DeviceControlModel.buttonEnabled(next)) {
            model.subscribeButton(next, this::showButton);
        }
    }

    private Parent build() {
        orientations.setConverter(new StringConverter<>() {
            @Override
            public String toString(DisplayGeometry.Orientation orientation) {
                return orientation == null ? "" : orientationLabel(orientation);
            }

            @Override
            public DisplayGeometry.Orientation fromString(String text) {
                return null;
            }
        });
        orientations.setPrefWidth(160);
        orientations.setPromptText("选择方向");
        orientations.setOnAction(event -> {
            if (applying || snapshot == null) {
                return;
            }
            model.requestOrientation(snapshot, orientations.getValue());
        });

        backlight.setBlockIncrement(10);
        backlight.setPrefWidth(150);
        backlight.valueProperty().addListener((obs, oldValue, value) -> {
            int pwm = (int) Math.round(value.doubleValue());
            backlightValue.setText(Integer.toString(pwm));
            if (applying || !sessionLive) {
                return;
            }
            pendingPwm = pwm;
            backlightDebounce.playFromStart();
        });
        backlightDebounce.setOnFinished(event -> {
            if (!sessionLive || snapshot == null) {
                return;
            }
            model.requestBacklight(snapshot, pendingPwm);
        });

        lampCount.setEditable(true);
        lampCount.setPrefWidth(80);
        applyLeds.setOnAction(event -> applyLedColor());

        buttonHint.setText(DeviceControlModel.BUTTON_ABSENT);
        backlightHint.setText(DeviceControlModel.BACKLIGHT_UNSUPPORTED);
        ledsHint.setText(DeviceControlModel.LEDS_UNSUPPORTED);

        Label title = new Label("设备控制");
        title.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(8,
                title,
                row(new Label("方向"), orientations),
                orientationHint,
                row(new Label("背光"), backlight, backlightValue),
                backlightHint,
                new Label("RGB 灯"),
                row(new Label("颜色"), ledColor, new Label("灯数"), lampCount, applyLeds),
                ledsHint,
                new Label("按键"),
                buttonState,
                buttonHint);
        box.setPadding(new Insets(4, 8, 8, 12));
        box.setPrefWidth(280);
        box.setMinWidth(250);
        return box;
    }

    private void applyLedColor() {
        if (snapshot == null) {
            return;
        }
        try {
            lampCount.commitValue();
        } catch (RuntimeException ignored) {
            // 非法输入保持上一次合法值。
        }
        Integer count = lampCount.getValue();
        Color color = ledColor.getValue() == null ? Color.BLACK : ledColor.getValue();
        model.requestLeds(snapshot, channel(color.getRed()), channel(color.getGreen()),
                channel(color.getBlue()), count == null ? DeviceControlModel.DEFAULT_LAMP_COUNT : count);
    }

    private void showButton(InputEvent event) {
        if (!sessionLive) {
            return;
        }
        String text = event.type() == InputEvent.Type.PRESSED ? "按下" : "释放";
        buttonState.setText(text);
        controller.appendLog("按键：" + text);
    }

    private void updateOrientation(ConnectionSnapshot next) {
        applying = true;
        try {
            List<DisplayGeometry.Orientation> options = next.orientations();
            orientations.getItems().setAll(options);
            boolean enabled = DeviceControlModel.orientationEnabled(next);
            orientations.setDisable(!enabled);
            String hint = enabled ? null : DeviceControlModel.orientationHint(next);
            showHint(orientationHint, hint);
            if (options.size() == 1) {
                orientations.setValue(options.getFirst());
            } else if (orientations.getValue() != null && !options.contains(orientations.getValue())) {
                orientations.setValue(null);
            }
        } finally {
            applying = false;
        }
    }

    private void updateBacklight(ConnectionSnapshot next) {
        boolean enabled = DeviceControlModel.backlightEnabled(next);
        backlight.setDisable(!enabled);
        showHint(backlightHint, enabled ? null : DeviceControlModel.BACKLIGHT_UNSUPPORTED);
        if (!enabled) {
            backlightDebounce.stop();
            model.cancelBacklight();
        }
    }

    private void updateLeds(ConnectionSnapshot next) {
        boolean enabled = DeviceControlModel.ledsEnabled(next);
        ledColor.setDisable(!enabled);
        lampCount.setDisable(!enabled);
        applyLeds.setDisable(!enabled);
        showHint(ledsHint, enabled ? null : DeviceControlModel.LEDS_UNSUPPORTED);
    }

    private void updateButton(ConnectionSnapshot next, boolean becameConnected) {
        boolean enabled = DeviceControlModel.buttonEnabled(next);
        buttonState.setVisible(enabled);
        buttonState.setManaged(enabled);
        showHint(buttonHint, enabled ? null : DeviceControlModel.BUTTON_ABSENT);
        if (becameConnected && enabled) {
            buttonState.setText("等待按键");
        }
    }

    private static void showHint(Label label, String text) {
        boolean visible = text != null;
        label.setText(visible ? text : "");
        label.setVisible(visible);
        label.setManaged(visible);
    }

    private static Label hintLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #666666;");
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    private static HBox row(javafx.scene.Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static String orientationLabel(DisplayGeometry.Orientation orientation) {
        return switch (orientation) {
            case NORMAL -> "正常";
            case ROTATE_90 -> "旋转 90°";
            case ROTATE_180 -> "旋转 180°";
            case ROTATE_270 -> "旋转 270°";
        };
    }

    private static int channel(double unit) {
        int value = (int) Math.round(unit * 255.0);
        if (value < 0) {
            return 0;
        }
        return Math.min(255, value);
    }
}

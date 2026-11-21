package space.firok.moli.usb.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import space.firok.moli.usb.api.domain.DeviceCandidate;
import space.firok.moli.usb.api.domain.DeviceProfile;

import java.time.Duration;

/**
 * MSU2 屏幕助手窗口。
 *
 * <p>工具栏仍是串口、刷新、profile、连接和断开。已连接后可以发送纯色、图片、静态文本、
 * 滚动文本、时钟、系统状态和桌面镜像，并按设备能力调节方向、背光、RGB 灯和按键。
 * 不包含 Flash 烧录、NAND 写入或固件更新。</p>
 *
 * <p>按钮处理只调用 {@link ConnectionController}、内容循环和能力控件。设备 IO 在
 * {@code moli-usb-gui-io} 上执行，不在 JavaFX 线程上调用 {@code MoliDevice}。</p>
 */
public final class MoliUsbGuiApp extends Application {

    private static final String SLOW_BAUD_WARNING = "所选 profile 波特率为 19200，发送整帧图片或桌面镜像会比较慢";

    private ConnectionController controller;
    private ContentPanel contentPanel;
    private CapabilityPanel capabilityPanel;
    private boolean applying;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        ComboBox<DeviceCandidate> devices = new ComboBox<>();
        devices.setPromptText("选择串口");
        devices.setPrefWidth(320);
        devices.setConverter(new StringConverter<>() {
            @Override
            public String toString(DeviceCandidate candidate) {
                return candidate == null ? "" : CandidateLabels.label(candidate);
            }

            @Override
            public DeviceCandidate fromString(String text) {
                return null;
            }
        });

        Button refresh = new Button("刷新");
        ComboBox<ProfileOption> profiles = new ComboBox<>();
        profiles.getItems().setAll(ProfileOption.ALL);
        profiles.setPrefWidth(120);

        Button connect = new Button("连接");
        Button disconnect = new Button("断开");
        Label status = new Label("未连接");
        status.setWrapText(true);

        Label geometryWarning = new Label();
        geometryWarning.setWrapText(true);
        geometryWarning.setStyle("-fx-text-fill: #8a5a00;");
        geometryWarning.setVisible(false);
        geometryWarning.setManaged(false);

        Label slowBaud = new Label(SLOW_BAUD_WARNING);
        slowBaud.setWrapText(true);
        slowBaud.setStyle("-fx-text-fill: #8a5a00;");
        slowBaud.setVisible(false);
        slowBaud.setManaged(false);

        Label placeholder = new Label("未连接");
        placeholder.setStyle("-fx-font-size: 18px;");
        VBox placeholderBox = new VBox(placeholder);
        placeholderBox.setAlignment(Pos.CENTER);
        placeholderBox.setPadding(new Insets(16));

        TextArea log = new TextArea();
        log.setEditable(false);
        log.setWrapText(true);
        log.setPrefRowCount(6);
        log.setPromptText("连接、断开和发送失败会显示在这里");
        Label logTitle = new Label("日志");
        VBox bottom = new VBox(4, logTitle, log);
        bottom.setPadding(new Insets(0, 10, 10, 10));
        VBox.setVgrow(log, Priority.ALWAYS);

        HBox toolbar = new HBox(8, devices, refresh, profiles, connect, disconnect);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(6, toolbar, status, geometryWarning, slowBaud);
        top.setPadding(new Insets(10));

        controller = new ConnectionController(new SerialDeviceSessionGateway(), Platform::runLater);
        controller.startIdleProbe(ConnectionController.DEFAULT_PROBE_INTERVAL);
        contentPanel = new ContentPanel(controller);
        capabilityPanel = new CapabilityPanel(controller, Platform::runLater);
        Parent contentRoot = contentPanel.root();
        Parent capabilityRoot = capabilityPanel.root();
        HBox connectedBox = new HBox(8, contentRoot, capabilityRoot);
        HBox.setHgrow(contentRoot, Priority.ALWAYS);
        StackPane center = new StackPane(connectedBox, placeholderBox);
        center.setPadding(new Insets(4, 8, 4, 8));

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(center);
        root.setBottom(bottom);

        controller.setListener(snapshot -> apply(snapshot, devices, profiles, connect, disconnect, status,
                geometryWarning, slowBaud, placeholder, placeholderBox, connectedBox, log));

        devices.setOnAction(event -> {
            if (!applying) {
                controller.selectCandidate(devices.getValue());
            }
        });
        profiles.setOnAction(event -> {
            if (!applying && profiles.getValue() != null) {
                controller.selectProfile(profiles.getValue().profile());
            }
        });
        refresh.setOnAction(event -> controller.refresh());
        connect.setOnAction(event -> controller.connect());
        disconnect.setOnAction(event -> {
            contentPanel.stopSending();
            capabilityPanel.stop();
            controller.disconnect();
        });

        apply(controller.snapshot(), devices, profiles, connect, disconnect, status,
                geometryWarning, slowBaud, placeholder, placeholderBox, connectedBox, log);
        controller.start();

        stage.setTitle("MSU2 屏幕助手");
        stage.setScene(new Scene(root, 1180, 720));
        stage.setMinWidth(900);
        stage.setMinHeight(520);
        stage.show();
    }

    @Override
    public void stop() {
        if (capabilityPanel != null) {
            capabilityPanel.stop();
        }
        if (contentPanel != null) {
            contentPanel.close();
        }
        if (controller != null) {
            controller.closeAndAwait(Duration.ofSeconds(60));
        }
    }

    private void apply(ConnectionSnapshot snapshot,
                       ComboBox<DeviceCandidate> devices,
                       ComboBox<ProfileOption> profiles,
                       Button connect,
                       Button disconnect,
                       Label status,
                       Label geometryWarning,
                       Label slowBaud,
                       Label placeholder,
                       VBox placeholderBox,
                       Parent connectedBox,
                       TextArea log) {
        applying = true;
        try {
            devices.getItems().setAll(snapshot.candidates());
            devices.setValue(snapshot.selected());
            devices.setDisable(snapshot.combosLocked());
            profiles.setValue(ProfileOption.fromProfileName(snapshot.profileName()));
            profiles.setDisable(snapshot.combosLocked());
            connect.setDisable(!snapshot.connectEnabled());
            disconnect.setDisable(!snapshot.disconnectEnabled());
            status.setText(snapshot.statusText());
            placeholder.setText(snapshot.placeholderText());
            String warningText = snapshot.warning();
            geometryWarning.setText(warningText == null ? "" : warningText);
            geometryWarning.setVisible(warningText != null);
            geometryWarning.setManaged(warningText != null);
            boolean slow = DeviceProfile.byName(snapshot.profileName()).serial().baudRate() == 19200;
            slowBaud.setVisible(slow);
            slowBaud.setManaged(slow);
            boolean connected = snapshot.state() == SessionState.CONNECTED;
            connectedBox.setVisible(connected);
            connectedBox.setManaged(connected);
            placeholderBox.setVisible(!connected);
            placeholderBox.setManaged(!connected);
            contentPanel.applyConnection(snapshot);
            capabilityPanel.applyConnection(snapshot);
            log.setText(String.join(System.lineSeparator(), snapshot.logLines()));
            log.positionCaret(log.getLength());
        } finally {
            applying = false;
        }
    }
}

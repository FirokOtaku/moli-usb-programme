package space.firok.moli.usb.gui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 已连接后的画面控制。控件只在已连接时由外层放开；设备 IO 不在 JavaFX 线程上发生。
 */
final class ContentPanel {

    private static final SpeedOption SPEED_SLOW = new SpeedOption("慢", 160, 1);
    private static final SpeedOption SPEED_MEDIUM = new SpeedOption("中", 80, 1);
    private static final SpeedOption SPEED_FAST = new SpeedOption("快", 40, 2);

    private final ConnectionController controller;
    private final ContentLoop contentLoop;
    private final Parent root;
    private final Map<ContentMode, VBox> modeBoxes = new EnumMap<>(ContentMode.class);
    private VBox textStyle;
    private Node textFontRow;
    private final Map<ContentMode, RadioButton> modeRadios = new EnumMap<>(ContentMode.class);

    private final Label sizeLabel = new Label();
    private final Label fileLabel = new Label("未选择文件");
    private final Label previewHint = new Label("尚无预览");
    private final ImageView preview = new ImageView();
    private final ColorPicker solidColor = new ColorPicker(Color.web("#1E6FE0"));
    private final ColorPicker textColor = new ColorPicker(Color.WHITE);
    private final ColorPicker backgroundColor = new ColorPicker(Color.BLACK);
    private final TextField staticText = new TextField();
    private final TextField scrollText = new TextField();
    private final CheckBox coverCrop = new CheckBox("裁剪铺满");
    private final CheckBox mirrorCover = new CheckBox("裁剪铺满");
    private final Spinner<Integer> fontSize = new Spinner<>(8, 72, 16);
    private final ComboBox<SpeedOption> scrollSpeed = new ComboBox<>();
    private final Button sendSolid = new Button("发送");
    private final Button chooseFile = new Button("选择图片");
    private final Button sendImage = new Button("发送");
    private final Button sendStatic = new Button("发送");
    private final Button applyScroll = new Button("开始滚动");

    private final AtomicReference<PreviewFrame> pendingPreview = new AtomicReference<>();
    private final AtomicBoolean previewQueued = new AtomicBoolean();

    private boolean ready;
    private boolean sessionLive;
    private volatile boolean closed;
    private int panelWidth;
    private int panelHeight;
    private Path imageFile;
    private File lastDirectory;

    ContentPanel(ConnectionController controller) {
        this.controller = controller;
        this.contentLoop = new ContentLoop(controller, this::acceptPreview);
        this.root = build();
        ready = true;
    }

    Parent root() {
        return root;
    }

    /** 断开前调用，让尚未开始的发送被取消。不等待正在进行的串口写。 */
    void stopSending() {
        contentLoop.stop();
    }

    void applyConnection(ConnectionSnapshot snapshot) {
        boolean connected = snapshot.state() == SessionState.CONNECTED;
        boolean becameConnected = connected && !sessionLive;
        boolean leftSession = !connected && sessionLive;
        sessionLive = connected;
        panelWidth = connected ? snapshot.screenWidth() : 0;
        panelHeight = connected ? snapshot.screenHeight() : 0;
        updateSizeLabel();
        updateSendEnabled();
        if (becameConnected) {
            pushSpec();
        } else if (leftSession) {
            contentLoop.stop();
            clearPreview();
        }
    }

    void close() {
        closed = true;
        contentLoop.close();
    }

    private Parent build() {
        ToggleGroup modes = new ToggleGroup();
        HBox modeRow = new HBox(12);
        modeRow.setAlignment(Pos.CENTER_LEFT);
        for (ContentMode mode : ContentMode.values()) {
            RadioButton radio = new RadioButton(mode.label());
            radio.setToggleGroup(modes);
            radio.setOnAction(event -> {
                showMode(mode);
                onUserEdit();
            });
            modeRadios.put(mode, radio);
            modeRow.getChildren().add(radio);
        }
        modeRadios.get(ContentMode.SOLID).setSelected(true);

        sizeLabel.setWrapText(true);
        VBox solidBox = solidBox();
        VBox imageBox = imageBox();
        VBox staticBox = staticBox();
        VBox scrollBox = scrollBox();
        VBox clockBox = clockBox();
        VBox statsBox = statsBox();
        VBox mirrorBox = mirrorBox();
        textStyle = textStyleBox();
        modeBoxes.put(ContentMode.SOLID, solidBox);
        modeBoxes.put(ContentMode.IMAGE, imageBox);
        modeBoxes.put(ContentMode.STATIC_TEXT, staticBox);
        modeBoxes.put(ContentMode.SCROLL, scrollBox);
        modeBoxes.put(ContentMode.CLOCK, clockBox);
        modeBoxes.put(ContentMode.STATS, statsBox);
        modeBoxes.put(ContentMode.MIRROR, mirrorBox);
        showMode(ContentMode.SOLID);

        preview.setPreserveRatio(true);
        preview.setSmooth(false);
        preview.setFitWidth(520);
        preview.setFitHeight(260);
        previewHint.setTextFill(Color.web("#cccccc"));
        StackPane previewBox = new StackPane(previewHint, preview);
        previewBox.setMinHeight(220);
        previewBox.setAlignment(Pos.CENTER);
        previewBox.setStyle("-fx-background-color: #1b1b1b; -fx-padding: 8;");
        Label previewTitle = new Label("预览");

        VBox form = new VBox(10, modeRow, sizeLabel, solidBox, imageBox, staticBox, scrollBox, clockBox, statsBox,
                mirrorBox, textStyle, previewTitle, previewBox);
        form.setPadding(new Insets(4, 8, 8, 8));
        VBox.setVgrow(previewBox, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    private VBox solidBox() {
        solidColor.valueProperty().addListener((obs, old, value) -> onUserEdit());
        sendSolid.setOnAction(event -> onUserEdit());
        HBox row = new HBox(8, new Label("颜色"), solidColor, sendSolid);
        row.setAlignment(Pos.CENTER_LEFT);
        return section(row);
    }

    private VBox imageBox() {
        fileLabel.setWrapText(true);
        chooseFile.setOnAction(event -> chooseImage());
        coverCrop.setOnAction(event -> onUserEdit());
        sendImage.setOnAction(event -> onUserEdit());
        Label hint = new Label("默认适应屏幕并留边。勾选后裁掉超出部分铺满面板。");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666666;");
        HBox row = new HBox(8, chooseFile, sendImage, coverCrop);
        row.setAlignment(Pos.CENTER_LEFT);
        return section(row, fileLabel, hint);
    }

    private VBox staticBox() {
        staticText.setPromptText("输入要显示的文字");
        staticText.setOnAction(event -> onUserEdit());
        sendStatic.setOnAction(event -> onUserEdit());
        return section(textRow(staticText, sendStatic));
    }

    private VBox scrollBox() {
        scrollText.setPromptText("输入要滚动的文字");
        scrollText.setOnAction(event -> onUserEdit());
        applyScroll.setOnAction(event -> onUserEdit());
        scrollSpeed.getItems().setAll(SPEED_SLOW, SPEED_MEDIUM, SPEED_FAST);
        scrollSpeed.setValue(SPEED_MEDIUM);
        scrollSpeed.setOnAction(event -> onUserEdit());
        HBox speed = new HBox(8, new Label("速度"), scrollSpeed);
        speed.setAlignment(Pos.CENTER_LEFT);
        return section(textRow(scrollText, applyScroll), speed);
    }

    private VBox textStyleBox() {
        textColor.valueProperty().addListener((obs, old, value) -> onUserEdit());
        backgroundColor.valueProperty().addListener((obs, old, value) -> onUserEdit());
        fontSize.valueProperty().addListener((obs, old, value) -> onUserEdit());
        HBox fontRow = new HBox(8, new Label("字号"), fontSize);
        fontRow.setAlignment(Pos.CENTER_LEFT);
        textFontRow = fontRow;
        HBox colors = new HBox(8, new Label("文字颜色"), textColor, new Label("背景颜色"), backgroundColor);
        colors.setAlignment(Pos.CENTER_LEFT);
        return section(fontRow, colors);
    }

    private VBox clockBox() {
        Label hint = new Label("由主机绘制当前时间与日期，大约每秒刷新一次，不使用设备字库。");
        hint.setWrapText(true);
        return section(hint);
    }

    private VBox statsBox() {
        Label hint = new Label("由主机绘制 CPU、内存、系统盘占用和电池电量，大约每秒刷新一次，不使用设备字库。没有电池时显示「无电池」。某一项读不到时显示不可用，不影响其它项。");
        hint.setWrapText(true);
        return section(hint);
    }

    private VBox mirrorBox() {
        mirrorCover.setOnAction(event -> onUserEdit());
        Label hint = new Label("只捕获主显示器，默认适应屏幕并留边。勾选后裁掉超出部分铺满面板。捕获或发送尚未结束时丢掉这一拍，不排队。");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666666;");
        return section(mirrorCover, hint);
    }

    private HBox textRow(TextField field, Button action) {
        field.setPrefWidth(280);
        HBox row = new HBox(8, new Label("文字"), field, action);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox section(javafx.scene.Node... nodes) {
        VBox box = new VBox(8, nodes);
        box.setPadding(new Insets(4, 0, 4, 0));
        return box;
    }

    private void showMode(ContentMode selected) {
        for (Map.Entry<ContentMode, VBox> entry : modeBoxes.entrySet()) {
            boolean visible = entry.getKey() == selected;
            entry.getValue().setVisible(visible);
            entry.getValue().setManaged(visible);
        }
        boolean textMode = selected == ContentMode.STATIC_TEXT
                || selected == ContentMode.SCROLL
                || selected == ContentMode.STATS;
        if (textStyle != null) {
            textStyle.setVisible(textMode);
            textStyle.setManaged(textMode);
        }
        if (textFontRow != null) {
            boolean showFont = selected == ContentMode.STATIC_TEXT || selected == ContentMode.SCROLL;
            textFontRow.setVisible(showFont);
            textFontRow.setManaged(showFont);
        }
        updateSendEnabled();
    }

    private void onUserEdit() {
        if (!ready || !sessionLive) {
            return;
        }
        pushSpec();
    }

    private void pushSpec() {
        updateSendEnabled();
        contentLoop.update(currentSpec());
    }

    private ContentSpec currentSpec() {
        ContentMode mode = selectedMode();
        SpeedOption speed = scrollSpeed.getValue() == null ? SPEED_MEDIUM : scrollSpeed.getValue();
        int period = switch (mode) {
            case SCROLL -> speed.periodMillis();
            case CLOCK, STATS -> 1000;
            case MIRROR -> 100;
            case SOLID, IMAGE, STATIC_TEXT -> 0;
        };
        int foreground = switch (mode) {
            case SOLID -> pack(solidColor.getValue());
            case IMAGE, MIRROR -> 0;
            case STATIC_TEXT, SCROLL, CLOCK, STATS -> pack(textColor.getValue());
        };
        boolean cover = switch (mode) {
            case IMAGE -> coverCrop.isSelected();
            case MIRROR -> mirrorCover.isSelected();
            default -> false;
        };
        return new ContentSpec(
                mode,
                panelWidth,
                panelHeight,
                foreground,
                pack(backgroundColor.getValue()),
                mode == ContentMode.SCROLL ? scrollText.getText() : staticText.getText(),
                imageFile,
                cover,
                fontSize.getValue() == null ? 16 : fontSize.getValue(),
                period,
                speed.step());
    }

    private ContentMode selectedMode() {
        for (Map.Entry<ContentMode, RadioButton> entry : modeRadios.entrySet()) {
            if (entry.getValue().isSelected()) {
                return entry.getKey();
            }
        }
        return ContentMode.SOLID;
    }

    private void chooseImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图片");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "图片 (jpg, png, bmp)", "*.jpg", "*.jpeg", "*.png", "*.bmp"));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        Window owner = chooseFile.getScene() == null ? null : chooseFile.getScene().getWindow();
        File file = chooser.showOpenDialog(owner);
        if (file == null) {
            return;
        }
        lastDirectory = file.getParentFile();
        imageFile = file.toPath();
        fileLabel.setText(file.getName());
        onUserEdit();
    }

    private void updateSizeLabel() {
        if (sessionLive && (panelWidth <= 0 || panelHeight <= 0)) {
            sizeLabel.setText("屏幕尺寸为 0，无法发送画面");
            sizeLabel.setStyle("-fx-text-fill: #8a5a00;");
            return;
        }
        if (panelWidth > 0 && panelHeight > 0) {
            sizeLabel.setText("面板 " + panelWidth + "×" + panelHeight);
            sizeLabel.setStyle("");
            return;
        }
        sizeLabel.setText("");
        sizeLabel.setStyle("");
    }

    private void updateSendEnabled() {
        boolean sized = sessionLive && panelWidth > 0 && panelHeight > 0;
        sendSolid.setDisable(!sized);
        sendImage.setDisable(!sized || imageFile == null);
        sendStatic.setDisable(!sized || staticText.getText() == null || staticText.getText().isBlank());
        applyScroll.setDisable(!sized || scrollText.getText() == null || scrollText.getText().isBlank());
        chooseFile.setDisable(!sessionLive);
    }

    private void acceptPreview(PreviewFrame frame) {
        if (closed) {
            return;
        }
        pendingPreview.set(frame);
        if (previewQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushPreview);
        }
    }

    private void flushPreview() {
        previewQueued.set(false);
        PreviewFrame frame = pendingPreview.getAndSet(null);
        if (!closed && frame != null) {
            showPreview(frame);
        }
        if (pendingPreview.get() != null && previewQueued.compareAndSet(false, true)) {
            Platform.runLater(this::flushPreview);
        }
    }

    private void showPreview(PreviewFrame frame) {
        WritableImage image = new WritableImage(frame.width(), frame.height());
        image.getPixelWriter().setPixels(
                0,
                0,
                frame.width(),
                frame.height(),
                PixelFormat.getIntArgbInstance(),
                frame.argb(),
                0,
                frame.width());
        preview.setImage(image);
        previewHint.setVisible(false);
        previewHint.setManaged(false);
    }

    private void clearPreview() {
        preview.setImage(null);
        previewHint.setVisible(true);
        previewHint.setManaged(true);
    }

    private static int pack(Color color) {
        if (color == null) {
            return 0;
        }
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return FrameOps.pack(r, g, b);
    }

    private record SpeedOption(String label, int periodMillis, int step) {
        @Override
        public String toString() {
            return label;
        }
    }
}

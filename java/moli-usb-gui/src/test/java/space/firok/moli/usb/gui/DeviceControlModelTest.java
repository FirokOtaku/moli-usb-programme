package space.firok.moli.usb.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.InputSubscription;
import space.firok.moli.usb.api.MoliDevice;
import space.firok.moli.usb.api.domain.DeviceCapabilities;
import space.firok.moli.usb.api.domain.DeviceId;
import space.firok.moli.usb.api.domain.DeviceInfo;
import space.firok.moli.usb.api.domain.DeviceProfile;
import space.firok.moli.usb.api.domain.DisplayGeometry;
import space.firok.moli.usb.api.domain.InputEvent;
import space.firok.moli.usb.api.domain.Rect;
import space.firok.moli.usb.api.domain.Rgb565;
import space.firok.moli.usb.api.domain.StorageGeometry;
import space.firok.moli.usb.api.error.MoliUsbException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 能力控件模型。不启动 JavaFX，也不打开设备。
 */
class DeviceControlModelTest {

    @Test
    @DisplayName("不支持的能力不需要调用设备，也不看 profile 名")
    void unsupportedCapabilityDoesNotCallDevice() {
        AtomicInteger calls = new AtomicInteger();
        DeviceControlModel model = new DeviceControlModel((action, done) -> {
            calls.incrementAndGet();
            return true;
        }, Runnable::run);
        ConnectionSnapshot namedProButNoCaps = snapshot(
                DeviceCapabilities.of(true, true, false, false, false, false),
                List.of(DisplayGeometry.Orientation.NORMAL),
                DeviceProfile.PRO);
        assertFalse(DeviceControlModel.backlightEnabled(namedProButNoCaps));
        assertFalse(DeviceControlModel.ledsEnabled(namedProButNoCaps));
        assertFalse(DeviceControlModel.buttonEnabled(namedProButNoCaps));
        assertFalse(DeviceControlModel.orientationEnabled(namedProButNoCaps));
        assertEquals(DeviceControlModel.BACKLIGHT_UNSUPPORTED, "当前设备不支持背光");
        assertEquals(DeviceControlModel.ORIENTATION_SINGLE, DeviceControlModel.orientationHint(namedProButNoCaps));
        assertFalse(model.requestBacklight(namedProButNoCaps, 400));
        assertFalse(model.requestLeds(namedProButNoCaps, 1, 2, 3, DeviceControlModel.DEFAULT_LAMP_COUNT));
        assertFalse(model.requestOrientation(namedProButNoCaps, DisplayGeometry.Orientation.NORMAL));
        assertFalse(model.subscribeButton(namedProButNoCaps, event -> { }));
        assertEquals(0, calls.get(), "不支持的能力不得把任务交给设备");

        ConnectionSnapshot noGeometry = snapshot(
                DeviceCapabilities.of(true, false, false, false, false, false),
                List.of(),
                DeviceProfile.MINI);
        assertEquals(DeviceControlModel.ORIENTATION_NONE, DeviceControlModel.orientationHint(noGeometry));
        assertFalse(model.requestOrientation(noGeometry, DisplayGeometry.Orientation.ROTATE_90));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("同一种颜色先逐灯暂存，再只提交一次")
    void ledsAppliedOnceAfterAllLamps() {
        List<String> calls = new ArrayList<>();
        DeviceControlModel model = new DeviceControlModel((action, done) -> {
            action.accept(new RecordingDevice(calls, null));
            return true;
        }, Runnable::run);
        ConnectionSnapshot capable = snapshot(
                DeviceCapabilities.of(true, false, true, false, false, false),
                List.of(DisplayGeometry.Orientation.NORMAL, DisplayGeometry.Orientation.ROTATE_90),
                DeviceProfile.MINI);
        assertTrue(model.requestLeds(capable, 9, 8, 7, 3));
        assertEquals(List.of("setLed:0:9:8:7", "setLed:1:9:8:7", "setLed:2:9:8:7", "applyLeds"), calls);
    }

    @Test
    @DisplayName("按键回调先切到界面执行器，再碰界面")
    void buttonEventsHopBeforeUi() {
        AtomicReference<Consumer<InputEvent>> listener = new AtomicReference<>();
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger uiUpdates = new AtomicInteger();
        DeviceControlModel model = new DeviceControlModel((action, done) -> {
            action.accept(new RecordingDevice(new ArrayList<>(), listener));
            return true;
        }, queued::add);
        ConnectionSnapshot withButton = snapshot(
                DeviceCapabilities.of(true, false, false, false, false, true),
                List.of(DisplayGeometry.Orientation.NORMAL),
                DeviceProfile.LITE);
        assertTrue(model.subscribeButton(withButton, event -> uiUpdates.incrementAndGet()));
        listener.get().accept(new InputEvent(
                InputEvent.Source.BUTTON_0, InputEvent.Type.PRESSED, Instant.now()));
        assertEquals(0, uiUpdates.get(), "库回调不得直接更新界面");
        assertEquals(1, queued.size());
        queued.getFirst().run();
        assertEquals(1, uiUpdates.get());
    }

    private static ConnectionSnapshot snapshot(DeviceCapabilities capabilities,
                                               List<DisplayGeometry.Orientation> orientations,
                                               String profileName) {
        return new ConnectionSnapshot(
                SessionState.CONNECTED,
                true,
                false,
                true,
                List.of(),
                null,
                profileName,
                "",
                "",
                List.of(),
                160,
                80,
                false,
                null,
                capabilities,
                orientations);
    }

    private static final class RecordingDevice implements MoliDevice {
        private final List<String> calls;
        private final AtomicReference<Consumer<InputEvent>> listener;

        private RecordingDevice(List<String> calls, AtomicReference<Consumer<InputEvent>> listener) {
            this.calls = calls;
            this.listener = listener;
        }

        @Override
        public DeviceId id() {
            return new DeviceId("UID", "COM1");
        }

        @Override
        public DeviceInfo info() {
            return new DeviceInfo(1, "UID", 160, 80, Optional.empty(), java.util.Map.of());
        }

        @Override
        public DeviceCapabilities capabilities() {
            return DeviceCapabilities.of(true, false, true, false, false, true);
        }

        @Override
        public Optional<DisplayGeometry> display() {
            return Optional.empty();
        }

        @Override
        public Optional<StorageGeometry> storage() {
            return Optional.empty();
        }

        @Override
        public String profileName() {
            return DeviceProfile.MINI;
        }

        @Override
        public void setOrientation(DisplayGeometry.Orientation orientation) {
            calls.add("orientation");
        }

        @Override
        public void setBacklight(int pwm) {
            calls.add("backlight");
        }

        @Override
        public void setLed(int index, int red, int green, int blue) {
            calls.add("setLed:" + index + ":" + red + ":" + green + ":" + blue);
        }

        @Override
        public void applyLeds() {
            calls.add("applyLeds");
        }

        @Override
        public void probeLink() {
            calls.add("probe");
        }

        @Override
        public void discardPending() {
            calls.add("drain");
        }

        @Override
        public void fillRect(Rect rect, Rgb565 color) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showStoredColorImage(int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void showStoredMonoImage(int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void streamImage(Rect rect, int[] pixels) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void streamImageRle(Rect rect, int[] pixels, int maxFrames) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] readStorage(long address, int length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void erasePages(long startPage, long pageCount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writePages(long startPage, byte[] data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputSubscription subscribeInput(Consumer<InputEvent> listener) {
            this.listener.set(listener);
            return new InputSubscription() {
                @Override
                public boolean isActive() {
                    return true;
                }

                @Override
                public Optional<MoliUsbException> failure() {
                    return Optional.empty();
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void close() {
        }
    }
}

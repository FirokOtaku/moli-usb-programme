package space.firok.moli.usb.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import space.firok.moli.usb.api.domain.DeviceId;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class ImageWriteQueueTest {

    @Test
    void dropOldestKeepsNewestQueuedFrame() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MoliDevice device = (MoliDevice) Proxy.newProxyInstance(
                MoliDevice.class.getClassLoader(), new Class<?>[]{MoliDevice.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id")) return new DeviceId("test", "test");
                    if (method.getName().equals("writeImage")) {
                        writing.countDown();
                        release.await();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ImageWriteQueue queue = new ImageWriteQueue(device, ImageWriteOptions.defaults(), 1,
                ImageWriteQueue.OverflowPolicy.DROP_OLDEST, ignored -> { });
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        try {
            var first = queue.submit(image);
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            var dropped = queue.submit(image);
            var newest = queue.submit(image);

            assertEquals(ImageWriteQueue.SendResult.DROPPED, dropped.get(2, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(ImageWriteQueue.SendResult.SENT, first.get(2, TimeUnit.SECONDS));
            assertEquals(ImageWriteQueue.SendResult.SENT, newest.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            queue.close();
        }
    }
}

package space.firok.moli.usb.api.codec;

import space.firok.moli.usb.api.error.InvalidArgumentError;

/**
 * 编解码层：ADC 通道读取（命令 {@code 0x08}）。
 *
 * <p>帧为 {@code 08 channel 00 00 00 00}；响应的采样值是 byte 4..5 的大端 u16。
 * 具体通道语义（MINI 按键在通道 9 等）属于设备适配事实，由领域层封装，不在
 * 编解码层硬编码。</p>
 */
public final class AdcCodec {

    private AdcCodec() {
    }

    /** {@code 08 channel 00 00 00 00}：读取一个 ADC 通道。 */
    public static Frame readChannel(int channel) {
        Frame.checkByte("channel", channel);
        return Frame.of(Commands.ADC, channel, 0, 0, 0, 0);
    }

    /**
     * 从响应帧解出采样值。
     *
     * <p>响应必须先是 {@code 0x08} 命令；据此拒绝把其它命令的响应当成采样值。</p>
     */
    public static int decodeSample(Frame response) {
        if (response == null) {
            throw new InvalidArgumentError("AdcCodec.decodeSample", null, "response 不能为 null");
        }
        if (response.command() != Commands.ADC) {
            throw new space.firok.moli.usb.api.error.MalformedResponseError(
                    "AdcCodec.decodeSample", null,
                    "响应不是 ADC 帧（command=0x" + Integer.toHexString(response.command()) + "）",
                    response.toWire());
        }
        return response.u16AtData2();
    }
}
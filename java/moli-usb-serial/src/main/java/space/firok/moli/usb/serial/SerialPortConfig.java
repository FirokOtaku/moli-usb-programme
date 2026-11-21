package space.firok.moli.usb.serial;

import com.fazecast.jSerialComm.SerialPort;
import space.firok.moli.usb.api.domain.DeviceProfile;

import java.util.Objects;

/**
 * 串口参数到 jSerialComm 常量的映射。
 *
 * <p>抽成独立类型而不是散落在打开端口的代码里，是为了在没有真实串口的环境下也能测试
 * 映射结果（spec/verification.md 要求串口参数最终以实机确认为准，这里只做忠实翻译）。</p>
 *
 * <p>帧格式固定为 8N1：数据位 8、无校验、1 位停止位。这是按 pyserial 演示脚本的默认值
 * 推定的，属 spec/verification.md 待实机确认项；本类型不提供其它帧格式入口。</p>
 *
 * @param baudRate   波特率
 * @param dataBits   数据位（固定 8）
 * @param stopBits   jSerialComm 停止位常量（固定 {@link SerialPort#ONE_STOP_BIT}）
 * @param parity     jSerialComm 校验位常量（固定 {@link SerialPort#NO_PARITY}）
 * @param flowControl jSerialComm 流控位组合
 */
public record SerialPortConfig(int baudRate, int dataBits, int stopBits, int parity,
                               int flowControl) {

    /** 固定 8 数据位。 */
    public static final int DATA_BITS_8 = 8;

    public SerialPortConfig {
        if (baudRate <= 0) {
            throw new IllegalArgumentException("baudRate must be positive: " + baudRate);
        }
        if (dataBits != DATA_BITS_8) {
            throw new IllegalArgumentException("only 8 data bits are supported: " + dataBits);
        }
        if (flowControl < 0) {
            throw new IllegalArgumentException("flowControl must not be negative: " + flowControl);
        }
    }

    /**
     * 由领域层的串口参数快照翻译出 jSerialComm 配置。
     *
     * @param settings api 模块的 {@code DeviceProfile.SerialSettings}；不可为 null
     */
    public static SerialPortConfig from(DeviceProfile.SerialSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return new SerialPortConfig(
                settings.baudRate(),
                DATA_BITS_8,
                SerialPort.ONE_STOP_BIT,
                SerialPort.NO_PARITY,
                flowControlFor(settings.rtscts(), settings.xonxoff()));
    }

    /** 由裸参数构造配置，便于命令行直接指定波特率与流控。 */
    public static SerialPortConfig of(int baudRate, boolean rtscts, boolean xonxoff) {
        return from(new DeviceProfile.SerialSettings(baudRate, rtscts, xonxoff));
    }

    /**
     * 计算 jSerialComm 流控位。
     *
     * <p>RTS/CTS 硬件流控需要同时置位 RTS 与 CTS 两个标志；XON/XOFF 为软件流控，两个方向
     * 各自独立。两者可以同时启用，与 pyserial 的 {@code rtscts}/{@code xonxoff} 语义一致。</p>
     */
    public static int flowControlFor(boolean rtscts, boolean xonxoff) {
        int value = SerialPort.FLOW_CONTROL_DISABLED;
        if (rtscts) {
            value |= SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
        }
        if (xonxoff) {
            value |= SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                    | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
        }
        return value;
    }

    /** 是否启用了 RTS/CTS 硬件流控。 */
    public boolean rtscts() {
        return (flowControl & (SerialPort.FLOW_CONTROL_RTS_ENABLED
                | SerialPort.FLOW_CONTROL_CTS_ENABLED))
                == (SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED);
    }

    /** 是否启用了任一方向的 XON/XOFF 软件流控。 */
    public boolean xonxoff() {
        return (flowControl & (SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED)) != 0;
    }

    /** 人类可读摘要，用于探测工具输出。 */
    public String describe() {
        return baudRate + " 8N1"
                + (rtscts() ? " rtscts" : "")
                + (xonxoff() ? " xonxoff" : "")
                + (flowControl == 0 ? " 无流控" : "");
    }
}
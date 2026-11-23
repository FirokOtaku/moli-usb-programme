# 变更记录

## 2026-11-23

- JavaFX 与 Flutter 上位机为 MINI 开放方向切换；MINI、LITE、PRO 统一显示「正常」和「旋转 180°」，修正把方向值 `0x01` 标成 90° 的问题。

## 2026-11-22

- Dart 的 PRO 缺省几何从占位的 160×80 改为官方画面使用的 320×172，MINI 从 64×64 改为 160×80。自描述表首字节为 0 时停止读取，避免对没有表的 PRO 连续读 256 次 `0x0100`。缺表时的提示改为「自描述表没有给出 Lcd_X/Lcd_Y」，不再说设备报告了 0×0。COM7 实机对 `00 30 00 01 00 00` 没有 SFR 回执，只继续广播 `\0MSN01`。
- 空闲不再发送 `00 30 00 01 00 00`。官方 `MSN_Device_1_State_machine` 不轮询 SFR `0x0100`；`Read_M_u8(123)` 只在本地转图时用来「保持通信」，截图卡住时重发的是 `LCD_ADD`。这条读打进正在接收的像素流时，固件把它当成像素，画面只刷出一条然后不再应答。一次读超时不再拆掉连接。空闲只排空已经到达的 `\0MSN` 横幅。Java 串口的读超时在打开时设成一次 20 ms 半阻塞，不再在每次读取时改成 0 ms：jSerialComm 对 0 ms 会走 `TIMEOUT_NONBLOCKING`，`setComPortTimeouts` 失败并报「设置读超时 0 ms 失败」，把仍打开的 COM 拆掉。921600 的 RTS/CTS 在 `openPort` 之前写入。自描述表没给出 `Lcd_X`/`Lcd_Y` 时，不再说设备报告了 `0x0`。
- 照片和桌面镜像改为官方 `show_PC_Screen` / `MSN_compaction`（command `0x09`），不再发送 160×80 的 39006 字节未压缩帧。RLE 配对与 C 函数一致：第一段取满 15 个像素后，块内还有像素就继续编第二段。时钟和系统状态仍走原来的 RLE。Java 串口按 512 字节分块写完，避免 jSerialComm 在 1 秒写超时内对整段返回 0、把已经送出的前缀丢掉，固件因此停在像素接收里、直到重新插拔才再发 `\0MSN`。波特率维持官方 `serial.Serial` 实参：MINI 19200 无流控，LITE/PRO 921600 且 RTS/CTS。
- 修复实机屏幕尺寸被读成 `160x0`，以及写画面后空闲探活超时断开。自描述表的 family
  是 NUL 结尾字段；MINI 固件 V1.2 里 `Lcd_X` / `Lcd_Y` 是地址 `0x00` / `0x01` 的
  u16 SFR。以前把 family 后的 `00` 当成地址，表在 `Lcd_Y` 之前被截断，宽度碰巧读到
  地址 0 的 160，高度停在 0。地址 0 现在按合法寄存器读取。Java 与 Dart 同一解释。
- 照片和时钟不再逐帧等待 `0x04`、action `0x08`、action `0x09` 或 RLE 数据帧。官方
  `Write_LCD_Screen_fast1` 把这些帧一次写出且不读响应；`show_PC_Screen` 在
  `LCD_ADD` 之后只写 command `0x09` 的 RLE 帧，不再追加 action `0x09`。逐帧等待
  会让画面按 256 字节慢慢扫出来，并让随后的探活 `00 30 00 01 00 00` 在 800 ms 内
  超时，上位机因此把链路判成断开。没有把命令超时调大。Dart 对这一次批量写出按波特率
  放宽写超时，避免 19200 下整屏写出被 2 秒写超时截断。

- 跟进实机：PRO 不能仅凭 `MSNCN` 报已连通，启动需要方向命令回执；流式写屏必须确认区域动作。整帧占用会话队列，区域设置不逐帧等回执，像素不逐帧读回执，Dart 模式取消不会主动截断已经开始写入的画面。
- 照片和镜像按机型选压缩：MINI 使用 `02 04` 暂存区默认字和差量，LITE/PRO 使用 `MSN_compaction` 两色 RLE；修正 Dart 对 PRO 文本、时钟和状态画面误回退整帧未压缩的上界。
- 两套上位机遇到传输断链后约 1 秒开始同设备、同 profile 重连，最多四次，显式断开、选择变更或退出立即取消；Web 只重新枚举已授权端口。写超时可能已经发出未知长度，不重放。白闪和 COM6 间歇性写超时的硬件根因仍未确认。
- JavaFX 和 Flutter 上位机的日志窗格随窗口大小调整，最新记录置顶并显示记录时的本地时间；右键可清空日志，不影响设备连接。
- 修复 Flutter Web 选图后清理临时输入元素时使用了编译器不支持的 JS 互操作方法引用。

## 2026-11-21

- 修复 JavaFX 与 Flutter 连接取消状态机：连接中的断开现在会取消握手、等待清理完成并回到可重连的未连接状态；Java 连接入口把取消令牌传入协议会话，握手失败、超时和取消都会释放传输并保留错误日志。

- 修正实机连接时的协议失步。握手后设备仍约每 100 ms 广播 `\0MSNxx`。一次读取可以是
  6 字节命令回显再加已经进入缓冲的横幅前缀：7 字节是前缀 `00`，11 字节是前缀
  `00 4D 53 4E 30`。MINI 连接路径多发了只写的显示设置帧并采样按键，横幅多走出 4
  字节，所以比 GENERIC 多看到 4 字节。Java 与 Dart 会话层现在按 6 字节取命令帧，
  跳过完整横幅，并把横幅真前缀留到下一次读取；不是横幅的尾巴仍视为失步。
  `setXy` / `setSize` / `setColors` 不再为了等回执阻塞完整命令超时，因此不会在
  填充动作发出前断开。屏幕尺寸仍取 SFR 响应的 byte 5；以前把整段超长读当成失败，
  自描述中止后上位机才显示 0x0。

- JavaFX 上位机进程入口改为 `MoliUsbGuiMain`。`javafx-controls` 25.0.1 原本就在
  `java/moli-usb-gui` 的依赖里；JDK 不允许直接启动继承 `Application` 的主类，除非
  JavaFX 在模块路径上，否则报“缺少 JavaFX 运行时组件”。
- 工具链改为本机 JDK `C:\environments\jdk-25` 与 Flutter SDK `C:\environments\flutter`。
  JavaFX 上位机的 GraalVM 原生编译改为后续项。
- 开始落地 1.2 JavaFX 上位机 `java/moli-usb-gui` 与 2.2 Flutter 上位机
  `dart/moli_usb_gui`。一个窗口只控制一台设备；不做 Flash / 固件烧录。
- Flutter 传输封装选定 `flutter_libserialport` 0.6.0（Windows）和 `webserial` 1.2.0
  （Web）。放弃已停更的 `flutter_serial_port`、Dart 1 的 `serial_port`，以及 Web USB。
- 落地 1.2 与 2.2。一个窗口只控制一台设备；连接后锁定设备和型号，断开或拔出后解锁，界面不卡死。
  内容包括纯色、图片、静态/滚动文本、时钟和主机预览。系统状态与桌面镜像只在 Windows 提供，
  Flutter Web 禁用并说明原因。方向、背光、RGB 灯和 MINI 按键按设备能力显示。不做 Flash / 固件烧录。
- Java 协议库补充 `MoliDevice.setLed`、`applyLeds` 与 `probeLink`。`setLed` 只暂存，不立即提交。
- Dart 协议库补充 `drawPixelsRle`、`stageLed`、`applyLeds` 与 `probeLink`。原有 `setLed` 仍是暂存后立即提交。
- 系统状态：Java 使用 `com.github.oshi:oshi-core:7.8.0`；Flutter Windows 使用 `win32` 5.15.0，不另加原生插件。
- 本机没有 `C:\environments\dark-sdk-3.14.1`。协议库测试改用 Flutter 自带的 Dart SDK 跑过。

## 2026-11-20

- 完成路线图 0 号协议抽象分析：确定不使用 Protocol Buffers 承载设备线上协议或核心
  SDK 模型，改用规范驱动的跨语言抽象。
- 新增 MSU2 分层架构、6 字节线协议、显示/存储/ADC/RLE 命令、编码向量和实机验证
  清单。
- 开始并行落地 1.1 Java 协议库（`java/moli-usb-api`）与 2.1 纯 Dart 协议库
  （`dart/moli_usb_protocol`）；库按单仓库多子项目方式组织，不嵌套独立 git 仓库。
- 在 roadmap.md 与 AGENTS.md 登记本机工具链：JDK 25（GraalVM，Maven 依赖管理）、
  Dart SDK 3.14.1（`C:\environments\dark-sdk-3.14.1`，目标环境为 Windows 原生与 Web，
  暂不引入 Flutter）。
- 准备本地 git 仓库：分支由 `master` 改为 `main`，新增 `.gitignore` 与 `.gitattributes`，
  建立基线提交，仅包含 `spec/`、`docs/` 与根目录文档（65 个文件，约 11.58 MB）。
  `docs/` 下 10 个厂商安装包（`.exe`，约 217 MB）与 2 个资料压缩包（`.zip`，约 37 MB）
  暂不入库，文件保留在磁盘原位，必要时可改用 Git LFS 跟踪。本次未创建任何远程仓库。
- 确定包坐标命名空间统一使用 `space.firok`：Java 为 `groupId space.firok.moli.usb`
  与包名 `space.firok.moli.usb.api.*`；Dart 因 pub 包名不允许点号，使用
  `space_firok_moli_usb_protocol`。

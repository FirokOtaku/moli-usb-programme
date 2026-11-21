# 路线图

本文件记录当前要做的事。先判断协议能否抽出跨语言抽象，再并行完成 Java 与 Dart 两条线，最后用这些库做我们自己的工具。

## 工具链

本仓库统一使用以下本地工具链，各子项目构建与测试时遵循：

- Java：使用 **JDK 25**，本机 JDK 位于 `C:\environments\jdk-25`。依赖管理使用 **Maven**（系统内可直接使用 `mvn` 指令，无需访问内网仓库）。JavaFX 上位机的 GraalVM 原生编译不在当前工具链内，见 1.2。
- Dart：Dart SDK 3.14.1，位于 `C:\environments\dark-sdk-3.14.1`。协议库仍是纯 Dart，不依赖 Flutter。
- Flutter：本机 SDK 位于 `C:\environments\flutter`。只给 2.2 上位机使用，目标环境为 Windows 与 Web。
- Dart 协议库要求能编译运行在 Web 环境，也能直接编译运行在 Windows 或其它原生环境；当前只面向 **Windows 与 Web** 两个环境做测试。

包坐标统一归属命名空间 `space.firok`：Java 使用 Maven `groupId` `space.firok.moli.usb` 与包名 `space.firok.moli.usb.api.*`；Dart 因 pub 包名不允许点号，使用 `space_firok_moli_usb_protocol`。

## 复刻原则

对照厂商 Python 演示实现功能时，不要按源码一比一翻译。那些文件是演示，写法不一定合适，也有已经看到的缺陷。复刻时直接修正，并按目标语言重写结构。

至少不要原样带上这些问题：

- 扫描并打开所有串口，只凭明文 `MSN` 横幅认设备。
- 用裸文件名加载 `compaction.dll`，不锁定库文件位置。
- 烧录不检查 Flash 容量，也不区分外部素材和单片机固件。
- 压缩函数的输出缓冲没有长度参数。
- 串口读写缺少超时、取消和多设备隔离。

## 0. 协议抽象

分析这套 USB 屏幕协议是否适合用 Protocol Buffers 或同类库抽出抽象层。

如果适合，先把通用抽象层定下来，后续其它语言都基于它实现。抽象层的事实放在 `spec/`。各语言只实现自己的库，不共享源码或构建产物。

如果不适合，在本文件记下结论和原因，再按语言分别实现。协议事实仍然以 `spec/` 为准。

### 结论（已完成，2026-11-20）

可以提取跨语言抽象，但不采用 Protocol Buffers 作为设备线上协议，也不把
Protobuf 生成类作为 Java/Dart 协议库的核心 API。

原因如下：

- 设备固件已经固定使用 6 字节命令帧、连续帧批量发送、RGB565 像素流和厂商
  自定义 RLE 数据。设备不能解析 Protobuf，主机端最终仍要另写一次帧编解码和
  会话状态机。
- 线上帧没有长度、校验和请求 ID。Protobuf 不能解决串口分帧、响应归属、超时、
  取消、断线重连和多设备隔离这些真正需要统一的行为。
- 图像与 Flash 数据是高频、大块字节流。把它们包装成 Protobuf 只会增加分配、
  拷贝、代码生成和运行时依赖，不会提高兼容性。
- Java 与 Dart 库不会通过 RPC、文件或消息队列交换同一批消息，当前没有需要稳定
  序列化格式的跨进程边界。仅把高层对象写成 `.proto` 会同时维护语义模型和设备帧
  两套结构，并不能消除语言实现工作。

通用抽象改为规范驱动：`spec/architecture.md` 定义语义接口、能力模型、传输边界和
错误分类；`spec/msu2-wire-protocol.md` 定义设备真实接受的帧、字节序、握手、图像与
Flash 流程。Java 与 Dart 分别实现这些规范，不共享源码或构建产物。

将来只有出现独立进程间通信、可持久化配置/场景文件，或远程控制服务时，才在该
边界单独评估 Protobuf；它不能替代本设备的线协议编解码器。

## 1. Java

Java 与 Dart 可以并行。

### 1.1 协议库

用 Java 复刻 USB 屏幕协议库。

状态：进行中（2026-11-20 开始）。Maven 工程位于 `java/`，核心库模块为 `java/moli-usb-api`。

厂商目前有三种规格：

- MINI：最小，带一个按键。协议库必须支持这个按键的输入响应。
- LITE：无按键。
- PRO：无按键。

三者的屏幕尺寸和分辨率不同。库要支持这些现有规格，并为以后同类型、规格不同的设备留出扩展点。

一台电脑可能同时接入多块小屏幕。控制前必须先选中其中一块，可以用代码指定，或其它明确的选择方式，类似选择一台播放设备或摄像头。

### 1.2 上位机

用 JavaFX 和 1.1 的协议库实现上位机。模块为 `java/moli-usb-gui`。一个窗口只控制一台已选中的设备；设备列表在运行中刷新；拔出后界面不得卡死。内容包括图片、文本、时钟、系统状态和桌面镜像，并按能力提供方向、背光、RGB 灯和 MINI 按键。不做 Flash / 固件烧录。

状态：已落地（2026-11-21）。单测不依赖实机；本机尚未做插屏验收。

GraalVM 原生编译成 Windows 可执行文件改为后续项。当前 JDK 是 `C:\environments\jdk-25`，不是带 `native-image` 的 GraalVM；jSerialComm 与 JavaFX 的原生配置也不在这一版范围内。

## 2. Dart 与 Flutter

与第 1 步并行，不依赖 Java 的源码或构建产物。这条线用 Dart + Flutter，不再用 TypeScript。

开始落地前，先查找可复用的大型开源项目，用它们的封装完成设备发现、连接和数据读写等重复工作。不要一上来就自己调用 Web Serial、Web USB 或其它最底层接口拼读写流程。

查找结果按下面处理：

- 找到的项目覆盖当前需求，并且依赖范围可接受：直接使用它的封装，只在上面实现本项目的协议和界面。
- 找不到，或者项目对当前需求来说过重：不引入，由我们自己实现。
- 选定或放弃都要在本文件记下项目名称、地址和原因，避免后续重复查找。

### 2.1 协议库

协议库用纯 Dart 实现，不依赖 Flutter、`dart:ffi` 或浏览器对象。要求与 1.1 相同：支持 MINI、LITE、PRO，包括 MINI 的按键输入；能为新的同类型规格扩展；能在多块屏幕同时接入时先选中一块再控制。传输层通过接口注入，库本身不绑定某一种 USB 调用方式。

状态：进行中（2026-11-20 开始）。Dart 工程位于 `dart/moli_usb_protocol`。

### 2.2 上位机

上位机用 Flutter 实现，调用 2.1 的协议库。目录 `dart/moli_usb_gui`，包名 `space_firok_moli_usb_gui`。功能要求与 1.2 相同。系统状态和桌面镜像只在 Windows 提供；Web 上这两项禁用并说明原因。GraalVM 打包只属于 JavaFX 上位机，且已改为后续项。

状态：已落地（2026-11-21）。单测不依赖实机；本机尚未做插屏验收。

传输封装已选定，不再重查：

- Windows：[`flutter_libserialport` 0.6.0](https://pub.dev/packages/flutter_libserialport)。它负责枚举、打开和读写，并用 Flutter 构建系统编译 libserialport。放弃已停更的 `flutter_serial_port` 和只支持 Dart 1 的 `serial_port`。若它设不了 RTS/CTS，再退到 `serial_port_win32`，不要一开始就手写 Win32。
- Web：[`webserial` 1.2.0](https://pub.dev/packages/webserial)。`package:web` 没有尚未进入 W3C 标准轨道的 Web Serial。不用手写 `dart:js_interop`，也不用 Web USB：Windows 串口驱动通常已经占用该接口。

Flutter Web 可以编译到浏览器，并用 `dart:js_interop` 调用浏览器接口，因此可以替代原来的 TypeScript Web 程序。浏览器侧按下面的约束接设备：

- 厂商演示走的是 USB 串口，不是浏览器直接占用的裸 USB 接口。Windows 上系统串口驱动通常已经占用该接口，Web USB 的 `claimInterface` 会失败。
- 浏览器上位机优先使用 Web Serial。只有确认设备提供了未被系统驱动占用的厂商接口时，才使用 Web USB。
- 两种接口都只在 Chrome、Edge 等 Chromium 浏览器中可用，页面必须是 HTTPS 或 localhost，并且要由用户操作触发设备选择。Firefox 和 Safari 不支持。

纯 Dart 协议库和 Flutter 上位机放在独立的 Dart 工程中。不放进 Java reactor，也不做成 npm 工程。目录在第一次添加时按 `AGENTS.md` 创建。

## 3. 自有工具

第 1 步和第 2 步都完成之后，再用前面的库开发完全由我们自己设计的工具，去驱动这些 USB 小屏幕。

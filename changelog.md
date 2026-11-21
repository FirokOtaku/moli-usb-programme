# 变更记录

## 2026-11-21

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

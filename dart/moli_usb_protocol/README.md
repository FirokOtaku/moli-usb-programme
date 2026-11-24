# space_firok_moli_usb_protocol

纯 Dart 实现的 MSU2 系列 USB 屏幕协议库（roadmap 2.1）。

- 不依赖 Flutter、`dart:ffi` 或任何浏览器对象；`lib/` 不 import `dart:io`、
  `dart:html`、`dart:js_interop`、`dart:js_util`。
- 传输层通过抽象接口注入，库本身不绑定 Web Serial / Web USB / 原生串口。
- 可在 Dart VM（Windows，`dart compile exe`）与 Web（`dart compile js`）编译运行。
- 协议真源是仓库根的 `spec/msu2-wire-protocol.md` 与 `spec/architecture.md`。

## 分层

四层单向依赖，目录与层一一对应：

```
lib/src/domain/      领域 API（枚举、选择、显示、存储、输入事件）
        │
lib/src/session/     会话层（握手、请求串行化、超时、取消、断线）
        │
lib/src/model/       共享不可变值模型（DeviceId / Profile / Capabilities / Info）
        │
lib/src/codec/       编解码层（6 字节帧、SFR、RGB565、RLE、Flash 分页）
        │
lib/src/transport/   传输层抽象（字节读写、清空输入、关闭）
```

依赖只能自上而下。`model/` 是叶子节点：`codec/` 与 `transport/` 都不 import 它，
因此不存在环。领域 API 不暴露串口对象，编解码层不自行枚举设备。

## 快速开始

```dart
import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';

// 宿主提供传输实现：Web 用 Web Serial 适配器，Windows 用原生串口适配器。
final manager = MoliDeviceManager(providers: [myProvider]);

final candidates = await manager.discover();          // 不打开端口
final session = await manager.open(candidates.first.id); // 明确选中一块
final device = MoliDeviceApi(session);

await device.fillRect(const Rect(0, 0, 160, 80), Rgb565Colors.green);
await device.setOrientation(0);
await device.setBacklight(500);                        // 不支持时报错
await device.drawFrame(pixels);                        // 整帧 RGB565

device.inputEvents.listen((event) {                    // MINI 按键
  print('${event.keyIndex} ${event.state}');
});

await device.close();
```

`example/main.dart` 只走纯编码流程（不打开任何传输），用于：

```
dart run example/main.dart
dart compile js  example/main.dart -o build/example.js
dart compile exe example/main.dart -o build/example.exe
```

## 写入宿主图像

`RgbaImage` 接收已解码的 sRGB RGBA8888 字节（行优先、非预乘 alpha），不依赖
Flutter 或图片解码库。Flutter 调用方可用 `ui.ImageByteFormat.rawStraightRgba`
取得所需数据。单个颜色可用 `Rgb565Color.fromRgb(0xRRGGBB)` 或
`Rgb565Color.fromArgb(0xAARRGGBB)` 转换，后者忽略 alpha。

```dart
final image = RgbaImage(width, height, rgbaBytes);
const options = ImageWriteOptions(
  alphaMode: ImageAlphaMode.composite,
  alphaBackground: Rgb565Colors.black,
  scaleMode: ImageScaleMode.fit,
  compression: ImageCompression.profileCompact,
);
await device.writeImage(image, options: options); // 默认适配整屏，可传 rect

final queue = device.createImageWriteQueue(
  capacity: 1,
  overflowPolicy: ImageOverflowPolicy.dropOldest,
  options: options,
);
final result = await queue.submit(image); // sent 或 dropped；发送失败则抛错
await queue.close();
```

缩放可选等比留边 `fit`、等比居中裁切 `fill` 或拉伸 `stretch`；传输编码独立选择
`none`、`rle` 或 `profileCompact`（MINI 用暂存区紧凑编码，其余用 RLE）。目标尺寸
不一致时，每个会话首次缩放会输出一条 `WARNING`。可通过
`device.setImageResolutionWarningEnabled(false)` 或 Dart 编译环境参数
`-Dspace.firok.moli.usb.warnImageResize=false` 关闭。

队列容量只计待发帧，不计正在发送的帧。队列满时可等待空位（`block`）、丢新帧
（`dropNewest`），或替换最早的待发帧（`dropOldest`）；不会中断当前传输。
选择 `block` 时，生产者应等待 `submit` 返回的 Future，避免在调用方积压。
关闭队列或设备会先等已接受的帧处理完成。

## 已实现 / 未实现

已实现（纯编码与状态机均已测试）：

- 握手 `\0MSNxx` / `\0MSNCN`，含横幅扫描与超时、非 MSU2 横幅分类。
- 6 字节帧、SFR u8 读写与 u16 读、显示区域/颜色/动作、暂存区 64 帧、
  像素流（`07` → `04` → `08` → `09`）、RLE `09`、Flash `03 xx`、ADC `08`。
- 会话串行化、回显校验、超时、取消、断线；MINI 按键 ADC 采样适配。

未实现（需要实机抓包，见 `spec/verification.md`）：

- 真实传输实现（Web Serial / Web USB / Windows 串口）由上位机提供，本库只定义
  接口。仓库内只有测试用的 `test/fake_transport.dart`。
- `MSN_UID`、`Lcd_X/Y`、`Flash_Info` 的实际宽度与地址未确认，自描述解析只在
  `SessionOptions.readSelfDescription = true` 时启用，且失败可回退到 profile。
- SFR u16 写（操作码 `0xA0` vs `0x20` 冲突）未公开；`03 04` 按 profile 路由。
- MINI/LITE/PRO 的具体分辨率与 MINI 按键方向仍是 P0 待确认项。

## 测试

```
dart test
```

`test/codec_vectors_test.dart` 逐条实现 `spec/msu2-wire-protocol.md`
「规范测试向量」的 10 条断言；`test/session_test.dart` 用假传输覆盖握手成功/失败、
超时、取消、断线、串行化、能力守卫与设备选择。

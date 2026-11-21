// Model / profile / input behaviour.
//
// Covers the roadmap 2.1 requirements that are not pure encoding:
//   - MINI / LITE / PRO are known profiles but not a closed set,
//   - serial profiles: legacy 19200 without flow control, LITE V1.2 / PRO V1.0
//     at 921600 with RTS/CTS, 8N1,
//   - the MINI key reaches the domain layer as typed input events.

import 'dart:async';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

import 'fake_transport.dart';

void main() {
  group('串口 profile', () {
    test('旧版为 19200、无流控、8N1', () {
      final legacy = DeviceProfile.legacySerial;
      expect(legacy.baudRate, 19200);
      expect(legacy.rtscts, isFalse);
      expect(legacy.xonxoff, isFalse);
      expect(legacy.dataBits, 8);
      expect(legacy.stopBits, SerialStopBits.one);
      expect(legacy.parity, SerialParity.none);
    });

    test('LITE V1.2 / PRO V1.0 为 921600 + rtscts + 8N1', () {
      final high = DeviceProfile.highSpeedSerial;
      expect(high.baudRate, 921600);
      expect(high.rtscts, isTrue);
      expect(high.xonxoff, isFalse);
      expect(high.dataBits, 8);
      expect(high.stopBits, SerialStopBits.one);
      expect(high.parity, SerialParity.none);

      expect(DeviceProfile.lite.serial.baudRate, 921600);
      expect(DeviceProfile.lite.serial.rtscts, isTrue);
      expect(DeviceProfile.pro.serial.baudRate, 921600);
      expect(DeviceProfile.pro.serial.rtscts, isTrue);
    });

    test('MINI 默认旧版串口参数', () {
      expect(DeviceProfile.mini.serial.baudRate, 19200);
      expect(DeviceProfile.mini.serial.rtscts, isFalse);
    });
  });

  group('profile 不是封闭枚举', () {
    test('已知 profile 按名称查找', () {
      expect(DeviceProfile.byName('MINI')!.name, 'MINI');
      expect(DeviceProfile.byName('lite')!.name, 'LITE');
      expect(DeviceProfile.byName('pro')!.name, 'PRO');
      expect(DeviceProfile.byName('nope'), isNull);
    });

    test('未知型号回退到 GENERIC', () {
      expect(DeviceProfile.byNameOrDefault('MSU3').name, 'GENERIC');
      expect(DeviceProfile.byNameOrDefault(null).name, 'GENERIC');
    });

    test('可以注册自定义 profile', () {
      final custom = DeviceProfile.custom(
        name: 'MSU3-WIDE',
        serial: const SerialSettings.eightN1(baudRate: 921600, rtscts: true),
        display: const DisplayGeometry(width: 320, height: 240),
        capabilities: const DeviceCapabilities(keyInput: true),
        keyAdcChannel: 9,
      );
      expect(custom.name, 'MSU3-WIDE');
      expect(custom.display.width, 320);
      expect(custom.capabilities.keyInput, isTrue);
      expect(DeviceProfile.byName('MSU3-WIDE'), isNull,
          reason: 'custom profiles are not registered globally');
    });

    test('能力按型号区分而不是按枚举', () {
      expect(DeviceProfile.mini.capabilities.keyInput, isTrue);
      expect(DeviceProfile.lite.capabilities.keyInput, isFalse);
      expect(DeviceProfile.pro.capabilities.backlight, isTrue);
      expect(DeviceProfile.lite.capabilities.backlight, isFalse);
      expect(DeviceProfile.pro.capabilities.nand, isTrue);
      expect(DeviceProfile.lite.capabilities.nand, isFalse);
      // u16 SFR write stays off until hardware confirms opcode 0xA0.
      expect(DeviceProfile.pro.capabilities.sfrU16Write, isFalse);
    });

    test('PRO 使用 24 位页地址编码，其余用 16 位', () {
      expect(DeviceProfile.pro.colorPageEncoding,
          FlashColorPageEncoding.proMidLowHigh);
      expect(DeviceProfile.lite.colorPageEncoding,
          FlashColorPageEncoding.liteU16);
      const display = DisplayCodec();
      // PRO: middle, low, high of the 24-bit page — NOT u24_be.
      expect(
        toHex(display.showFlashColor(0x123456,
                FlashColorPageEncoding.proMidLowHigh)
            .bytes),
        '020300345612',
      );
      // LITE: 16-bit page, last byte zeroed.
      expect(
        toHex(display.showFlashColor(0x1234, FlashColorPageEncoding.liteU16)
            .bytes),
        '020300123400',
      );
    });

    test('P25D80 几何：1 MiB / 256 B 页 / 4096 页', () {
      final geometry = DeviceProfile.p25d80Geometry;
      expect(geometry.capacityBytes, 1024 * 1024);
      expect(geometry.pageSizeBytes, 256);
      expect(geometry.pageCount, 4096);
      expect(geometry.eraseGranularityBytes, 256);
    });
  });

  group('MINI 按键输入', () {
    test('ADC 空闲后下降到阈值以下产生 pressed，回升产生 released', () async {
      final port = FakeMsu2Port(
        descriptor: const TransportPortDescriptor(
          transportKey: 'mini-1',
          displayName: 'Fake MINI',
          kind: 'fake',
        ),
      )
        ..responseByte4 = 0x03
        ..responseByte5 = 0x20; // 800 = idle

      final session = MoliSession(
        port: port,
        profile: DeviceProfile.mini,
        options: const SessionOptions(
          inputPollInterval: Duration(milliseconds: 5),
          keyDebounce: Duration(milliseconds: 1),
        ),
      );
      await session.connect();
      addTearDown(session.disconnect);

      final events = <InputEvent>[];
      final sub = session.inputEvents.listen(events.add);
      addTearDown(sub.cancel);

      // Let the detector measure the idle level from two samples.
      await Future<void>.delayed(const Duration(milliseconds: 40));

      port
        ..responseByte4 = 0x00
        ..responseByte5 = 0x64; // 100, well below 800 - 125
      await Future<void>.delayed(const Duration(milliseconds: 40));

      port
        ..responseByte4 = 0x03
        ..responseByte5 = 0x20;
      await Future<void>.delayed(const Duration(milliseconds: 40));

      expect(events, isNotEmpty);
      expect(events.first.state, InputState.pressed);
      expect(events.first.keyIndex, 0);
      expect(events.first.source, InputSource.adcKey);
      expect(events.map((e) => e.state), contains(InputState.released));
    });

    test('LITE 没有按键能力时 inputEvents 保持静默', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(port: port, profile: DeviceProfile.lite);
      await session.connect();
      addTearDown(session.disconnect);

      final events = <InputEvent>[];
      final sub = session.inputEvents.listen(events.add);
      addTearDown(sub.cancel);
      await Future<void>.delayed(const Duration(milliseconds: 40));
      expect(events, isEmpty);
    });

    test('未知型号声明按键能力后同样可用', () async {
      final port = FakeMsu2Port()
        ..responseByte4 = 0x03
        ..responseByte5 = 0x20;
      final profile = DeviceProfile.custom(
        name: 'MSU3-MINI',
        serial: const SerialSettings.eightN1(baudRate: 19200),
        display: const DisplayGeometry(width: 64, height: 64),
        capabilities: const DeviceCapabilities(keyInput: true),
        keyAdcChannel: 9,
      );
      final session = MoliSession(
        port: port,
        profile: profile,
        options: const SessionOptions(
          inputPollInterval: Duration(milliseconds: 5),
          keyDebounce: Duration(milliseconds: 1),
        ),
      );
      await session.connect();
      addTearDown(session.disconnect);

      final events = <InputEvent>[];
      final sub = session.inputEvents.listen(events.add);
      addTearDown(sub.cancel);
      await Future<void>.delayed(const Duration(milliseconds: 40));
      port
        ..responseByte4 = 0x00
        ..responseByte5 = 0x64;
      await Future<void>.delayed(const Duration(milliseconds: 40));
      expect(events.first.state, InputState.pressed);
    });
  });

  group('AdcKeyDetector', () {
    test('低于阈值为按下，去抖窗口内的抖动被忽略', () {
      final detector = AdcKeyDetector(
        channel: 9,
        pressThreshold: 500,
        debounce: const Duration(milliseconds: 50),
        idleLevel: 600,
      );
      final t0 = DateTime(2026, 1, 1, 12);
      expect(detector.feed(600, now: t0), isNull);
      expect(
        detector.feed(200, now: t0.add(const Duration(milliseconds: 10))),
        KeyState.pressed,
      );
      // Bounce right after the change: ignored.
      expect(
        detector.feed(600, now: t0.add(const Duration(milliseconds: 20))),
        isNull,
      );
      expect(detector.isPressed, isTrue);
      // After the debounce window the release is reported.
      expect(
        detector.feed(600, now: t0.add(const Duration(milliseconds: 200))),
        KeyState.released,
      );
    });
  });

  group('DeviceId', () {
    test('传输派生 id 标记为临时', () {
      final id = DeviceId.transport('COM7');
      expect(id.isProvisional, isTrue);
      expect(id.value, 'transport:COM7');
      expect(const DeviceId('uid:0A').isProvisional, isFalse);
    });

    test('相同值相等且哈希一致', () {
      expect(const DeviceId('uid:0A'), const DeviceId('uid:0A'));
      expect(const DeviceId('uid:0A').hashCode, const DeviceId('uid:0A').hashCode);
      expect(const DeviceId('uid:0A'), isNot(const DeviceId('uid:0B')));
    });
  });

  group('错误分类', () {
    test('全部必需错误类型都有稳定标签', () {
      const required = {
        MoliErrorKind.deviceNotFound: 'DeviceNotFound',
        MoliErrorKind.deviceBusy: 'DeviceBusy',
        MoliErrorKind.disconnected: 'Disconnected',
        MoliErrorKind.handshakeFailed: 'HandshakeFailed',
        MoliErrorKind.protocolMismatch: 'ProtocolMismatch',
        MoliErrorKind.protocolDesynchronized: 'ProtocolDesynchronized',
        MoliErrorKind.timeout: 'Timeout',
        MoliErrorKind.cancelled: 'Cancelled',
        MoliErrorKind.unsupportedCapability: 'UnsupportedCapability',
        MoliErrorKind.invalidArgument: 'InvalidArgument',
        MoliErrorKind.outOfBounds: 'OutOfBounds',
        MoliErrorKind.storageCapacityExceeded: 'StorageCapacityExceeded',
        MoliErrorKind.storageOperationFailed: 'StorageOperationFailed',
        MoliErrorKind.malformedResponse: 'MalformedResponse',
      };
      for (final entry in required.entries) {
        expect(entry.key.label, entry.value);
      }
    });

    test('withContext 保留已有上下文', () {
      const original = MoliException(
        MoliErrorKind.timeout,
        'slow',
        operation: 'readSfrU8',
        deviceId: 'uid:0A',
      );
      final enriched = original.withContext(operation: 'request');
      expect(enriched.operation, 'readSfrU8');
      expect(enriched.deviceId, 'uid:0A');
      expect(enriched.kind, MoliErrorKind.timeout);
    });

    test('错误字符串包含分类、操作与设备', () {
      const error = MoliException(
        MoliErrorKind.outOfBounds,
        'too big',
        operation: 'fillRect',
        deviceId: 'uid:0A',
      );
      final text = error.toString();
      expect(text, contains('OutOfBounds'));
      expect(text, contains('fillRect'));
      expect(text, contains('uid:0A'));
    });
  });
}
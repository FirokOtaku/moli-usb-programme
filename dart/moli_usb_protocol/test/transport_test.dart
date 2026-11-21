// Transport-layer helpers: `readExact` deadline behaviour and the byte
// accumulator. These are the pieces a real Web Serial / native serial adapter
// relies on, so they are tested independently of the session.

import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

import 'fake_transport.dart';

void main() {
  group('readExact', () {
    test('凑齐指定字节数后返回', () async {
      final port = FakeMsu2Port(banner: const [1, 2, 3, 4, 5, 6, 7, 8]);
      final bytes = await port.readExact(
        8,
        overallTimeout: const Duration(seconds: 1),
      );
      expect(bytes, hasLength(8));
      expect(bytes, [1, 2, 3, 4, 5, 6, 7, 8]);
    });

    test('字节不足时按总超时抛 Timeout', () async {
      final port = FakeMsu2Port(banner: const [1, 2]);
      await expectLater(
        port.readExact(6, overallTimeout: const Duration(milliseconds: 80)),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.timeout)),
      );
    });

    test('count 为 0 立即返回空列表', () async {
      final port = FakeMsu2Port(banner: null);
      expect(await port.readExact(0), isEmpty);
    });

    test('负数 count 报 InvalidArgument', () async {
      final port = FakeMsu2Port(banner: null);
      await expectLater(
        port.readExact(-1),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.invalidArgument)),
      );
    });

    test('取消 token 中止长读取', () async {
      final port = FakeMsu2Port(banner: null);
      final token = CancelToken();
      final future = port.readExact(
        6,
        overallTimeout: const Duration(seconds: 5),
        token: token,
      );
      await Future<void>.delayed(const Duration(milliseconds: 20));
      token.cancel('stop');
      await expectLater(
        future,
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.cancelled)),
      );
    });

    test('断线时传播 Disconnected 而不是 Timeout', () async {
      final port = FakeMsu2Port(banner: null)..unplug();
      await expectLater(
        port.readExact(6, overallTimeout: const Duration(milliseconds: 100)),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.disconnected)),
      );
    });
  });

  group('ByteAccumulator', () {
    test('拼接多段并保留长度', () {
      final buffer = ByteAccumulator()
        ..add(Uint8List.fromList([1, 2]))
        ..add(const [3]);
      expect(buffer.length, 3);
      expect(buffer.isNotEmpty, isTrue);
      expect(buffer.toBytes(), [1, 2, 3]);
    });

    test('clear 后为空', () {
      final buffer = ByteAccumulator()..add(const [1]);
      buffer.clear();
      expect(buffer.isEmpty, isTrue);
      expect(buffer.toBytes(), isEmpty);
    });

    test('接收普通 List 时会拷贝', () {
      final source = [1, 2, 3];
      final buffer = ByteAccumulator()..add(source);
      source[0] = 9;
      expect(buffer.toBytes(), [1, 2, 3]);
    });
  });

  group('SerialSettings', () {
    test('8N1 快捷构造与默认值一致', () {
      const explicit = SerialSettings(baudRate: 19200);
      const shortcut = SerialSettings.eightN1(baudRate: 19200);
      expect(shortcut, explicit);
      expect(shortcut.hashCode, explicit.hashCode);
    });

    test('流控差异影响相等性', () {
      const noFlow = SerialSettings.eightN1(baudRate: 921600);
      const rtscts = SerialSettings.eightN1(baudRate: 921600, rtscts: true);
      expect(noFlow, isNot(rtscts));
    });

    test('toString 显示 8N1 与流控', () {
      final text = DeviceProfile.highSpeedSerial.toString();
      expect(text, contains('921600'));
      expect(text, contains('8N1'));
      expect(text, contains('rtscts=true'));
    });
  });
}
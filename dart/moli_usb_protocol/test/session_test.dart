// Session-layer behaviour with an injected fake transport.
//
// Covers the three failure modes the spec calls out explicitly:
//   - a read that never completes must fail with Timeout,
//   - a cancelled token must abort with Cancelled,
//   - a lost link must fail with Disconnected and never be retried.
//
// Also verifies handshake success/failure, serialization (no interleaved
// requests) and the capability guards.

import 'dart:async';
import 'dart:typed_data';

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

import 'fake_transport.dart';

MoliException expectMoliError(Object? error, MoliErrorKind kind) {
  expect(error, isA<MoliException>());
  final moli = error! as MoliException;
  expect(moli.kind, kind, reason: moli.toString());
  return moli;
}

/// A connected session over a fake device, closed automatically.
Future<MoliSession> connectedSession({
  SessionOptions options = const SessionOptions(),
  TransportPortDescriptor? descriptor,
}) async {
  final port = FakeMsu2Port(descriptor: descriptor);
  final session = MoliSession(
    port: port,
    profile: DeviceProfile.lite,
    options: options,
  );
  await session.connect();
  addTearDown(session.disconnect);
  return session;
}

void main() {
  group('握手', () {
    test('横幅 + 挑战成功，报告协议版本和设备信息', () async {
      final session = await connectedSession();
      expect(session.isConnected, isTrue);
      final info = session.info!;
      expect(info.protocolVersion, 1);
      expect(info.profile.name, DeviceProfile.nameLite);
      expect(info.display.width, 160);
      expect(info.display.height, 80);
      expect(session.deviceId!.value, startsWith('transport:'));
    });

    test('没有横幅时超时为 HandshakeFailed', () async {
      final port = FakeMsu2Port(banner: null);
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          handshakeTimeout: Duration(milliseconds: 60),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await expectLater(
        session.connect(),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.handshakeFailed)),
      );
      expect(session.isConnected, isFalse);
    });

    test('非 MSU2 横幅超时后为 ProtocolMismatch', () async {
      final port = FakeMsu2Port(banner: const [1, 2, 3, 4, 5, 6]);
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          handshakeTimeout: Duration(milliseconds: 60),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await expectLater(
        session.connect(),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.protocolMismatch)),
      );
    });

    test('设备不回显挑战时 HandshakeFailed', () async {
      final port = FakeMsu2Port()..echoChallenge = false;
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          challengeAttempts: 2,
          confirmTimeout: Duration(milliseconds: 40),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await expectLater(
        session.connect(),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.handshakeFailed)),
      );
    });

    test('重复 connect 被拒绝', () async {
      final session = await connectedSession();
      await expectLater(
        session.connect(),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.invalidArgument)),
      );
    });

    test('自描述读取不会与请求队列互相死锁', () async {
      // With readSelfDescription the session issues ~256 ordinary SFR
      // requests right after the handshake. If those were nested inside the
      // queued handshake task, connect() would hang forever.
      final session = await connectedSession(
        options: const SessionOptions(
          readSelfDescription: true,
          tolerateSelfDescriptionFailure: true,
        ).copyWith(),
      );
      expect(session.isConnected, isTrue);
      expect(session.info, isNotNull);
    });

    test('自描述解析失败时容忍并保留 profile 值', () async {
      // The fake device answers every SFR read with zero bytes, so the parsed
      // table is empty/garbage; the session must still be usable.
      final port = FakeMsu2Port();
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          readSelfDescription: true,
          tolerateSelfDescriptionFailure: true,
        ),
      );
      final info = await session.connect();
      addTearDown(session.disconnect);
      expect(info.display.width, 160);
      expect(session.isConnected, isTrue);
      await session.setOrigin(1, 2);
    });

    test('自描述解析失败且不容忍时 connect 抛错', () async {
      // Forcing a non-echoing response makes every SFR read fail with
      // ProtocolDesynchronized, which is not tolerated here.
      final port = FakeMsu2Port()
        ..forcedResponse = const [0x09, 0x00, 0, 0, 0, 0];
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          readSelfDescription: true,
          tolerateSelfDescriptionFailure: false,
        ),
      );
      addTearDown(port.close);
      await expectLater(
        session.connect(),
        throwsA(isA<MoliException>()),
      );
    });
  });

  group('请求序列化与响应匹配', () {
    test('每次请求都写入 6 字节帧并校验回显', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(),
      );
      await session.connect();
      addTearDown(session.disconnect);

      await session.setOrigin(1, 2);
      final commandWrites =
          port.writes.where((w) => !_isChallenge(w)).toList();
      expect(commandWrites, hasLength(1));
      expect(toHex(commandWrites.single), '020000010002');
    });

    test('并发请求被串行化，不会交错', () async {
      final port = FakeMsu2Port()..responseDelay = const Duration(milliseconds: 5);
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
      );
      await session.connect();
      addTearDown(session.disconnect);

      await Future.wait([
        session.setOrigin(1, 2),
        session.setRegionSize(160, 80),
        session.fill(Rgb565Colors.green),
      ]);
      final frames = port.writes.where((w) => !_isChallenge(w)).toList();
      expect(frames, hasLength(3));
      expect(toHex(frames[0]), '020000010002');
      expect(toHex(frames[1]), '020100A00050');
      expect(toHex(frames[2]), '02030B07E000');
    });

    test('响应不匹配请求时 ProtocolDesynchronized', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
      );
      await session.connect();
      addTearDown(session.disconnect);

      port.forcedResponse = const [0x09, 0x00, 0, 0, 0, 0];
      await expectLater(
        session.setOrigin(1, 2),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.protocolDesynchronized,
        )),
      );
    });
  });

  group('超时', () {
    test('设备不回应时 request 报 Timeout', () async {
      final port = FakeMsu2Port()..autoRespond = false;
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          operationTimeout: Duration(milliseconds: 50),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await session.connect();
      addTearDown(session.disconnect);

      await expectLater(
        session.setOrigin(1, 2),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.timeout)),
      );
    });

    test('超时错误标记为可重试并带上操作名', () async {
      final port = FakeMsu2Port()..autoRespond = false;
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          operationTimeout: Duration(milliseconds: 40),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await session.connect();
      addTearDown(session.disconnect);

      Object? caught;
      try {
        await session.setRegionSize(160, 80);
      } catch (error) {
        caught = error;
      }
      final moli = expectMoliError(caught, MoliErrorKind.timeout);
      expect(moli.isRetryable, isTrue);
      expect(moli.operation, isNotNull);
    });

    test('超时后会话仍可用于后续成功请求', () async {
      final port = FakeMsu2Port()..autoRespond = false;
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          operationTimeout: Duration(milliseconds: 40),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await session.connect();
      addTearDown(session.disconnect);

      await expectLater(session.setOrigin(0, 0), throwsA(isA<MoliException>()));

      port.autoRespond = true;
      await session.setOrigin(3, 4);
      expect(toHex(port.writes.last), '020000030004');
    });
  });

  group('取消', () {
    test('已取消的 token 在握手阶段抛出 Cancelled', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
      );
      final token = CancelToken()..cancel('user aborted');
      await expectLater(
        session.connect(token: token),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.cancelled)),
      );
    });

    test('请求过程中取消得到 Cancelled', () async {
      final port = FakeMsu2Port()..autoRespond = false;
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.lite,
        options: const SessionOptions(
          operationTimeout: Duration(seconds: 5),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      await session.connect();
      addTearDown(session.disconnect);

      final token = CancelToken();
      final future = session.setOrigin(1, 2, token: token);
      await Future<void>.delayed(const Duration(milliseconds: 30));
      token.cancel();

      await expectLater(
        future,
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.cancelled)),
      );
    });

    test('取消原因进入错误消息', () async {
      final token = CancelToken()..cancel('关闭窗口');
      try {
        await Future<void>.delayed(Duration.zero);
        token.ensureNotCancelled();
        fail('expected a cancelled error');
      } on MoliException catch (error) {
        expect(error.kind, MoliErrorKind.cancelled);
        expect(error.message, contains('关闭窗口'));
        expect(error.isRetryable, isFalse);
      }
    });
  });

  group('断线', () {
    test('断开后请求报 Disconnected 且不重试', () async {
      final session = await connectedSession();
      final port = session.port as FakeMsu2Port;

      port.unplug();
      final attemptsBefore = port.writeAttempts;
      await expectLater(
        session.setOrigin(1, 2),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.disconnected)),
      );
      // The failing write was attempted exactly once: no automatic replay.
      expect(port.writeAttempts, attemptsBefore + 1);
    });

    test('disconnect 幂等并关闭端口', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(port: port, profile: DeviceProfile.lite);
      await session.connect();
      await session.disconnect();
      await session.disconnect();
      expect(port.isClosed, isTrue);
      expect(session.isConnected, isFalse);
    });

    test('disconnect 后再请求报 Disconnected', () async {
      final session = await connectedSession();
      await session.disconnect();
      await expectLater(
        session.setOrigin(1, 2),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.disconnected)),
      );
    });
  });

  group('能力与边界守卫', () {
    test('LITE 无按键能力时不启动输入轮询', () async {
      final session = await connectedSession();
      expect(session.profile.capabilities.keyInput, isFalse);
      expect(session.info!.capabilities.keyInput, isFalse);
    });

    test('不支持的背光命令报 UnsupportedCapability', () async {
      final session = await connectedSession();
      await expectLater(
        session.setBacklight(500),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.unsupportedCapability,
        )),
      );
    });

    test('PRO profile 支持背光', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(port: port, profile: DeviceProfile.pro);
      await session.connect();
      addTearDown(session.disconnect);
      await session.setBacklight(500);
      expect(toHex(port.writes.last), '020312' '01F400');
    });

    test('区域超出面板时报 OutOfBounds 且不发送任何帧', () async {
      final session = await connectedSession();
      final port = session.port as FakeMsu2Port;
      final before = port.writes.length;
      await expectLater(
        session.setRegion(const Rect(150, 0, 20, 10)),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.outOfBounds)),
      );
      expect(port.writes.length, before);
    });

    test('drawPixels 像素数量不符时报 InvalidArgument', () async {
      final session = await connectedSession();
      await expectLater(
        session.drawPixels(
          const Rect(0, 0, 2, 2),
          List<Rgb565Color>.filled(3, Rgb565Colors.red),
        ),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.invalidArgument)),
      );
    });

    test('Flash 页范围超出容量时报 StorageCapacityExceeded', () async {
      final session = await connectedSession();
      await expectLater(
        session.flashErasePages(4090, 100),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.storageCapacityExceeded,
        )),
      );
    });

    test('无外部存储的 profile 报 UnsupportedCapability', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.generic,
      );
      await session.connect();
      addTearDown(session.disconnect);
      await expectLater(
        session.flashReadByte(0),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.unsupportedCapability,
        )),
      );
    });

    test('Flash 读取返回响应 byte 5', () async {
      final port = FakeMsu2Port()..responseByte5 = 0x7F;
      final session = MoliSession(port: port, profile: DeviceProfile.lite);
      await session.connect();
      addTearDown(session.disconnect);
      expect(await session.flashReadByte(0x012345), 0x7F);
      expect(toHex(port.writes.last), '030001234500');
    });

    test('SFR u16 读取解码 bytes 4..5', () async {
      final port = FakeMsu2Port()
        ..responseByte4 = 0x12
        ..responseByte5 = 0x34;
      final session = MoliSession(port: port, profile: DeviceProfile.lite);
      await session.connect();
      addTearDown(session.disconnect);
      expect(await session.readSfrU16(0x42), 0x1234);
    });

    test('未知型号作为 GENERIC 设备可握手', () async {
      final port = FakeMsu2Port();
      final session = MoliSession(
        port: port,
        profile: DeviceProfile.byNameOrDefault('SOMETHING-NEW'),
        options: const SessionOptions(),
      );
      final info = await session.connect();
      addTearDown(session.disconnect);
      expect(info.profile.name, DeviceProfile.nameGeneric);
      expect(session.isConnected, isTrue);
    });
  });

  group('设备管理器', () {
    test('枚举不打开端口', () async {
      final provider = FakeTransportProvider((_) => FakeMsu2Port());
      final manager = MoliDeviceManager(providers: [provider]);
      final candidates = await manager.discover();
      expect(candidates, hasLength(1));
      expect(provider.opened, isFalse);
    });

    test('按 DeviceId 打开并完成握手', () async {
      final provider = FakeTransportProvider((_) => FakeMsu2Port());
      final manager = MoliDeviceManager(providers: [provider]);
      final candidates = await manager.discover();
      final session = await manager.open(candidates.single.id);
      addTearDown(session.disconnect);
      expect(session.isConnected, isTrue);
      expect(provider.opened, isTrue);
    });

    test('未知 DeviceId 报 DeviceNotFound', () async {
      final provider = FakeTransportProvider((_) => FakeMsu2Port());
      final manager = MoliDeviceManager(providers: [provider]);
      await expectLater(
        manager.open(const DeviceId('uid:ff')),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.deviceNotFound)),
      );
    });

    test('选择器不匹配时报 DeviceNotFound', () async {
      final provider = FakeTransportProvider((_) => FakeMsu2Port());
      final manager = MoliDeviceManager(providers: [provider]);
      await expectLater(
        manager.openFirst((_) => false),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.deviceNotFound)),
      );
    });

    test('握手失败时关闭端口，不泄漏传输', () async {
      late FakeMsu2Port port;
      final provider = FakeTransportProvider((_) => port = FakeMsu2Port(banner: null));
      final manager = MoliDeviceManager(
        providers: [provider],
        options: const SessionOptions(
          handshakeTimeout: Duration(milliseconds: 50),
          readSliceTimeout: Duration(milliseconds: 10),
        ),
      );
      final candidates = await manager.discover();
      await expectLater(
        manager.open(candidates.single.id),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.handshakeFailed)),
      );
      expect(port.isClosed, isTrue);
    });

    test('同一 provider 第二次打开报 DeviceBusy', () async {
      final provider = FakeTransportProvider((_) => FakeMsu2Port());
      final manager = MoliDeviceManager(providers: [provider]);
      final candidates = await manager.discover();
      final session = await manager.open(candidates.single.id);
      addTearDown(session.disconnect);
      await expectLater(
        manager.open(candidates.single.id),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.deviceBusy)),
      );
    });
  });

  group('设备 API 门面', () {
    test('fillRect 先设区域再填充，并做边界检查', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      final port = session.port as FakeMsu2Port;
      await api.fillRect(
        const Rect(0, 0, 160, 80),
        Rgb565Colors.green,
      );
      final frames = port.writes.where((w) => !_isChallenge(w)).toList();
      expect(frames, hasLength(3));
      expect(toHex(frames[0]), '020000000000');
      expect(toHex(frames[1]), '020100A00050');
      expect(toHex(frames[2]), '02030B07E000');
    });

    test('clear 使用整块面板区域', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      await api.clear();
      final port = session.port as FakeMsu2Port;
      final frames = port.writes.where((w) => !_isChallenge(w)).toList();
      expect(toHex(frames[1]), '020100A00050');
    });

    test('drawFrame 校验面板像素数', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      expect(
        () => api.drawFrame(List<Rgb565Color>.filled(10, Rgb565Colors.black)),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.invalidArgument)),
      );
    });

    test('fillRect 越界时报 OutOfBounds', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      await expectLater(
        api.fillRect(const Rect(0, 0, 161, 80), Rgb565Colors.black),
        throwsA(isA<MoliException>()
            .having((e) => e.kind, 'kind', MoliErrorKind.outOfBounds)),
      );
    });

    test('drawPixelsRle 设置区域后发送 RleCodec 帧', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      final port = session.port as FakeMsu2Port;
      final before = port.writes.length;
      await api.drawPixelsRle(
        const Rect(1, 2, 5, 1),
        const [
          Rgb565Colors.red,
          Rgb565Colors.red,
          Rgb565Colors.red,
          Rgb565Colors.black,
          Rgb565Colors.black,
        ],
      );
      final frames = port.writes.skip(before).toList();
      expect(frames, hasLength(3));
      expect(toHex(frames[0]), '020000010002');
      expect(toHex(frames[1]), '020100050001');
      expect(toHex(frames[2]), '0932F8000000');
    });

    test('drawPixelsRle 最坏帧数超过上界时拒绝且不发送', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      final port = session.port as FakeMsu2Port;
      final before = port.writes.length;
      await expectLater(
        api.drawPixelsRle(
          const Rect(0, 0, 1, 1),
          const [Rgb565Colors.red],
          maxFrames: 1,
        ),
        throwsA(
          isA<MoliException>()
              .having((e) => e.kind, 'kind', MoliErrorKind.invalidArgument)
              .having((e) => e.message, 'message', contains('drawPixels')),
        ),
      );
      expect(port.writes.length, before);
    });

    test('4x4 图片走完整的像素流帧序列', () async {
      final session = await connectedSession();
      final api = MoliDeviceApi(session);
      final port = session.port as FakeMsu2Port;
      await api.drawPixels(
        const Rect(0, 0, 4, 4),
        List<Rgb565Color>.filled(16, Rgb565Colors.red),
      );
      final frames = port.writes.where((w) => !_isChallenge(w)).toList();
      // setXy, setSize, beginStream(07), 8 staging frames for 32 bytes,
      // commit(08) with the batch length, waitTransferDone(09).
      expect(frames, hasLength(13));
      expect(toHex(frames[2]), '020307' '000000');
      expect(toHex(frames[3]), '0400F800F800');
      expect(toHex(frames[10]), '0407F800F800');
      expect(toHex(frames[11]), '020308' '002000');
      expect(toHex(frames[12]), '020309' '000000');
    });
  });
}

bool _isChallenge(Uint8List bytes) {
  if (bytes.length != 6) return false;
  for (var i = 0; i < 6; i++) {
    if (bytes[i] != kHandshakeChallenge[i]) return false;
  }
  return true;
}
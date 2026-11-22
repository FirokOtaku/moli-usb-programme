// The ten normative encoding assertions from
// `spec/msu2-wire-protocol.md` ("规范测试向量").
//
// These only verify pure encoding; they say nothing about device responses.

import 'package:space_firok_moli_usb_protocol/space_firok_moli_usb_protocol.dart';
import 'package:test/test.dart';

void expectFrame(Frame frame, String expectedHex) {
  expect(toHex(frame.bytes), expectedHex.replaceAll(' ', ''));
}

void main() {
  group('spec 规范测试向量', () {
    const display = DisplayCodec();
    const sfr = SfrCodec();
    const flash = FlashCodec();
    const scratch = ScratchCodec();
    const adc = AdcCodec();

    test('设置坐标 (x=1, y=2) -> 02 00 00 01 00 02', () {
      expectFrame(display.setXy(1, 2), '02 00 00 01 00 02');
    });

    test('设置尺寸 160x80 -> 02 01 00 A0 00 50', () {
      expectFrame(display.setSize(160, 80), '02 01 00 A0 00 50');
    });

    test('设置红/黑前景背景 -> 02 02 F8 00 00 00', () {
      expectFrame(
        display.setColors(Rgb565Colors.red, Rgb565Colors.black),
        '02 02 F8 00 00 00',
      );
    });

    test('填充当前区域绿色 -> 02 03 0B 07 E0 00', () {
      expectFrame(
        display.fillCurrent(Rgb565Colors.green),
        '02 03 0B 07 E0 00',
      );
    });

    test('读 ADC 通道 9 -> 08 09 00 00 00 00', () {
      expectFrame(adc.readChannel(9), '08 09 00 00 00 00');
    });

    test('读 u8 SFR 0x0123 -> 00 30 00 01 23 00', () {
      expectFrame(sfr.readU8(0x0123), '00 30 00 01 23 00');
    });

    test('写 u8 SFR 0x0123=0x7F -> 00 30 80 01 23 7F', () {
      expectFrame(sfr.writeU8(0x0123, 0x7F), '00 30 80 01 23 7F');
    });

    test('读 Flash 字节 0x012345 -> 03 00 01 23 45 00', () {
      expectFrame(flash.readByte(0x012345), '03 00 01 23 45 00');
    });

    test('暂存区 offset 2 写 DEADBEEF -> 04 02 DE AD BE EF', () {
      expectFrame(
        scratch.write(2, const [0xDE, 0xAD, 0xBE, 0xEF]),
        '04 02 DE AD BE EF',
      );
    });

    test('RLE 红×3 黑×2 -> 09 32 F8 00 00 00', () {
      final frames = RleCodec.encode([
        Rgb565Colors.red.value,
        Rgb565Colors.red.value,
        Rgb565Colors.red.value,
        Rgb565Colors.black.value,
        Rgb565Colors.black.value,
      ]);
      expect(frames, hasLength(1));
      expectFrame(frames.single, '09 32 F8 00 00 00');
    });

    test('128 像素块与 MSN_compaction 逐字节一致', () {
      const red = 0xF800;
      const green = 0x07E0;
      const blue = 0x001F;
      const white = 0xFFFF;
      final pixels = <int>[
        for (var pair = 0; pair < 4; pair++) ...[
          ...List<int>.filled(15, red),
          ...List<int>.filled(15, green),
        ],
        ...List<int>.filled(4, blue),
        ...List<int>.filled(4, white),
      ];
      final frames = RleCodec.encode(pixels);
      expect(frames, hasLength(5));
      final bytes = frames.expand((frame) => frame.bytes).toList();
      expect(
        toHex(bytes),
        '09FFF80007E0'
        '09FFF80007E0'
        '09FFF80007E0'
        '09FFF80007E0'
        '0944001FFFFF',
      );
    });
  });

  group('编码器基础行为', () {
    test('RGB565 分量与常量一致', () {
      expect(Rgb565Color.fromComponents(31, 0, 0).value, 0xF800);
      expect(Rgb565Color.fromComponents(0, 63, 0).value, 0x07E0);
      expect(Rgb565Color.fromComponents(0, 0, 31).value, 0x001F);
      expect(Rgb565Color.fromComponents(31, 63, 31).value, 0xFFFF);
      expect(Rgb565Colors.red.value, 0xF800);
      expect(Rgb565Colors.green.value, 0x07E0);
      expect(Rgb565Colors.black.value, 0x0000);
      expect(Rgb565Colors.white.value, 0xFFFF);
    });

    test('像素写出为高字节在前', () {
      final bytes = <Rgb565Color>[
        Rgb565Colors.red,
        Rgb565Colors.green,
        Rgb565Colors.black,
      ].toWireBytes();
      expect(toHex(bytes), 'F80007E00000');
    });

    test('帧长度固定 6 字节且拒绝越界字节', () {
      final frame = Frame(command: 0x02, subcommand: 0x03, data0: 0x0B);
      expect(frame.bytes, hasLength(6));
      expect(
        () => Frame(command: 0x100),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
    });

    test('Frame.fromBytes 要求恰好 6 字节', () {
      final frame = Frame.fromBytes(const [1, 2, 3, 4, 5, 6]);
      expect(frame.command, 1);
      expect(frame.responseU8, 6);
      expect(
        () => Frame.fromBytes(const [1, 2]),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.malformedResponse,
        )),
      );
    });

    test('响应读取器按大端解析', () {
      final frame = Frame.fromBytes(const [0x08, 0x09, 0x00, 0x00, 0x12, 0x34]);
      expect(frame.responseU16At4, 0x1234);
      expect(frame.responseU16At2, 0x0000);
      expect(frame.responseU8, 0x34);
    });

    test('requirePrefix 只比较给定字节数', () {
      final frame = Frame.fromBytes(const [0x02, 0x00, 0x00, 0x01, 0x00, 0x02]);
      frame.requirePrefix(const [0x02, 0x00], count: 2);
      expect(
        () => frame.requirePrefix(const [0x02, 0x01], count: 2),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.malformedResponse,
        )),
      );
    });
  });

  group('Rect 与边界', () {
    test('Rect 拒绝负原点和非正尺寸', () {
      expect(
        () => Rect(0, 0, 0, 10).validate(),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
      expect(
        () => Rect(-1, 0, 10, 10).validate(),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
    });

    test('Rect 超出面板时报 OutOfBounds', () {
      const rect = Rect(150, 0, 20, 10);
      expect(
        () => rect.ensureWithin(160, 80),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.outOfBounds,
        )),
      );
      const ok = Rect(0, 0, 160, 80);
      ok.ensureWithin(160, 80);
    });

    test('DisplayGeometry 报告整帧字节数', () {
      const geometry = DisplayGeometry(width: 160, height: 80);
      expect(geometry.frameByteCount, 160 * 80 * 2);
      expect(geometry.contains(const Rect(0, 0, 160, 80)), isTrue);
      expect(geometry.contains(const Rect(0, 0, 161, 80)), isFalse);
    });
  });

  group('RLE 编码细节', () {
    test('128 像素的完整块与尾部短块都产生帧', () {
      final pixels = List<int>.filled(130, Rgb565Colors.white.value);
      final frames = RleCodec.encode(pixels);
      // MSN_compaction pairs 15+15. 128 pixels are 4 such frames plus one
      // 8-pixel frame; the 2-pixel tail is its own block.
      expect(frames, hasLength(5 + 1));
      expect(frames.first.subcommand, 0xFF);
      expect(frames[4].subcommand, 0x80);
      expect(frames.last.subcommand & 0x0F, 0);
      expect(frames.last.subcommand >> 4, 2);
      // Frames never exceed one 128-pixel block worth of decoded pixels.
      var decoded = 0;
      for (final frame in frames) {
        final runA = frame.subcommand >> 4;
        final runB = frame.subcommand & 0x0F;
        decoded += runA + runB;
      }
      expect(decoded, 130);
    });

    test('单一颜色块用 runB=0 收尾', () {
      final frames = RleCodec.encode(List<int>.filled(5, 0xFFFF));
      expect(frames, hasLength(1));
      expect(frames.single.subcommand, 0x50);
      expect(toHex(frames.single.bytes), '0950FFFFFFFF');
    });

    test('解码回读像素数量一致', () {
      final pixels = [
        ...List<int>.filled(7, Rgb565Colors.red.value),
        ...List<int>.filled(4, Rgb565Colors.green.value),
      ];
      final frames = RleCodec.encode(pixels);
      var decoded = 0;
      for (final frame in frames) {
        final pairs = RleCodec.decodeFrame(frame);
        for (var i = 1; i < pairs.length; i += 2) {
          decoded += pairs[i];
        }
      }
      expect(decoded, pixels.length);
    });

    test('空像素列表被拒绝', () {
      expect(
        () => RleCodec.encode(const []),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
    });

    test('最坏情况上界按 128 像素块计算', () {
      expect(RleCodec.worstCaseFrameCount(0), 0);
      expect(RleCodec.worstCaseFrameCount(1), RleCodec.maxRunsPerBlock);
      expect(
        RleCodec.worstCaseFrameCount(RleCodec.pixelsPerBlock * 3),
        RleCodec.maxRunsPerBlock * 3,
      );
      expect(
        RleCodec.worstCaseByteCount(1),
        RleCodec.maxRunsPerBlock * 6,
      );
    });
  });

  group('SFR / Flash / ADC 参数校验', () {
    test('SFR u16 读按 u8 地址编码', () {
      expectFrame(const SfrCodec().readU16(0x42), '00 30 20 42 00 00');
    });

    test('SFR 读值解码使用响应字段', () {
      final response = Frame.fromBytes(const [0x00, 0x30, 0x00, 0x00, 0x12, 0x34]);
      expect(const SfrCodec().decodeU16(response), 0x1234);
      expect(const SfrCodec().decodeU8(response), 0x34);
    });

    test('Flash 参数越界被拒绝', () {
      expect(
        () => const FlashCodec().readByte(0x1000000),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
      expect(
        () => const FlashCodec().erasePages(0, 0x10000),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
    });

    test('ADC 通道范围检查', () {
      expect(
        () => const AdcCodec().readChannel(256),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
    });

    test('暂存区偏移和 4 字节载荷检查', () {
      expect(
        () => const ScratchCodec().write(64, const [1, 2, 3, 4]),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
      expect(
        () => const ScratchCodec().write(0, const [1, 2, 3]),
        throwsA(isA<MoliException>().having(
          (e) => e.kind,
          'kind',
          MoliErrorKind.invalidArgument,
        )),
      );
    });

    test('暂存区分块填充 256 字节需要 64 帧', () {
      final chunk = List<int>.generate(256, (i) => i & 0xFF);
      final frames = const ScratchCodec().writeChunk(chunk);
      expect(frames, hasLength(ScratchCodec.framesPerScratch));
      expect(frames.first.subcommand, 0);
      expect(frames.last.subcommand, 63);
      expect(toHex(frames.first.bytes), '0400${
        chunk.sublist(0, 4).map((b) => b.toRadixString(16).padLeft(2, '0').toUpperCase()).join()
      }');
    });

    test('尾部分块补零后仍是 4 字节载荷', () {
      final frames = const ScratchCodec().writeChunk(const [0xAA, 0xBB]);
      expect(frames, hasLength(1));
      expect(toHex(frames.single.bytes), '0400AABB' '0000');
    });
  });

  group('握手编解码', () {
    const handshake = HandshakeCodec();

    test('解析 \u0000MSN01 横幅为协议版本 1', () {
      expect(handshake.tryParseBanner(parseHex('00 4D 53 4E 30 31')), 1);
    });

    test('非横幅字节返回 null', () {
      expect(handshake.tryParseBanner(parseHex('00 4D 53 4E 41 42')), isNull);
      expect(handshake.tryParseBanner(parseHex('01 02 03 04 05 06')), isNull);
    });

    test('挑战帧为 00 4D 53 4E 43 4E', () {
      expect(toHex(kHandshakeChallenge), '004D534E434E');
      expect(handshake.isConfirmResponse(kHandshakeChallenge), isTrue);
      expect(
        handshake.isConfirmResponse(parseHex('00 4D 53 4E 30 31')),
        isFalse,
      );
    });
  });
}
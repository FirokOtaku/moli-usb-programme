// SFR (special function register) codec: frames with command 0x00 and
// subcommand ASCII '0' (0x30).
//
// Operations per spec/msu2-wire-protocol.md:
//   0x00 read u8  : 00 30 00 addr_hi addr_lo 00          -> byte 5 = u8
//   0x20 read u16 : 00 30 20 addr 00 00                  -> bytes 4..5 = u16_be
//   0x80 write u8 : 00 30 80 addr_hi addr_lo value
//   0xA0 write u16: 00 30 A0 addr value_hi value_lo      (candidate value; the
//      new LITE/PRO sources wrongly use 0x20, so u16 writes stay non-public)

import 'dart:typed_data';

import '../errors.dart';
import 'frame.dart';

/// SFR access opcodes.
abstract final class SfrOp {
  static const int readU8 = 0x00;
  static const int readU16 = 0x20;
  static const int writeU8 = 0x80;
  /// Candidate value for u16 writes. Vendor sources disagree (new LITE/PRO
  /// sources reuse 0x20 which clashes with the read opcode), so this operation
  /// is NOT exposed by the session layer until real hardware confirms 0xA0.
  static const int writeU16Candidate = 0xA0;
}

/// Encodes SFR frames and decodes their responses.
final class SfrCodec {
  const SfrCodec();

  /// `00 30 00 addr_hi addr_lo 00`; response u8 at byte 5.
  Frame readU8(int address) {
    _checkU16('address', address);
    return Frame(
      command: 0x00,
      subcommand: 0x30,
      data0: 0x00,
      data1: address >> 8,
      data2: address & 0xFF,
      data3: 0,
    );
  }

  /// `00 30 20 addr 00 00`; response u16 at bytes 4..5 (big-endian).
  Frame readU16(int address) {
    _checkU8('address', address);
    return Frame(
      command: 0x00,
      subcommand: 0x30,
      data0: 0x20,
      data1: address,
      data2: 0,
      data3: 0,
    );
  }

  /// `00 30 80 addr_hi addr_lo value`.
  Frame writeU8(int address, int value) {
    _checkU16('address', address);
    _checkU8('value', value);
    return Frame(
      command: 0x00,
      subcommand: 0x30,
      data0: 0x80,
      data1: address >> 8,
      data2: address & 0xFF,
      data3: value,
    );
  }

  /// Decodes the u8 value from a readU8 response (byte 5).
  int decodeU8(Frame response) => response.responseU8;

  /// Decodes the u16 value from a readU16 response (bytes 4..5, big-endian).
  int decodeU16(Frame response) => response.responseU16At4;

  static void _checkU8(String name, int value) {
    if (value < 0 || value > 0xFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..255 (got $value)',
        operation: 'SfrCodec',
      );
    }
  }

  static void _checkU16(String name, int value) {
    if (value < 0 || value > 0xFFFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..65535 (got $value)',
        operation: 'SfrCodec',
      );
    }
  }
}

/// One entry of the 256-byte self-describing SFR table (address 0x0100..).
///
/// Layout per spec: NUL-terminated name, NUL-terminated unit, 1 family byte
/// whose high 3 bits determine the entry shape and low 5 bits the element
/// count/length, then family-specific data.
enum SfrEntryFamily {
  u8Register('u8', 0),
  u16Register('u16', 1),
  u32Register('u32', 2),
  byteString('string', 3),
  byteArray('bytes', 4);

  const SfrEntryFamily(this.label, int familyBits)
      : bits = familyBits & 0x07;

  /// Family value as encoded in the table (high 3 bits of the family byte).
  final int bits;
  final String label;

  static SfrEntryFamily? fromBits(int rawFamily) {
    final kind = (rawFamily >> 5) & 0x07;
    for (final f in SfrEntryFamily.values) {
      if (f.bits == kind) return f;
    }
    return null;
  }
}

/// Parsed SFR table entry.
final class SfrTableEntry {
  const SfrTableEntry({
    required this.name,
    required this.unit,
    required this.family,
    required this.count,
    required this.address,
    required this.length,
  });

  final String name;
  final String unit;
  final SfrEntryFamily family;

  /// Element count for register families, length for string/array families.
  final int count;

  /// Register address when [family] is u8/u16/u32, else unused (0).
  final int address;

  /// Raw data length in bytes for string/array families, else 0.
  final int length;

  @override
  String toString() =>
      'SfrTableEntry($name $unit, family=${family.label}, count=$count, '
      'address=0x${address.toRadixString(16)}, length=$length)';
}

/// Parser for the SFR self-description table.
///
/// The vendor C parser has known defects (family 4 length handling). This
/// parser follows the byte layout documented in the spec and reports
/// [MoliErrorKind.malformedResponse] instead of guessing when the table ends
/// prematurely.
final class SfrTableParser {
  const SfrTableParser();

  /// Parses entries out of [table] (up to 256 bytes read from 0x0100).
  List<SfrTableEntry> parse(Uint8List table) {
    final entries = <SfrTableEntry>[];
    var offset = 0;
    while (offset < table.length) {
      final nameEnd = table.indexOf(0, offset);
      if (nameEnd < 0) break; // truncated trailing entry: stop, don't guess
      if (nameEnd == offset) break; // empty name: end-of-table padding
      final name = String.fromCharCodes(table.sublist(offset, nameEnd));
      offset = nameEnd + 1;

      final unitEnd = table.indexOf(0, offset);
      if (unitEnd < 0) {
        throw MoliException.malformedResponse(
          'SFR table truncated inside unit of "$name"',
          operation: 'SfrTableParser.parse',
        );
      }
      final unit = String.fromCharCodes(table.sublist(offset, unitEnd));
      offset = unitEnd + 1;

      if (offset >= table.length) {
        throw MoliException.malformedResponse(
          'SFR table truncated before family byte of "$name"',
          operation: 'SfrTableParser.parse',
        );
      }
      final familyByte = table[offset];
      offset += 1;
      final family = SfrEntryFamily.fromBits(familyByte);
      if (family == null) {
        throw MoliException.malformedResponse(
          'unknown SFR family 0x${familyByte.toRadixString(16)} for "$name"',
          operation: 'SfrTableParser.parse',
        );
      }
      final count = familyByte & 0x1F;

      switch (family) {
        case SfrEntryFamily.u8Register:
          _require(table, offset + 2, name);
          entries.add(SfrTableEntry(
            name: name,
            unit: unit,
            family: family,
            count: count,
            address: (table[offset] << 8) | table[offset + 1],
            length: 0,
          ));
          offset += 2;
        case SfrEntryFamily.u16Register:
          _require(table, offset + 1, name);
          entries.add(SfrTableEntry(
            name: name,
            unit: unit,
            family: family,
            count: count,
            address: table[offset],
            length: 0,
          ));
          offset += 1;
        case SfrEntryFamily.u32Register:
          // Vendor read path is unfinished; the data length for family 2 is a
          // P0 verification item. We accept the documented 16-bit address
          // shape so tables can round-trip, but the session layer does not
          // expose u32 reads.
          _require(table, offset + 2, name);
          entries.add(SfrTableEntry(
            name: name,
            unit: unit,
            family: family,
            count: count,
            address: (table[offset] << 8) | table[offset + 1],
            length: 0,
          ));
          offset += 2;
        case SfrEntryFamily.byteString:
          _require(table, offset + count, name);
          entries.add(SfrTableEntry(
            name: name,
            unit: unit,
            family: family,
            count: count,
            address: 0,
            length: count,
          ));
          offset += count;
        case SfrEntryFamily.byteArray:
          // Family 4 length handling is defective in the vendor parser; treat
          // the low 5 bits as length like family 3 and let verification
          // correct us. Unknown/oversized claims are rejected, not guessed.
          _require(table, offset + count, name);
          entries.add(SfrTableEntry(
            name: name,
            unit: unit,
            family: family,
            count: count,
            address: 0,
            length: count,
          ));
          offset += count;
      }
    }
    return entries;
  }

  static void _require(Uint8List table, int end, String name) {
    if (end > table.length) {
      throw MoliException.malformedResponse(
        'SFR table truncated inside data of "$name"',
        operation: 'SfrTableParser.parse',
      );
    }
  }
}

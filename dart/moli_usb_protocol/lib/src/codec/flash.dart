// Flash / external storage codec (command 0x03).
//
// spec/msu2-wire-protocol.md records:
//   03 00 address_u24_be 00            -> read one byte; response byte 5
//   03 01 page_u24_be page_count        -> write scratch into pages
//   03 02 start_page_u16_be count_u16_be -> erase consecutive pages
//   03 03 page_u24_be page_count        -> fast write (write + erase)
//   03 04 start_page_u16_be 00 00       -> LITE: high speed write start
//   03 04 00 block_u16_be 00            -> PRO: NAND high speed write start
//   03 05 00 00 00 00                   -> chip erase (PRO)
//   03 06 block_u16_be 00 00            -> erase NAND block (PRO)
//
// Because 0x04 conflicts between LITE (serial Flash) and PRO (NAND), the
// session layer routes by profile/capability — never by model name guesses.

import '../errors.dart';
import 'frame.dart';

/// Flash sub-commands (byte 1 of command 0x03).
abstract final class FlashSub {
  static const int readByte = 0x00;
  static const int writePagesFromScratch = 0x01;
  static const int erasePages = 0x02;
  static const int writePagesFast = 0x03;
  static const int highSpeedWriteStart = 0x04;
  static const int chipErase = 0x05;
  static const int eraseNandBlock = 0x06;
}

/// Geometry of one external storage medium.
///
/// Reported by the device self-description (P0 item) or configured by the host
/// from a verified profile; used by the session layer for bounds checks before
/// any erase/write.
final class StorageGeometry {
  const StorageGeometry({
    required this.type,
    required this.capacityBytes,
    required this.pageSizeBytes,
    required this.eraseGranularityBytes,
    required this.addressableByteRange,
  });

  /// Medium kind ("serial-flash", "nand", ...).
  final String type;

  /// Total capacity in bytes.
  final int capacityBytes;

  /// Physical page size in bytes.
  final int pageSizeBytes;

  /// Erase granularity in bytes (P25D80: 256-byte pages can be erased).
  final int eraseGranularityBytes;

  /// [inclusiveStart, exclusiveEnd] of the writable address range.
  final RangeValues addressableByteRange;

  int get pageCount => capacityBytes ~/ pageSizeBytes;

  bool containsAddress(int address) =>
      address >= addressableByteRange.start && address < addressableByteRange.end;

  /// Throws [MoliErrorKind.outOfBounds] when [address] is outside range.
  void checkAddress(int address, {String? operation}) {
    if (!containsAddress(address)) {
      throw MoliException.outOfBounds(
        'address 0x${address.toRadixString(16)} outside medium range '
        '0x${addressableByteRange.start.toRadixString(16)}..'
        '0x${addressableByteRange.end.toRadixString(16)}',
        operation: operation ?? 'StorageGeometry.checkAddress',
      );
    }
  }

  /// Throws [MoliErrorKind.outOfBounds] when the [length]-byte write at
  /// [address] exceeds capacity.
  void checkWriteRange(int address, int length, {String? operation}) {
    checkAddress(address, operation: operation);
    if (length < 0 || address + length > addressableByteRange.end) {
      throw MoliException.outOfBounds(
        'write length $length at 0x${address.toRadixString(16)} exceeds medium '
        'capacity',
        operation: operation ?? 'StorageGeometry.checkWriteRange',
      );
    }
  }

  /// Throws [MoliErrorKind.storageCapacityExceeded] when more than
  /// [capacityBytes] is requested.
  void checkCapacity(int requestedBytes, {String? operation}) {
    if (requestedBytes > capacityBytes) {
      throw MoliException.storageCapacityExceeded(
        'requested $requestedBytes bytes exceeds medium capacity $capacityBytes',
        operation: operation ?? 'StorageGeometry.checkCapacity',
      );
    }
  }
}

/// Inclusive start, exclusive end.
final class RangeValues {
  const RangeValues(this.start, this.end);
  final int start;
  final int end;
}

/// Encodes Flash frames.
final class FlashCodec {
  const FlashCodec();

  /// `03 00 addr_hi addr_mid addr_lo 00`; response: byte 5 holds the byte.
  Frame readByte(int address) {
    _checkU24('address', address);
    return Frame(command: 0x03, subcommand: 0x00, data0: (address >> 16) & 0xFF,
        data1: (address >> 8) & 0xFF, data2: address & 0xFF, data3: 0);
  }

  /// Decodes the read byte from a readByte response (byte 5).
  int decodeReadByte(Frame response) => response.responseU8;

  /// `03 01 page count`: commit staged scratch into [count] pages starting at
  /// [page] (page as u24_be, count as u8).
  Frame writePagesFromScratch(int page, int pageCount) {
    _checkU24('page', page);
    _checkU8('pageCount', pageCount);
    return Frame(command: 0x03, subcommand: 0x01, data0: (page >> 16) & 0xFF,
        data1: (page >> 8) & 0xFF, data2: page & 0xFF, data3: pageCount);
  }

  /// `03 02 start_page_u16 count_u16`: erase consecutive pages.
  Frame erasePages(int startPage, int pageCount) {
    _checkU16('startPage', startPage);
    _checkU16('pageCount', pageCount);
    return Frame(command: 0x03, subcommand: 0x02, data0: startPage >> 8,
        data1: startPage & 0xFF, data2: pageCount >> 8, data3: pageCount & 0xFF);
  }

  /// `03 03 page count`: fast write (write + erase) for [count] pages at [page].
  Frame writePagesFast(int page, int pageCount) {
    _checkU24('page', page);
    _checkU8('pageCount', pageCount);
    return Frame(command: 0x03, subcommand: 0x03, data0: (page >> 16) & 0xFF,
        data1: (page >> 8) & 0xFF, data2: page & 0xFF, data3: pageCount);
  }

  /// `03 04 start_page_u16 00 00`: LITE high-speed write start.
  Frame highSpeedWriteStartSerialFlash(int startPage) {
    _checkU16('startPage', startPage);
    return Frame(command: 0x03, subcommand: 0x04, data0: startPage >> 8,
        data1: startPage & 0xFF, data2: 0, data3: 0);
  }

  /// `03 04 00 block_u16_be 00`: PRO NAND high-speed write start.
  Frame highSpeedWriteStartNand(int block) {
    _checkU16('block', block);
    return Frame(command: 0x03, subcommand: 0x04, data0: 0,
        data1: block >> 8, data2: block & 0xFF, data3: 0);
  }

  /// `03 05 00 00 00 00`: chip erase (PRO).
  Frame chipErase() => Frame(command: 0x03, subcommand: 0x05);

  /// `03 06 block_u16 00 00`: erase one NAND block (PRO).
  Frame eraseNandBlock(int block) {
    _checkU16('block', block);
    return Frame(command: 0x03, subcommand: 0x06, data0: block >> 8,
        data1: block & 0xFF, data2: 0, data3: 0);
  }

  static void _checkU8(String name, int value) {
    if (value < 0 || value > 0xFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..255 (got $value)',
        operation: 'FlashCodec',
      );
    }
  }

  static void _checkU16(String name, int value) {
    if (value < 0 || value > 0xFFFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..65535 (got $value)',
        operation: 'FlashCodec',
      );
    }
  }

  static void _checkU24(String name, int value) {
    if (value < 0 || value > 0xFFFFFF) {
      throw MoliException.invalidArgument(
        '$name must be 0..16777215 (got $value)',
        operation: 'FlashCodec',
      );
    }
  }
}

/// Flash medium profile parameters used to pick encoding and validate access.
///
/// The same frame bytes mean different things on LITE vs PRO (see `03 04`),
/// so the session layer chooses based on the confirmed profile rather than the
/// product name alone.
final class FlashMediumProfile {
  const FlashMediumProfile({
    required this.type,
    required this.geometry,
    required this.highSpeedWriteStart,
    required this.eraseSupported,
    required this.writePagesSupported,
  });

  final String type;
  final StorageGeometry geometry;
  final HighSpeedWriteStart highSpeedWriteStart;
  final bool eraseSupported;
  final bool writePagesSupported;

  bool containsAddress(int address) => geometry.containsAddress(address);
  bool writeRangeOk(int address, int length) =>
      address >= geometry.addressableByteRange.start &&
          address + length <= geometry.addressableByteRange.end;
}

/// Which high-speed-start layout applies.
enum HighSpeedWriteStart {
  /// 03 04 start_page_u16 00 00 — serial Flash (LITE).
  serialFlash,
  /// 03 04 00 block_u16_be 00 — NAND (PRO).
  nand,
}

extension type const FlashAddress(int value) {}

extension type const FlashPage(int value) {}

/// Capability bit fields for storage.
class StorageCapabilities {
  StorageCapabilities({
    this.readByteSupported = true,
    this.eraseSupported = false,
    this.writePagesSupported = false,
    this.chipEraseSupported = false,
  });

  final bool readByteSupported;
  final bool eraseSupported;
  final bool writePagesSupported;
  final bool chipEraseSupported;
}

// The win32 barrel opens unrelated DLLs at load time. These libraries are the
// kernel32/user32/gdi32 bindings this wave actually calls.
// ignore_for_file: implementation_imports

import 'dart:ffi';
import 'dart:typed_data';

import 'package:ffi/ffi.dart';
import 'package:win32/src/structs.g.dart';
import 'package:win32/src/win32/gdi32.g.dart';
import 'package:win32/src/win32/kernel32.g.dart';
import 'package:win32/src/win32/user32.g.dart';

import 'desktop_capture.dart';
import 'host_api.dart';
import 'system_reading.dart';

/// Win32 `MONITOR_DEFAULTTOPRIMARY`.
const int _monitorDefaultToPrimary = 0x00000001;

/// Win32 `BI_RGB`.
const int _biRgb = 0;

/// Win32 `DIB_RGB_COLORS`.
const int _dibRgbColors = 0;

/// Win32 `SRCCOPY`.
const int _srcCopy = 0x00CC0020;

/// Win32 `CAPTUREBLT`. Combined with SRCCOPY so layered windows are included.
const int _captureBlt = 0x40000000;

/// Header plus one color quad. Matches `BITMAPINFO` without the variable array.
base class _CaptureInfo extends Struct {
  external BITMAPINFOHEADER header;

  @Uint8()
  external int blue;

  @Uint8()
  external int green;

  @Uint8()
  external int red;

  @Uint8()
  external int reserved;
}

/// Reads CPU times, memory, one drive and battery. A failed call leaves that
/// field null; the others are still filled.
///
/// [driveRoot] is the only path queried. This does not enumerate volumes or
/// network drives.
RawMetricSnapshot readWindowsMetrics(String? driveRoot) {
  final times = _tryTimes();
  final memory = _tryMemory();
  final disk = _tryDisk(driveRoot);
  final power = _tryPower();
  return RawMetricSnapshot(
    times: times,
    memoryTotalBytes: memory?.$1,
    memoryAvailBytes: memory?.$2,
    diskTotalBytes: disk?.$1,
    diskFreeBytes: disk?.$2,
    diskLabel: disk?.$3,
    powerOk: power != null,
    batteryFlag: power?.$1 ?? 0,
    batteryPercent: power?.$2 ?? 0,
    acLineStatus: power?.$3 ?? kBatteryStatusUnknown,
  );
}

/// BitBlt of the primary monitor only. Runs on a worker isolate.
DesktopCapture capturePrimaryScreen() {
  final screen = GetDC(0);
  if (screen == 0) {
    throw const HostMetricException('无法取得主屏幕设备上下文');
  }
  var memory = 0;
  var bitmap = 0;
  var previous = 0;
  var restored = false;
  Pointer<Uint8>? pixels;
  Pointer<_CaptureInfo>? info;
  try {
    final bounds = _primaryBounds();
    final width = bounds.$1;
    final height = bounds.$2;
    final originX = bounds.$3;
    final originY = bounds.$4;
    memory = CreateCompatibleDC(screen);
    if (memory == 0) {
      throw const HostMetricException('无法创建屏幕兼容设备上下文');
    }
    bitmap = CreateCompatibleBitmap(screen, width, height);
    if (bitmap == 0) {
      throw const HostMetricException('无法创建屏幕位图');
    }
    previous = SelectObject(memory, bitmap);
    final layered = BitBlt(
      memory,
      0,
      0,
      width,
      height,
      screen,
      originX,
      originY,
      _srcCopy | _captureBlt,
    );
    final copied = layered != 0
        ? layered
        : BitBlt(
            memory,
            0,
            0,
            width,
            height,
            screen,
            originX,
            originY,
            _srcCopy,
          );
    if (copied == 0) {
      throw HostMetricException('截取主屏幕失败 (${GetLastError()})');
    }
    if (previous != 0) {
      SelectObject(memory, previous);
      restored = true;
    }
    final byteCount = width * height * 4;
    info = calloc<_CaptureInfo>();
    info.ref.header
      ..biSize = sizeOf<BITMAPINFOHEADER>()
      ..biWidth = width
      ..biHeight = -height
      ..biPlanes = 1
      ..biBitCount = 32
      ..biCompression = _biRgb
      ..biSizeImage = byteCount;
    pixels = calloc<Uint8>(byteCount);
    final rows = GetDIBits(
      screen,
      bitmap,
      0,
      height,
      pixels,
      info.cast<BITMAPINFO>(),
      _dibRgbColors,
    );
    if (rows == 0) {
      throw HostMetricException('读取主屏幕像素失败 (${GetLastError()})');
    }
    return DesktopCapture(
      width: width,
      height: height,
      rgba: _bgraToRgba(pixels.asTypedList(byteCount)),
    );
  } finally {
    if (pixels != null) calloc.free(pixels);
    if (info != null) calloc.free(info);
    if (bitmap != 0) {
      if (!restored && previous != 0 && memory != 0) {
        SelectObject(memory, previous);
      }
      DeleteObject(bitmap);
    }
    if (memory != 0) DeleteDC(memory);
    ReleaseDC(0, screen);
  }
}

SystemTimesSample? _tryTimes() {
  final idle = calloc<FILETIME>();
  final kernel = calloc<FILETIME>();
  final user = calloc<FILETIME>();
  try {
    if (GetSystemTimes(idle, kernel, user) == 0) return null;
    return SystemTimesSample(
      idle: _fileTime(idle.ref),
      kernel: _fileTime(kernel.ref),
      user: _fileTime(user.ref),
    );
  } catch (_) {
    return null;
  } finally {
    calloc.free(idle);
    calloc.free(kernel);
    calloc.free(user);
  }
}

(int, int)? _tryMemory() {
  final status = calloc<MEMORYSTATUSEX>();
  try {
    status.ref.dwLength = sizeOf<MEMORYSTATUSEX>();
    if (GlobalMemoryStatusEx(status) == 0) return null;
    final total = status.ref.ullTotalPhys;
    final avail = status.ref.ullAvailPhys;
    if (total <= 0) return null;
    return (total, avail);
  } catch (_) {
    return null;
  } finally {
    calloc.free(status);
  }
}

(int, int, String?)? _tryDisk(String? driveRoot) {
  if (driveRoot == null || driveRoot.isEmpty) return null;
  final path = driveRoot.toNativeUtf16();
  final avail = calloc<Uint64>();
  final total = calloc<Uint64>();
  final freeBytes = calloc<Uint64>();
  try {
    final ok = GetDiskFreeSpaceEx(path, avail, total, freeBytes);
    if (ok == 0) return null;
    final totalBytes = total.value;
    if (totalBytes <= 0) return null;
    return (totalBytes, freeBytes.value, diskLabelOf(driveRoot));
  } catch (_) {
    return null;
  } finally {
    calloc.free(path);
    calloc.free(avail);
    calloc.free(total);
    calloc.free(freeBytes);
  }
}

(int, int, int)? _tryPower() {
  final status = calloc<SYSTEM_POWER_STATUS>();
  try {
    if (GetSystemPowerStatus(status) == 0) return null;
    return (
      status.ref.BatteryFlag,
      status.ref.BatteryLifePercent,
      status.ref.ACLineStatus,
    );
  } catch (_) {
    return null;
  } finally {
    calloc.free(status);
  }
}

(int, int, int, int) _primaryBounds() {
  final monitor = MonitorFromWindow(0, _monitorDefaultToPrimary);
  if (monitor == 0) {
    throw const HostMetricException('找不到主屏幕');
  }
  final info = calloc<MONITORINFO>();
  try {
    info.ref.cbSize = sizeOf<MONITORINFO>();
    if (GetMonitorInfo(monitor, info) == 0) {
      throw HostMetricException('读取主屏幕范围失败 (${GetLastError()})');
    }
    final rect = info.ref.rcMonitor;
    final width = rect.right - rect.left;
    final height = rect.bottom - rect.top;
    if (width <= 0 || height <= 0) {
      throw const HostMetricException('主屏幕尺寸无效');
    }
    final bytes = width * height * 4;
    if (width > 8192 || height > 8192 || bytes > 64 * 1024 * 1024) {
      throw HostMetricException('主屏幕过大（${width}x$height），已跳过这一帧');
    }
    return (width, height, rect.left, rect.top);
  } finally {
    calloc.free(info);
  }
}

int _fileTime(FILETIME time) {
  final low = time.dwLowDateTime & 0xFFFFFFFF;
  final high = time.dwHighDateTime & 0xFFFFFFFF;
  return low | (high << 32);
}

Uint8List _bgraToRgba(Uint8List bgra) {
  final rgba = Uint8List(bgra.length);
  for (var i = 0; i + 3 < bgra.length; i += 4) {
    rgba[i] = bgra[i + 2];
    rgba[i + 1] = bgra[i + 1];
    rgba[i + 2] = bgra[i];
    rgba[i + 3] = 255;
  }
  return rgba;
}

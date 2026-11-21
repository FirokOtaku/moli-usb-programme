import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../connection/connection_controller.dart';
import '../content/content_controller.dart';
import '../content/image_pick.dart';
import 'frame_preview.dart';

class ContentPanel extends StatefulWidget {
  const ContentPanel({
    super.key,
    required this.connection,
    required this.content,
  });

  final ConnectionController connection;
  final ContentController content;

  @override
  State<ContentPanel> createState() => _ContentPanelState();
}

class _ContentPanelState extends State<ContentPanel> {
  late final TextEditingController _text;

  @override
  void initState() {
    super.initState();
    _text = TextEditingController(text: widget.content.text);
  }

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final connection = widget.connection;
    final content = widget.content;
    final theme = Theme.of(context);
    final enabled = connection.phase == ConnectionPhase.connected;
    final sendEnabled = content.canSend;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: 12,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 160,
                child: DropdownButton<ContentMode>(
                  value: content.mode,
                  isExpanded: true,
                  items: [
                    for (final mode in ContentMode.values)
                      DropdownMenuItem<ContentMode>(
                        value: mode,
                        enabled: content.modeSelectable(mode),
                        child: Opacity(
                          opacity: content.modeSelectable(mode) ? 1 : 0.45,
                          child: Text(mode.label),
                        ),
                      ),
                  ],
                  onChanged: enabled
                      ? (value) {
                          if (value != null) content.selectMode(value);
                        }
                      : null,
                ),
              ),
              Text(
                enabled ? '画面内容' : '连接后可以选择画面内容',
                style: theme.textTheme.bodyMedium,
              ),
              if (content.sending)
                Text(
                  '发送中',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          if (content.hostMetricsUnsupportedReason != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                content.hostMetricsUnsupportedReason!,
                style: theme.textTheme.bodyMedium,
              ),
            ),
          if (content.slowPhoto)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                '当前串口为 19200 baud，整屏照片和桌面镜像发送会比较慢，界面仍可操作。',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.tertiary,
                ),
              ),
            ),
          if (content.geometryBlocked)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                ContentController.zeroPanelMessage,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ),
          if (content.statusMessage != null &&
              content.statusMessage != ContentController.zeroPanelMessage)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                content.statusMessage!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          _modeControls(enabled: enabled, sendEnabled: sendEnabled),
          const SizedBox(height: 8),
          const Text('预览'),
          const SizedBox(height: 4),
          Expanded(child: FramePreview(frame: content.preview)),
        ],
      ),
    );
  }

  Widget _modeControls({required bool enabled, required bool sendEnabled}) {
    final content = widget.content;
    switch (content.mode) {
      case ContentMode.solid:
        return _ColorEditor(
          label: '颜色',
          color: content.solid,
          enabled: enabled,
          onChanged: content.setSolid,
        );
      case ContentMode.image:
        return Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            OutlinedButton(
              onPressed: enabled ? _pickImage : null,
              child: const Text('选择图片'),
            ),
            Text(content.imageName ?? '尚未选择图片'),
            FilterChip(
              label: Text(content.cover ? '裁切铺满' : '完整显示'),
              selected: content.cover,
              onSelected: enabled && sendEnabled ? content.setCover : null,
            ),
          ],
        );
      case ContentMode.staticText:
      case ContentMode.scrollingText:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _text,
              enabled: enabled,
              decoration: const InputDecoration(
                labelText: '文本',
                isDense: true,
                border: OutlineInputBorder(),
              ),
              onChanged: content.setText,
            ),
            const SizedBox(height: 8),
            _ColorEditor(
              label: '文字颜色',
              color: content.foreground,
              enabled: enabled,
              onChanged: content.setForeground,
            ),
            _ColorEditor(
              label: '背景颜色',
              color: content.background,
              enabled: enabled,
              onChanged: content.setBackground,
            ),
          ],
        );
      case ContentMode.clock:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '大约每秒刷新一次，时间和日期在本机绘制。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            _ColorEditor(
              label: '文字颜色',
              color: content.foreground,
              enabled: enabled,
              onChanged: content.setForeground,
            ),
            _ColorEditor(
              label: '背景颜色',
              color: content.background,
              enabled: enabled,
              onChanged: content.setBackground,
            ),
          ],
        );
      case ContentMode.stats:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              '大约每秒刷新一次。CPU、内存、当前目录所在磁盘和电池在本机绘制；某一项读不到时，其余项仍会显示。没有电池时显示「无电池」。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            _ColorEditor(
              label: '文字颜色',
              color: content.foreground,
              enabled: enabled,
              onChanged: content.setForeground,
            ),
            _ColorEditor(
              label: '背景颜色',
              color: content.background,
              enabled: enabled,
              onChanged: content.setBackground,
            ),
          ],
        );
      case ContentMode.mirror:
        return Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text(
              '只截取主屏幕。发送未完成时丢弃新的一帧，不会排队。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            FilterChip(
              label: Text(content.mirrorCover ? '裁切铺满' : '完整显示'),
              selected: content.mirrorCover,
              onSelected: enabled && sendEnabled
                  ? content.setMirrorCover
                  : null,
            ),
          ],
        );
    }
  }

  void _pickImage() {
    // click() inside the picker must run before the first await.
    final pending = pickImageFile();
    unawaited(_finishPick(pending));
  }

  Future<void> _finishPick(Future<PickedImage?> pending) async {
    try {
      final picked = await pending;
      if (!mounted || picked == null) return;
      await widget.content.acceptImage(picked);
    } catch (error) {
      if (!mounted) return;
      widget.content.reportStatus('选择图片失败: $error');
    }
  }
}

class _ColorEditor extends StatelessWidget {
  const _ColorEditor({
    required this.label,
    required this.color,
    required this.enabled,
    required this.onChanged,
  });

  final String label;
  final HostColor color;
  final bool enabled;
  final ValueChanged<HostColor> onChanged;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(width: 72, child: Text(label)),
        Container(
          width: 28,
          height: 28,
          color: Color.fromARGB(255, color.red, color.green, color.blue),
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            children: [
              _channel('R', color.red, (value) {
                onChanged(color.copyWith(red: value));
              }),
              _channel('G', color.green, (value) {
                onChanged(color.copyWith(green: value));
              }),
              _channel('B', color.blue, (value) {
                onChanged(color.copyWith(blue: value));
              }),
            ],
          ),
        ),
      ],
    );
  }

  Widget _channel(String name, int value, ValueChanged<int> onChanged) {
    return Row(
      children: [
        SizedBox(width: 16, child: Text(name)),
        Expanded(
          child: Slider(
            value: value.toDouble(),
            max: 255,
            label: '$value',
            onChanged: enabled
                ? (next) => onChanged(next.round())
                : null,
          ),
        ),
      ],
    );
  }
}

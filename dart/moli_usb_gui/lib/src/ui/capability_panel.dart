import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../connection/capability_controller.dart';
import '../connection/connection_controller.dart';

/// Orientation, backlight, RGB, and MINI key. Same controls on Web and Windows.
class CapabilityPanel extends StatefulWidget {
  const CapabilityPanel({
    super.key,
    required this.connection,
    required this.controls,
  });

  final ConnectionController connection;
  final CapabilityController controls;

  @override
  State<CapabilityPanel> createState() => _CapabilityPanelState();
}

class _CapabilityPanelState extends State<CapabilityPanel> {
  late final TextEditingController _lamps;

  @override
  void initState() {
    super.initState();
    _lamps = TextEditingController(
      text: '${CapabilityController.defaultLampCount}',
    );
  }

  @override
  void dispose() {
    _lamps.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final connection = widget.connection;
    final controls = widget.controls;
    final theme = Theme.of(context);
    final connected = connection.phase == ConnectionPhase.connected;
    final orientations = connection.supportedOrientations;
    final orientationReason = connected
        ? orientationUnavailableReason(orientations)
        : null;
    final orientationEnabled = connected && orientationReason == null;
    final backlightOn = connected && connection.hasBacklight;
    final rgbOn = connected && connection.hasRgbLed;
    final keyOn = connected && connection.hasKeyInput;
    final muted = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );

    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('设备控制', style: theme.textTheme.titleSmall),
          if (!connected)
            Text('连接后可以控制方向、背光、RGB 灯和按键', style: muted),
          const SizedBox(height: 4),
          Wrap(
            spacing: 12,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              const Text('方向'),
              if (orientations.length >= 2)
                SizedBox(
                  width: 140,
                  child: DropdownButton<int>(
                    value: orientations.contains(controls.selectedOrientation)
                        ? controls.selectedOrientation
                        : null,
                    isExpanded: true,
                    hint: const Text('选择方向'),
                    items: [
                      for (final value in orientations)
                        DropdownMenuItem<int>(
                          value: value,
                          child: Text(switch (value) {
                            0 => '正常',
                            1 => '旋转 180°',
                            _ => '方向 $value',
                          }),
                        ),
                    ],
                    onChanged: orientationEnabled
                        ? (value) {
                            if (value != null) {
                              unawaited(controls.setOrientation(value));
                            }
                          }
                        : null,
                  ),
                ),
              if (orientationReason != null)
                Text(orientationReason, style: muted),
            ],
          ),
          Row(
            children: [
              const SizedBox(width: 48, child: Text('背光')),
              Expanded(
                child: Slider(
                  value: controls.backlight.toDouble(),
                  max: 1000,
                  label: '${controls.backlight}',
                  onChanged: backlightOn
                      ? (value) => controls.setBacklight(value.round())
                      : null,
                ),
              ),
              SizedBox(width: 40, child: Text('${controls.backlight}')),
            ],
          ),
          if (connected && !connection.hasBacklight)
            Text('当前设备不支持背光', style: muted),
          Row(
            children: [
              const SizedBox(width: 48, child: Text('RGB')),
              _swatch(controls.red, controls.green, controls.blue),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  children: [
                    _channel(
                      'R',
                      controls.red,
                      rgbOn,
                      (value) => controls.setLedColor(red: value),
                    ),
                    _channel(
                      'G',
                      controls.green,
                      rgbOn,
                      (value) => controls.setLedColor(green: value),
                    ),
                    _channel(
                      'B',
                      controls.blue,
                      rgbOn,
                      (value) => controls.setLedColor(blue: value),
                    ),
                  ],
                ),
              ),
            ],
          ),
          Wrap(
            spacing: 12,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 120,
                child: TextField(
                  controller: _lamps,
                  enabled: rgbOn && !controls.commandInFlight,
                  decoration: const InputDecoration(
                    labelText: '灯数量',
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  onChanged: controls.setLampCountText,
                ),
              ),
              FilledButton(
                onPressed: rgbOn &&
                        controls.lampCountValid &&
                        !controls.commandInFlight
                    ? () => unawaited(controls.applyRgb())
                    : null,
                child: const Text('应用'),
              ),
              if (controls.lampCountMessage != null)
                Text(controls.lampCountMessage!, style: muted),
              if (connected && !connection.hasRgbLed)
                Text('当前设备不支持 RGB 灯', style: muted),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            keyOn
                ? '按键 ${controls.keyLabel ?? '等待按键'}'
                : connected
                ? '当前设备没有按键'
                : '按键',
            style: keyOn ? theme.textTheme.bodyMedium : muted,
          ),
        ],
      ),
    );
  }

  Widget _swatch(int red, int green, int blue) {
    return Container(
      width: 28,
      height: 28,
      color: Color.fromARGB(255, red, green, blue),
    );
  }

  Widget _channel(
    String name,
    int value,
    bool enabled,
    ValueChanged<int> onChanged,
  ) {
    return Row(
      children: [
        SizedBox(width: 16, child: Text(name)),
        Expanded(
          child: Slider(
            value: value.toDouble(),
            max: 255,
            label: '$value',
            onChanged: enabled ? (next) => onChanged(next.round()) : null,
          ),
        ),
      ],
    );
  }
}

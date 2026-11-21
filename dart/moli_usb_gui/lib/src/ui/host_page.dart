import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../connection/capability_controller.dart';
import '../connection/connection_controller.dart';
import '../content/content_controller.dart';
import '../transport/transport_factory.dart';
import 'capability_panel.dart';
import 'content_panel.dart';

class HostPage extends StatefulWidget {
  const HostPage({super.key, this.controller});

  /// Injected by tests. The running app builds one from the platform factory.
  final ConnectionController? controller;

  @override
  State<HostPage> createState() => _HostPageState();
}

class _HostPageState extends State<HostPage> {
  late final ConnectionController _controller;
  late final ContentController _content;
  late final CapabilityController _controls;
  late final bool _ownsController;
  final ScrollController _logScroll = ScrollController();
  int _logLength = 0;

  @override
  void initState() {
    super.initState();
    final injected = widget.controller;
    if (injected != null) {
      _controller = injected;
      _ownsController = false;
    } else {
      _controller = ConnectionController.fromBinding(createHostTransport());
      _ownsController = true;
    }
    _content = ContentController(connection: _controller);
    _controls = CapabilityController(
      connection: _controller,
      contentSending: () => _content.sending,
    );
    _controller.addListener(_onChanged);
    _content.addListener(_onChanged);
    _controls.addListener(_onChanged);
    unawaited(_controller.refresh());
  }

  void _onChanged() {
    if (!mounted) return;
    final logLength = _controller.log.length;
    final scrollLog = logLength != _logLength;
    _logLength = logLength;
    setState(() {});
    if (!scrollLog) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_logScroll.hasClients) return;
      _logScroll.jumpTo(_logScroll.position.maxScrollExtent);
    });
  }

  @override
  void dispose() {
    _controller.removeListener(_onChanged);
    _content.removeListener(_onChanged);
    _controls.removeListener(_onChanged);
    _controls.dispose();
    _content.dispose();
    if (_ownsController) _controller.dispose();
    _logScroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = _controller;
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('MSU2 屏幕助手'),
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: Wrap(
              spacing: 8,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                SizedBox(
                  width: 280,
                  child: DropdownButton<String>(
                    value: controller.selectedKey,
                    isExpanded: true,
                    hint: const Text('选择设备'),
                    items: [
                      for (final candidate in controller.candidates)
                        DropdownMenuItem<String>(
                          value: candidate.transport.transportKey,
                          child: Text(
                            candidate.transport.displayName,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: controller.dropdownsEnabled
                        ? controller.selectCandidate
                        : null,
                  ),
                ),
                OutlinedButton(
                  onPressed: controller.phase == ConnectionPhase.connecting ||
                          controller.phase == ConnectionPhase.disconnecting
                      ? null
                      : () => unawaited(controller.refresh()),
                  child: const Text('刷新'),
                ),
                if (controller.canRequestPort)
                  OutlinedButton(
                    onPressed: controller.phase == ConnectionPhase.connecting ||
                            controller.phase == ConnectionPhase.disconnecting
                        ? null
                        : () => unawaited(controller.requestGrantedPort()),
                    child: const Text('添加设备'),
                  ),
                SizedBox(
                  width: 140,
                  child: DropdownButton<String>(
                    value: controller.profileName,
                    isExpanded: true,
                    items: [
                      for (final choice in ConnectionController.profileChoices)
                        DropdownMenuItem<String>(
                          value: choice.profile.name,
                          child: Text(choice.label),
                        ),
                    ],
                    onChanged: controller.dropdownsEnabled
                        ? (value) {
                            if (value != null) controller.selectProfile(value);
                          }
                        : null,
                  ),
                ),
                FilledButton(
                  onPressed: controller.connectEnabled
                      ? () => unawaited(_connect())
                      : null,
                  child: const Text('连接'),
                ),
                FilledButton(
                  onPressed: controller.disconnectEnabled
                      ? () {
                          _content.prepareDisconnect();
                          unawaited(controller.disconnect());
                        }
                      : null,
                  child: const Text('断开'),
                ),
                Text(controller.statusText),
              ],
            ),
          ),
          if (controller.grantedPortsHint != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Text(
                controller.grantedPortsHint!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 280),
            child: SingleChildScrollView(
              child: CapabilityPanel(
                connection: controller,
                controls: _controls,
              ),
            ),
          ),
          Expanded(
            child: ContentPanel(
              connection: controller,
              content: _content,
            ),
          ),
          if (controller.geometryWarning != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              child: Text(
                controller.geometryWarning!,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.error,
                ),
              ),
            ),
          SizedBox(
            height: 180,
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border(
                  top: BorderSide(color: theme.dividerColor),
                ),
              ),
              child: controller.log.isEmpty
                  ? const Padding(
                      padding: EdgeInsets.all(12),
                      child: Text('日志'),
                    )
                  : ListView.builder(
                      controller: _logScroll,
                      padding: const EdgeInsets.all(12),
                      itemCount: controller.log.length,
                      itemBuilder: (context, index) {
                        return SelectableText(
                          controller.log[index],
                          style: theme.textTheme.bodySmall,
                        );
                      },
                    ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _connect() async {
    try {
      await _controller.connect();
    } on StateError {
      // A second connect is refused. The button is disabled in that phase.
    }
  }
}

import 'package:material_ui/material_ui.dart';

class LogPanel extends StatefulWidget {
  const LogPanel({super.key, required this.lines, required this.onClear});

  /// Bounded history in emission order, with timestamps already attached.
  final List<String> lines;
  final VoidCallback onClear;

  @override
  State<LogPanel> createState() => _LogPanelState();
}

class _LogPanelState extends State<LogPanel> {
  final TextEditingController _text = TextEditingController();
  final ScrollController _scroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _syncText();
  }

  @override
  void didUpdateWidget(covariant LogPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (_syncText()) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _scroll.hasClients) _scroll.jumpTo(0);
      });
    }
  }

  bool _syncText() {
    final text = widget.lines.reversed.join('\n');
    if (_text.text == text) return false;
    _text.value = TextEditingValue(
      text: text,
      selection: const TextSelection.collapsed(offset: 0),
    );
    return true;
  }

  @override
  void dispose() {
    _text.dispose();
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: theme.dividerColor)),
      ),
      child: TextField(
        controller: _text,
        scrollController: _scroll,
        readOnly: true,
        expands: true,
        minLines: null,
        maxLines: null,
        textAlignVertical: TextAlignVertical.top,
        style: theme.textTheme.bodySmall,
        decoration: const InputDecoration(
          labelText: '日志',
          floatingLabelBehavior: FloatingLabelBehavior.always,
          alignLabelWithHint: true,
          border: InputBorder.none,
          contentPadding: EdgeInsets.all(12),
        ),
        contextMenuBuilder: (context, editable) {
          return AdaptiveTextSelectionToolbar.buttonItems(
            anchors: editable.contextMenuAnchors,
            buttonItems: [
              ...editable.contextMenuButtonItems,
              ContextMenuButtonItem(
                label: '清空',
                onPressed: () {
                  editable.hideToolbar();
                  widget.onClear();
                },
              ),
            ],
          );
        },
      ),
    );
  }
}

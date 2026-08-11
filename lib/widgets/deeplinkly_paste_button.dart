import 'dart:io' show Platform;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// How the system paste button labels itself.
enum DeeplinklyPasteButtonDisplayMode { iconAndLabel, iconOnly, labelOnly }

/// The button's corner treatment.
///
/// UIKit offers named styles rather than an arbitrary radius, so this mirrors
/// the platform rather than inventing a radius the control cannot honour.
enum DeeplinklyPasteButtonCornerStyle { capsule, small, medium, large }

/// A system **Paste** button that recovers a deferred deep link without
/// triggering iOS's "Pasted from…" banner.
///
/// iOS has no install-referrer API, so a link tapped before the app was
/// installed survives the App Store trip on the pasteboard. Reading it
/// automatically works, but shows the system paste banner. `UIPasteControl`
/// (iOS 16+) is the alternative: because the user taps the button themselves,
/// iOS treats that as the grant and **no banner appears**.
///
/// ```dart
/// DeeplinklyPasteButton(
///   width: 140,
///   height: 44,
///   onPasted: (handled) {
///     if (handled) setState(() => _showButton = false);
///   },
///   fallback: const SizedBox.shrink(),
/// )
/// ```
///
/// The recovered link arrives on `FlutterDeeplinkly.instance.deepLinkStream`
/// like any other deep link — [onPasted] only reports whether the pasted
/// content was one of your links, so you can dismiss the button or explain that
/// it was not.
///
/// Renders [fallback] on Android, on iOS below 16, and on any platform where
/// the control is unavailable. [fallback] defaults to an empty box, so it is
/// safe to place unconditionally.
class DeeplinklyPasteButton extends StatefulWidget {
  const DeeplinklyPasteButton({
    super.key,
    this.width = 140,
    this.height = 44,
    this.onPasted,
    this.fallback,
    this.displayMode = DeeplinklyPasteButtonDisplayMode.iconAndLabel,
    this.cornerStyle = DeeplinklyPasteButtonCornerStyle.capsule,
    this.backgroundColor,
    this.foregroundColor,
  });

  /// Width of the button's box.
  final double width;

  /// Height of the button's box.
  final double height;

  /// Called after a tap with whether the pasted content was a Deeplinkly link
  /// for one of your configured domains.
  final void Function(bool handled)? onPasted;

  /// Shown where the system control is unavailable. Defaults to an empty box.
  final Widget? fallback;

  /// Whether the button shows its icon, its label, or both.
  final DeeplinklyPasteButtonDisplayMode displayMode;

  /// Corner treatment. Defaults to the system's capsule shape.
  final DeeplinklyPasteButtonCornerStyle cornerStyle;

  /// Button fill. Defaults to the system appearance.
  ///
  /// Restyling a paste control heavily is a review risk — Apple expects it to
  /// read as a system paste affordance.
  final Color? backgroundColor;

  /// Icon and label colour. Defaults to the system appearance.
  final Color? foregroundColor;

  @override
  State<DeeplinklyPasteButton> createState() => _DeeplinklyPasteButtonState();
}

class _DeeplinklyPasteButtonState extends State<DeeplinklyPasteButton> {
  MethodChannel? _channel;

  /// True only where a real `UIPasteControl` can exist. Everything else takes
  /// the fallback without ever creating a platform view.
  bool get _isSupported {
    if (kIsWeb) return false;
    try {
      return Platform.isIOS;
    } catch (_) {
      return false;
    }
  }

  void _onPlatformViewCreated(int id) {
    final channel = MethodChannel('deeplinkly/paste_button_$id');
    _channel = channel;
    channel.setMethodCallHandler((call) async {
      if (call.method == 'onPasteResult') {
        final args = (call.arguments as Map?) ?? const {};
        widget.onPasted?.call(args['handled'] == true);
      }
      return null;
    });
  }

  @override
  void dispose() {
    _channel?.setMethodCallHandler(null);
    _channel = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_isSupported) {
      return widget.fallback ?? const SizedBox.shrink();
    }

    return SizedBox(
      width: widget.width,
      height: widget.height,
      child: UiKitView(
        viewType: 'deeplinkly/paste_button',
        creationParams: <String, dynamic>{
          'displayMode': widget.displayMode.name,
          'cornerStyle': widget.cornerStyle.name,
          if (widget.backgroundColor != null)
            'backgroundColor': _argb(widget.backgroundColor!),
          if (widget.foregroundColor != null)
            'foregroundColor': _argb(widget.foregroundColor!),
        },
        creationParamsCodec: const StandardMessageCodec(),
        onPlatformViewCreated: _onPlatformViewCreated,
      ),
    );
  }
}

/// Packs a [Color] into the ARGB int the platform side unpacks.
///
/// Built from the float components rather than the deprecated `.value`, which
/// newer Flutter versions warn on.
int _argb(Color color) {
  int channel(double v) => (v * 255.0).round().clamp(0, 255);
  return (channel(color.a) << 24) |
      (channel(color.r) << 16) |
      (channel(color.g) << 8) |
      channel(color.b);
}

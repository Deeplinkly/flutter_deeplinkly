import 'package:flutter/services.dart';

class FlutterDeeplinkly {
  static const _channel = MethodChannel('deeplinkly/channel');

  static void Function(Map<dynamic, dynamic> params)? _onResolvedCallback;

  static void init() {
    print("🔥 FlutterDeeplinkly.init() called");
    _channel.setMethodCallHandler((call) async {
      if (call.method == "onDeepLink") {
        final args = Map<dynamic, dynamic>.from(call.arguments);
        print("✅ Method call received: onDeepLink");
        print("📦 Payload: $args");

        _onResolvedCallback?.call(args);
      }
    });
  }

  static void onResolved(void Function(Map<dynamic, dynamic> params) callback) {
    _onResolvedCallback = callback;
  }
}

import 'package:flutter/services.dart';
import 'package:flutter_deeplinkly/models/deeplinkly.dart';

class FlutterDeeplinkly {
  static const _channel = MethodChannel('deeplinkly/channel');

  static void Function(Map<dynamic, dynamic> params)? _onResolvedCallback;

  static void init() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == "onDeepLink") {
        final args = Map<dynamic, dynamic>.from(call.arguments);
        _onResolvedCallback?.call(args);
      }
    });
  }

  static void onResolved(void Function(Map<dynamic, dynamic> params) callback) {
    _onResolvedCallback = callback;
  }

  static Future<DeeplinklyResult> generateLink({
    required DeeplinklyContent content,
    required DeeplinklyLinkOptions options,
  }) async {
    try {
      final payload = {
        'content': content.toJson(),
        'options': options.toJson(),
      };

      final rawResult = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'generateLink',
        payload,
      );

      if (rawResult == null) {
        return DeeplinklyResult(
          success: false,
          errorMessage: 'No response from native layer',
          errorCode: 'NULL_NATIVE_RESPONSE',
        );
      }

      return DeeplinklyResult.fromMap(rawResult);
    } catch (e) {
      print("generateLink failed: $e");
      return DeeplinklyResult(
        success: false,
        errorMessage: e.toString(),
        errorCode: 'PLATFORM_EXCEPTION',
      );
    }
  }
}

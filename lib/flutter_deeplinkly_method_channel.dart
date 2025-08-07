import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_deeplinkly_platform_interface.dart';

/// An implementation of [FlutterDeeplinklyPlatform] that uses method channels.
class MethodChannelFlutterDeeplinkly extends FlutterDeeplinklyPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_deeplinkly');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  Future<void> setCustomUserId(String userId) async {
    await methodChannel.invokeMethod("setCustomUserId", {"user_id": userId});
  }

  Future<void> setTrackingEnabled({bool enabled = true}) async {
    await methodChannel.invokeMethod("disableTracking", {"disabled": !enabled});
  }
}

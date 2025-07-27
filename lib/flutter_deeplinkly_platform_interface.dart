import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_deeplinkly_method_channel.dart';

abstract class FlutterDeeplinklyPlatform extends PlatformInterface {
  /// Constructs a FlutterDeeplinklyPlatform.
  FlutterDeeplinklyPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterDeeplinklyPlatform _instance = MethodChannelFlutterDeeplinkly();

  /// The default instance of [FlutterDeeplinklyPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterDeeplinkly].
  static FlutterDeeplinklyPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterDeeplinklyPlatform] when
  /// they register themselves.
  static set instance(FlutterDeeplinklyPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}

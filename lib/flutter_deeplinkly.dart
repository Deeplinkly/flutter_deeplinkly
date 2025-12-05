import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_deeplinkly/models/deeplinkly.dart';

/// Stream-based deep link controller
class _DeepLinkController {
  final _controller = StreamController<Map<dynamic, dynamic>>.broadcast();
  
  Stream<Map<dynamic, dynamic>> get stream => _controller.stream;
  
  void add(Map<dynamic, dynamic> data) {
    if (!_controller.isClosed) {
      _controller.add(data);
    }
  }
  
  void close() {
    _controller.close();
  }
}

class FlutterDeeplinkly with WidgetsBindingObserver {
  static const _channel = MethodChannel('deeplinkly/channel');

  static final FlutterDeeplinkly _instance = FlutterDeeplinkly._internal();
  factory FlutterDeeplinkly() => _instance;
  FlutterDeeplinkly._internal();

  static FlutterDeeplinkly get instance => _instance;

  final _deepLinkController = _DeepLinkController();
  static bool _isInitialized = false;
  static bool _isFlutterReady = false;
  static bool _isLifecycleObserving = false;
  AppLifecycleState? _currentLifecycleState;

  /// Stream of deep link events
  /// Multiple listeners can subscribe to this stream
  Stream<Map<dynamic, dynamic>> get deepLinkStream => _deepLinkController.stream;

  /// Initialize the plugin and set up method channel handler
  /// Must be called before accessing deepLinkStream
  static void init() {
    if (_isInitialized) {
      return; // Already initialized
    }
    
    final instance = FlutterDeeplinkly.instance;
    
    // Set up lifecycle observer
    if (!_isLifecycleObserving) {
      WidgetsBinding.instance.addObserver(instance);
      _isLifecycleObserving = true;
    }
    
    _channel.setMethodCallHandler((call) async {
      try {
        if (call.method == "onDeepLink") {
          final args = Map<dynamic, dynamic>.from(call.arguments);
          
          // Add to stream - all listeners will receive it
          instance._deepLinkController.add(args);
        }
      } catch (e) {
        // Log error but don't crash
        print('FlutterDeeplinkly: Error handling method call: $e');
      }
    });
    
    _isInitialized = true;
    
    // Mark Flutter as ready to receive deep links
    _markFlutterReady();
  }

  /// Mark Flutter as ready and process any queued deep links
  static Future<void> _markFlutterReady() async {
    if (_isFlutterReady) {
      return;
    }
    
    try {
      await _channel.invokeMethod('flutterReady');
      _isFlutterReady = true;
    } catch (e) {
      print('FlutterDeeplinkly: Error marking Flutter as ready: $e');
    }
  }

  /// Lifecycle observer implementation
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _currentLifecycleState = state;
    
    // Notify native layer of lifecycle changes
    try {
      _channel.invokeMethod('onLifecycleChange', {
        'state': state.toString().split('.').last, // e.g., 'resumed', 'paused'
      });
    } catch (e) {
      print('FlutterDeeplinkly: Error notifying lifecycle change: $e');
    }
    
    // Mark Flutter as ready when app resumes
    if (state == AppLifecycleState.resumed && !_isFlutterReady) {
      _markFlutterReady();
    }
  }

  /// Get current app lifecycle state
  AppLifecycleState? get currentLifecycleState => _currentLifecycleState;

  /// Check if app is in foreground
  bool get isInForeground => _currentLifecycleState == AppLifecycleState.resumed;

  static Future<Map<String, String>> getInstallAttribution() async {
    try {
      final result = await _channel.invokeMapMethod<String, String>('getInstallAttribution');
      return Map<String, String>.from(result ?? const {});
    } catch (e) {
      print('FlutterDeeplinkly: Error getting install attribution: $e');
      return const {};
    }
  }

  /// Register callback for deep link events (deprecated - use deepLinkStream instead)
  /// This is kept for backward compatibility but uses stream internally
  @Deprecated('Use deepLinkStream.listen() instead for better flexibility')
  static void onResolved(void Function(Map<dynamic, dynamic> params) callback) {
    if (!_isInitialized) {
      throw StateError(
        'FlutterDeeplinkly.init() must be called before onResolved(). '
        'Call init() in your main() function before runApp().'
      );
    }
    
    // Subscribe to stream and call callback
    FlutterDeeplinkly.instance.deepLinkStream.listen((data) {
      try {
        callback(data);
      } catch (e) {
        print('FlutterDeeplinkly: Error in callback: $e');
      }
    });
    
    // Ensure Flutter is marked as ready
    if (!_isFlutterReady) {
      _markFlutterReady();
    }
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
      final rawResult = await _channel.invokeMethod<Map<dynamic, dynamic>>('generateLink', payload);
      if (rawResult == null) {
        return DeeplinklyResult(
          success: false,
          errorMessage: 'No response from native layer',
          errorCode: 'NULL_NATIVE_RESPONSE',
        );
      }

      return DeeplinklyResult.fromMap(rawResult);
    } catch (e) {
      return DeeplinklyResult(
        success: false,
        errorMessage: e.toString(),
        errorCode: 'PLATFORM_EXCEPTION',
      );
    }
  }

  /// Dispose resources (called automatically, but can be called manually)
  void dispose() {
    if (_isLifecycleObserving) {
      WidgetsBinding.instance.removeObserver(this);
      _isLifecycleObserving = false;
    }
    _deepLinkController.close();
  }
}

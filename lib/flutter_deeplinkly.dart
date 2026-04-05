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
  static const int _maxEventNameLength = 64;
  static const int _maxEventParamsCount = 25;
  static const int _maxEventParamKeyLength = 64;
  static const int _maxEventParamValueLength = 256;

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
        // Silently handle error without crashing
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
      // Silently handle error
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
      // Silently handle error
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
      return const {};
    }
  }

  /// Stable Deeplinkly device id for this install (same as `deeplinkly_device_id` / `X-Deeplinkly-User-Id` on the API).
  static Future<String> getDeeplinklyId() async {
    try {
      final id = await _channel.invokeMethod<String>('getDeeplinklyId');
      return id ?? '';
    } catch (e) {
      return '';
    }
  }

  /// Sets your app’s user id (`custom_user_id`) for enrichment and backend user linking.
  static Future<void> setUserId(String? userId) async {
    try {
      await _channel.invokeMethod<void>('setUserId', {'user_id': userId});
    } catch (e) {
      // Match other fire-and-forget SDK calls
    }
  }

  /// Logs a custom event with optional custom parameters.
  /// Returns true if accepted by native layer and backend.
  static Future<bool> logEvent(
    String eventName, {
    Map<String, Object>? parameters,
  }) async {
    final normalized = eventName.trim();
    if (normalized.isEmpty || normalized.length > _maxEventNameLength) {
      return false;
    }
    final params = parameters ?? const <String, Object>{};
    if (params.length > _maxEventParamsCount) {
      return false;
    }
    for (final entry in params.entries) {
      final key = entry.key.trim();
      if (key.isEmpty || key.length > _maxEventParamKeyLength) {
        return false;
      }
      final value = entry.value;
      if (value is String && value.length > _maxEventParamValueLength) {
        return false;
      }
      if (value is! String &&
          value is! num &&
          value is! bool &&
          value is! List &&
          value is! Map) {
        return false;
      }
    }
    try {
      final ok = await _channel.invokeMethod<bool>('logEvent', {
        'event_name': normalized,
        'parameters': params,
      });
      return ok ?? false;
    } catch (e) {
      return false;
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
        // Silently handle error
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

  /// Enable or disable debug logging
  /// When enabled, all Logger.d() calls will be printed to console
  /// Defaults to false (no logging) for production
  static Future<void> setDebugMode(bool enabled) async {
    try {
      await _channel.invokeMethod('setDebugMode', {'enabled': enabled});
    } catch (e) {
      // Silently handle error
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

enum DeeplinklyEventType {
  login,
  signup,
  logout,
  purchase,
  addToCart,
  removeFromCart,
  beginCheckout,
  addPaymentInfo,
  viewItem,
  viewItemList,
  search,
  share,
  invite,
  appOpen,
  sessionStart,
  screenView,
  levelUp,
  tutorialComplete,
  refund,
}

extension DeeplinklyEventTypeName on DeeplinklyEventType {
  String get eventName {
    switch (this) {
      case DeeplinklyEventType.login:
        return 'login';
      case DeeplinklyEventType.signup:
        return 'signup';
      case DeeplinklyEventType.logout:
        return 'logout';
      case DeeplinklyEventType.purchase:
        return 'purchase';
      case DeeplinklyEventType.addToCart:
        return 'add_to_cart';
      case DeeplinklyEventType.removeFromCart:
        return 'remove_from_cart';
      case DeeplinklyEventType.beginCheckout:
        return 'begin_checkout';
      case DeeplinklyEventType.addPaymentInfo:
        return 'add_payment_info';
      case DeeplinklyEventType.viewItem:
        return 'view_item';
      case DeeplinklyEventType.viewItemList:
        return 'view_item_list';
      case DeeplinklyEventType.search:
        return 'search';
      case DeeplinklyEventType.share:
        return 'share';
      case DeeplinklyEventType.invite:
        return 'invite';
      case DeeplinklyEventType.appOpen:
        return 'app_open';
      case DeeplinklyEventType.sessionStart:
        return 'session_start';
      case DeeplinklyEventType.screenView:
        return 'screen_view';
      case DeeplinklyEventType.levelUp:
        return 'level_up';
      case DeeplinklyEventType.tutorialComplete:
        return 'tutorial_complete';
      case DeeplinklyEventType.refund:
        return 'refund';
    }
  }
}

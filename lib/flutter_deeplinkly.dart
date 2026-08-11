import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_deeplinkly/models/deeplinkly.dart';

export 'package:flutter_deeplinkly/widgets/deeplinkly_paste_button.dart';

/// How much the SDK may report about a device.
///
/// Each level is a strict subset of the one above it. Set with
/// [FlutterDeeplinkly.setAttributionLevel].
/// Levels gate *reporting* only. Deep links resolve and are delivered to your
/// app at every level, including [DeeplinklyAttributionLevel.none]: resolving a
/// link sends its id and nothing describing the device, whatever the level.
enum DeeplinklyAttributionLevel {
  /// Everything the SDK collects, including the high-entropy device signals.
  /// The default.
  full,

  /// Drops the high-entropy hardware signals — screen geometry, pixel ratio,
  /// core count, device model, and the Android advertising ID and Android ID.
  /// Keeps the coarse context campaign reporting actually uses: locale,
  /// timezone, OS and app version.
  reduced,

  /// Only what a deep link needs to function: the install's own id, the app
  /// build, and the link being reported on. Nothing describing the device.
  minimal,

  /// No enrichment is sent at all. Deep links still resolve and are still
  /// delivered to your app — this suppresses reporting, not functionality.
  none,
}

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

    // Notify native layer of lifecycle changes.
    //
    // invokeMethod returns a Future, so a synchronous try/catch around it never
    // sees a rejection - the error escaped as an unhandled async error instead.
    // Hosts that funnel those into a crash reporter (Crashlytics via
    // PlatformDispatcher.onError) recorded a fatal crash on every foreground or
    // background transition. The rejection has to be caught on the Future.
    unawaited(
      _channel
          .invokeMethod('onLifecycleChange', {
            'state': state.name, // e.g., 'resumed', 'paused'
          })
          .catchError((Object _) => null),
    );

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
  ///
  /// Returns true if accepted by the native layer and the backend.
  ///
  /// The rules are enforced natively rather than here, so that a native-only
  /// integration gets the same answer this one does:
  ///
  /// - event name: non-empty after trimming, at most 64 characters
  /// - at most 25 parameters
  /// - parameter keys: non-empty after trimming, at most 64 characters, and
  ///   may not start with `_dl_` (reserved for the metadata the SDK attaches
  ///   to every event, which the backend excludes from the parameter budget)
  /// - `String` values: at most 256 characters
  /// - `List`/`Map` values: stored as compact JSON, and it is that encoded
  ///   form the 256 limit applies to; values that will not encode are rejected
  /// - any other type than `String`, `num`, `bool`, `List` or `Map` is rejected
  ///
  /// A rejected event returns false and sends nothing.
  static Future<bool> logEvent(
    String eventName, {
    Map<String, Object>? parameters,
  }) async {
    try {
      final ok = await _channel.invokeMethod<bool>('logEvent', {
        'event_name': eventName,
        'parameters': parameters ?? const <String, Object>{},
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

  /// Turn all reporting off, or back on.
  ///
  /// The switch for a consent flow's "don't track me". While disabled the SDK
  /// sends no enrichment, no events and no error reports, and skips the iOS
  /// pasteboard read. Deep links still resolve and are still delivered to
  /// [onResolved] — the link a user tapped keeps working.
  ///
  /// Persists across launches on both platforms. Enabled by default.
  ///
  /// Wins over [setAttributionLevel]: while disabled, [getAttributionLevel]
  /// reports [DeeplinklyAttributionLevel.none] whatever level was set. Use
  /// [setAttributionLevel] instead when you need a middle ground rather than
  /// an off switch.
  static Future<bool> setTrackingEnabled(bool enabled) async {
    try {
      final ok = await _channel.invokeMethod<bool>('disableTracking', {
        'disabled': !enabled,
      });
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Restrict how much the SDK may report about this device.
  ///
  /// Use this for consent flows that need a middle ground between "track" and
  /// "don't". Each level is a strict subset of the one above it; see
  /// [DeeplinklyAttributionLevel]. Defaults to
  /// [DeeplinklyAttributionLevel.full].
  ///
  /// The level persists across launches. To start restricted before any Dart
  /// runs, set `DeeplinklyAttributionLevel` in `Info.plist` (iOS) or the
  /// `com.deeplinkly.sdk.attribution_level` manifest meta-data (Android) —
  /// enrichment can be sent during plugin registration, before this could be
  /// called.
  ///
  /// [setTrackingEnabled] still wins: disabling tracking behaves as
  /// [DeeplinklyAttributionLevel.none] whatever is set here.
  static Future<bool> setAttributionLevel(
    DeeplinklyAttributionLevel level,
  ) async {
    try {
      final ok = await _channel.invokeMethod<bool>('setAttributionLevel', {
        'level': level.name,
      });
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// The attribution level currently in force.
  ///
  /// Reports [DeeplinklyAttributionLevel.none] when tracking is disabled, even
  /// if a higher level was set.
  static Future<DeeplinklyAttributionLevel> getAttributionLevel() async {
    try {
      final raw = await _channel.invokeMethod<String>('getAttributionLevel');
      return DeeplinklyAttributionLevel.values.firstWhere(
        (l) => l.name == raw,
        orElse: () => DeeplinklyAttributionLevel.full,
      );
    } catch (_) {
      return DeeplinklyAttributionLevel.full;
    }
  }

  /// Turn the automatic pasteboard read on or off.
  ///
  /// iOS-only — Android uses the Play Install Referrer and needs no clipboard
  /// access. The SDK reads the pasteboard once on first launch to recover a
  /// link tapped before install, and iOS shows its "Pasted from…" banner for
  /// that read.
  ///
  /// **On by default.** The SDK checks whether a URL is on the clipboard
  /// without reading anything, so a user with plain text or an empty clipboard
  /// sees no banner. Any URL does trigger it, though — the SDK discards links
  /// that are not yours only after the prompt has shown.
  ///
  /// To turn it **off**, do it in `Info.plist` rather than here — this call
  /// arrives after plugin registration, by which point the read has already
  /// happened:
  ///
  /// ```xml
  /// <key>DeeplinklyCheckPasteboardOnInstall</key>
  /// <false/>
  /// ```
  ///
  /// [DeeplinklyPasteButton] is the no-banner alternative and is unaffected by
  /// this setting — the user's tap is the grant. Turning the automatic read
  /// off and shipping the button is a perfectly good configuration.
  ///
  /// Turning it **on** from Dart performs the read immediately rather than
  /// waiting for a next launch the pasteboard may not survive to. Pass
  /// [checkNow] as `false` to suppress that — useful with
  /// [willShowPasteboardBanner] if you want to explain the prompt first.
  ///
  /// Returns `false` on Android, where there is nothing to enable.
  static Future<bool> setCheckPasteboardOnInstall(
    bool enabled, {
    bool checkNow = true,
  }) async {
    try {
      final ok = await _channel
          .invokeMethod<bool>('setCheckPasteboardOnInstall', {
            'enabled': enabled,
            'check_now': checkNow,
          });
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Whether reading the pasteboard right now would show the system banner.
  ///
  /// True only when the automatic read is enabled, has not already happened,
  /// tracking is on, and there is plausibly a URL to read. Checking this costs
  /// nothing and shows no banner itself, so it is safe to call on a first-run
  /// screen to decide whether to explain the prompt before it appears.
  ///
  /// Always false on Android.
  static Future<bool> willShowPasteboardBanner() async {
    try {
      final willShow = await _channel.invokeMethod<bool>(
        'willShowPasteboardBanner',
      );
      return willShow ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Perform the pasteboard read now, if it is enabled and has not run yet.
  ///
  /// Pair with [willShowPasteboardBanner] to show your own explanation first.
  /// Does nothing when the read is disabled or already done.
  ///
  /// Always `false` on Android, which has no pasteboard path — the Play
  /// Install Referrer covers deferred deep linking there.
  static Future<bool> checkPasteboardNow() async {
    try {
      final ok = await _channel.invokeMethod<bool>('checkPasteboardNow');
      return ok ?? false;
    } catch (_) {
      return false;
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

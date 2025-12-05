import 'package:flutter/services.dart';

/// Mock method channel for testing Flutter-Android communication
class MockMethodChannel {
  final String channelName;
  final Map<String, Function> methodHandlers = {};
  final List<MethodCall> methodCalls = [];
  final Map<String, dynamic> methodResults = {};
  bool isFlutterReady = false;

  MockMethodChannel(this.channelName);

  /// Set up a handler for a specific method
  void setMethodCallHandler(Function handler) {
    // Store handler for later use
  }

  /// Simulate a method call from native side
  Future<void> invokeMethodFromNative(String method, [dynamic arguments]) async {
    methodCalls.add(MethodCall(method, arguments));
    
    // Check if we have a predefined result
    if (methodResults.containsKey(method)) {
      return methodResults[method];
    }
    
    // Default handlers
    switch (method) {
      case 'flutterReady':
        isFlutterReady = true;
        return true;
      case 'onLifecycleChange':
        return true;
      default:
        return null;
    }
  }

  /// Set a predefined result for a method call
  void setMethodResult(String method, dynamic result) {
    methodResults[method] = result;
  }

  /// Clear all recorded method calls
  void clearMethodCalls() {
    methodCalls.clear();
  }

  /// Get all method calls for a specific method
  List<MethodCall> getMethodCalls(String method) {
    return methodCalls.where((call) => call.method == method).toList();
  }

  /// Simulate Flutter not being ready
  void setFlutterNotReady() {
    isFlutterReady = false;
  }

  /// Simulate deep link from native side
  Future<void> sendDeepLink(Map<dynamic, dynamic> data) async {
    await invokeMethodFromNative('onDeepLink', data);
  }
}

/// Test helper to create mock deep link data
class MockDeepLinkData {
  static Map<dynamic, dynamic> create({
    String? clickId,
    String? code,
    String? utmSource,
    String? utmMedium,
    String? utmCampaign,
    Map<String, dynamic>? additionalParams,
  }) {
    final data = <String, dynamic>{};
    
    if (clickId != null) data['click_id'] = clickId;
    if (code != null) data['code'] = code;
    if (utmSource != null) data['utm_source'] = utmSource;
    if (utmMedium != null) data['utm_medium'] = utmMedium;
    if (utmCampaign != null) data['utm_campaign'] = utmCampaign;
    
    if (additionalParams != null) {
      data.addAll(additionalParams);
    }
    
    return data;
  }

  static Map<dynamic, dynamic> createWithEnrichment({
    required String clickId,
    Map<String, dynamic>? enrichment,
  }) {
    final data = create(clickId: clickId);
    if (enrichment != null) {
      data.addAll(enrichment);
    }
    return data;
  }
}


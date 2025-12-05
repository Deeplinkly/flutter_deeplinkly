import 'package:flutter/widgets.dart';

/// Simulator for app lifecycle states during testing
class LifecycleSimulator {
  static WidgetsBinding? get binding => WidgetsBinding.instance;

  /// Simulate app going to background
  static void simulatePaused() {
    binding?.handleAppLifecycleStateChanged(AppLifecycleState.paused);
  }

  /// Simulate app going to foreground
  static void simulateResumed() {
    binding?.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
  }

  /// Simulate app being inactive
  static void simulateInactive() {
    binding?.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
  }

  /// Simulate app being detached
  static void simulateDetached() {
    binding?.handleAppLifecycleStateChanged(AppLifecycleState.detached);
  }

  /// Simulate app lifecycle sequence: resumed -> paused -> resumed
  static Future<void> simulateBackgroundTransition({
    Duration pauseDuration = const Duration(milliseconds: 100),
  }) async {
    simulatePaused();
    await Future.delayed(pauseDuration);
    simulateResumed();
  }

  /// Get current lifecycle state
  static AppLifecycleState? getCurrentState() {
    return binding?.lifecycleState;
  }
}


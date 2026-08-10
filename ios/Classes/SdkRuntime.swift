// SdkRuntime.swift
import Flutter
import Foundation

/// Readiness gate between the native side and Dart.
///
/// The native side can produce a deep link well before Dart can receive one:
/// the pasteboard is read during plugin registration, which always happens
/// before `FlutterDeeplinkly.init()` has registered its `onDeepLink` handler.
/// Invoking the channel at that point drops the payload silently, which is why
/// deferred deep links have to be buffered until Dart says it is listening.
///
/// Android has had this since the queue rework (`core/SdkRuntime.kt`); iOS did
/// not, so every early delivery was lost.
enum SdkRuntime {
    private struct Buffered {
        let method: String
        let args: Any?
        /// Runs once the message has actually reached Dart, so callers can hold
        /// durable state (the resolve queue) until delivery is real rather than
        /// merely buffered.
        let onDelivered: (() -> Void)?
    }

    private static let lock = NSLock()
    private static var ready = false
    private static var channel: FlutterMethodChannel?
    private static var pending: [Buffered] = []

    /// Cap the buffer so a host app that never calls `init()` cannot grow it
    /// without bound. In practice it holds at most one deep link.
    private static let maxPending = 20

    static func isFlutterReady() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return ready && channel != nil
    }

    /// Called when Dart reports it has a listener attached; flushes anything
    /// that arrived first.
    static func setFlutterReady(_ channel: FlutterMethodChannel) {
        lock.lock()
        self.channel = channel
        ready = true
        let flush = pending
        pending.removeAll()
        lock.unlock()

        Logger.d("Flutter ready; flushing \(flush.count) buffered message(s)")
        for item in flush {
            deliver(channel: channel, method: item.method, args: item.args)
            item.onDelivered?()
        }
    }

    static func setFlutterNotReady() {
        lock.lock()
        ready = false
        channel = nil
        lock.unlock()
        Logger.d("Flutter marked as not ready")
    }

    /// Sends to Dart, or buffers until it is ready.
    ///
    /// - Parameter onDelivered: called once the message has reached Dart —
    ///   immediately when it is ready, at flush time otherwise.
    static func postToFlutter(
        _ channel: FlutterMethodChannel,
        method: String,
        args: Any?,
        onDelivered: (() -> Void)? = nil
    ) {
        lock.lock()
        let canSend = ready && self.channel != nil
        if !canSend {
            if pending.count >= maxPending { pending.removeFirst() }
            pending.append(Buffered(method: method, args: args, onDelivered: onDelivered))
        }
        let target = self.channel ?? channel
        lock.unlock()

        guard canSend else {
            Logger.w("Flutter not ready; buffered \(method)")
            return
        }
        deliver(channel: target, method: method, args: args)
        onDelivered?()
    }

    private static func deliver(channel: FlutterMethodChannel, method: String, args: Any?) {
        // invokeMethod must be called on the platform thread.
        if Thread.isMainThread {
            channel.invokeMethod(method, arguments: args)
        } else {
            DispatchQueue.main.async { channel.invokeMethod(method, arguments: args) }
        }
        Logger.d("Posted to Flutter: \(method)")
    }
}

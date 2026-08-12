// PasteControlFactory.swift
import Deeplinkly
import Flutter
import Foundation
import UIKit

/// Hosts a `UIPasteControl` inside the Flutter view hierarchy.
///
/// This is the banner-free half of deferred deep linking. `UIPasteControl`
/// (iOS 16+) is a system-rendered button; when the user taps it iOS hands the
/// pasteboard item straight to us and **shows no "Pasted from…" banner**,
/// because the tap itself is the grant. The trade-off is that the app has to
/// show a button and the user has to press it, so it suits a "restore where you
/// left off" affordance on a first-run screen rather than a silent read.
///
/// Exposed to Dart as `DeeplinklyPasteButton`.
///
/// On iOS below 16 the factory still returns a view, but an empty one — Dart is
/// responsible for showing its own fallback there. `PasteControlView` reports
/// its availability back over the per-view channel so Dart can decide.
class PasteControlFactory: NSObject, FlutterPlatformViewFactory {
    private let messenger: FlutterBinaryMessenger

    init(messenger: FlutterBinaryMessenger) {
        self.messenger = messenger
        super.init()
    }

    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?)
        -> FlutterPlatformView
    {
        PasteControlView(
            frame: frame,
            viewId: viewId,
            args: args as? [String: Any] ?? [:],
            messenger: messenger
        )
    }

    /// Creation params arrive as a standard-codec map.
    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        FlutterStandardMessageCodec.sharedInstance()
    }
}

class PasteControlView: NSObject, FlutterPlatformView {
    private let container: PasteResponderView
    private let viewChannel: FlutterMethodChannel

    init(
        frame: CGRect,
        viewId: Int64,
        args: [String: Any],
        messenger: FlutterBinaryMessenger
    ) {
        container = PasteResponderView(frame: frame)
        viewChannel = FlutterMethodChannel(
            name: "deeplinkly/paste_button_\(viewId)", binaryMessenger: messenger)
        super.init()

        container.onPaste = { [weak self] providers in
            Deeplinkly.handlePaste(itemProviders: providers) { handled in
                // Tell the button's owner what happened so it can dismiss
                // itself or show "that link wasn't one of ours". The resolved
                // link itself still arrives on deepLinkStream like any other.
                self?.viewChannel.invokeMethod("onPasteResult", arguments: ["handled": handled])
            }
        }

        if #available(iOS 16.0, *) {
            installPasteControl(args: args)
        } else {
            // Nothing to render. Dart shows the caller's fallback instead.
            container.isHidden = true
        }
        viewChannel.setMethodCallHandler { [weak self] call, result in
            switch call.method {
            case "isAvailable":
                if #available(iOS 16.0, *) {
                    result(true)
                } else {
                    result(false)
                }
            case "dispose":
                self?.viewChannel.setMethodCallHandler(nil)
                result(nil)
            default:
                result(FlutterMethodNotImplemented)
            }
        }
    }

    @available(iOS 16.0, *)
    private func installPasteControl(args: [String: Any]) {
        let config = UIPasteControl.Configuration()
        // Mirror the system paste affordance by default; the labels and colours
        // are Apple's to choose, and overriding them is what gets a paste
        // button rejected as misleading.
        if let displayMode = args["displayMode"] as? String {
            switch displayMode {
            case "iconOnly": config.displayMode = .iconOnly
            case "labelOnly": config.displayMode = .labelOnly
            default: config.displayMode = .iconAndLabel
            }
        }
        // UIKit offers named corner styles rather than an arbitrary radius;
        // `.fixed` reads its radius off a background configuration that
        // UIPasteControl.Configuration does not expose, so it is not offered.
        if let cornerStyle = args["cornerStyle"] as? String {
            switch cornerStyle {
            case "small": config.cornerStyle = .small
            case "medium": config.cornerStyle = .medium
            case "large": config.cornerStyle = .large
            default: config.cornerStyle = .capsule
            }
        }
        if let baseBackgroundColor = args["backgroundColor"] as? NSNumber {
            config.baseBackgroundColor = UIColor(argb: baseBackgroundColor.intValue)
        }
        if let baseForegroundColor = args["foregroundColor"] as? NSNumber {
            config.baseForegroundColor = UIColor(argb: baseForegroundColor.intValue)
        }

        let control = UIPasteControl(configuration: config)
        // The control looks for a responder that can accept the paste. Ours is
        // the container, which overrides canPaste/paste below.
        control.target = container
        control.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(control)
        NSLayoutConstraint.activate([
            control.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            control.centerYAnchor.constraint(equalTo: container.centerYAnchor),
        ])
    }

    func view() -> UIView { container }
}

/// The responder `UIPasteControl` delivers to.
///
/// `UIView` is a `UIResponder`, so overriding these two methods is all that is
/// needed — the control enables itself based on `canPaste` and calls `paste`
/// on tap.
class PasteResponderView: UIView {
    var onPaste: (([NSItemProvider]) -> Void)?

    override func canPaste(_ itemProviders: [NSItemProvider]) -> Bool {
        // Accept plain text as well as URLs. The interstitial's primary write is
        // a text/uri-list item, but its fallbacks (writeText, and execCommand on
        // Safari < 13.4) land as plain text, as do links copied before that
        // change shipped. A URL-only check would leave the button disabled for
        // exactly those users.
        itemProviders.contains {
            $0.hasItemConformingToTypeIdentifier("public.url")
                || $0.hasItemConformingToTypeIdentifier("public.plain-text")
        }
    }

    override func paste(itemProviders: [NSItemProvider]) {
        onPaste?(itemProviders)
    }
}

extension UIColor {
    /// Flutter sends colours as a packed ARGB int.
    fileprivate convenience init(argb: Int) {
        self.init(
            red: CGFloat((argb >> 16) & 0xFF) / 255.0,
            green: CGFloat((argb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(argb & 0xFF) / 255.0,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255.0
        )
    }
}

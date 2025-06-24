# Deeplinkly Flutter SDK

**Deeplinkly** is a self-hosted, privacy-conscious deep linking and deferred deep linking service — an alternative to Branch.io and AppsFlyer. This SDK enables seamless integration of Deeplinkly into your Flutter apps on both Android and iOS.

---

## 🚀 Features

- ✅ Deep linking
- ✅ Deferred deep linking
- ✅ Read API keys from native config (`AndroidManifest.xml`, `Info.plist`)
- ✅ Automatically opens the app or fallback to store
- ✅ Lightweight native plugin
- ✅ Open-source and self-hostable

---

## 📦 Installation

Add to your `pubspec.yaml`:

```yaml
dependencies:
  flutter_deeplinkly:
    git:
      url: https://github.com/sahilasopa/flutter_deeplinkly
```

---

## 🛠 Platform Setup

### Android

1. **Add Intent Filter** in `AndroidManifest.xml`:

```xml
<activity android:name=".MainActivity">
    <intent-filter android:autoVerify="true">
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <category android:name="android.intent.category.BROWSABLE"/>
        <data android:scheme="yourapp" android:host="deeplink"/>
    </intent-filter>
</activity>
```

2. **Set your Deeplinkly API key** in `AndroidManifest.xml`:

```xml
<meta-data
    android:name="DEEPLINKLY_API_KEY"
    android:value="your_api_key_here" />
```

3. **Handle fallback in browser** (optional but recommended):

Use a redirect HTML template like:

```html
<!-- redirect_android.html -->
<script>
  document.addEventListener("DOMContentLoaded", function () {
    var fallbackTimeout = setTimeout(function () {
      window.location.href = "https://play.google.com/store/apps/details?id=your.package.name";
    }, 1500);
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) clearTimeout(fallbackTimeout);
    });
    window.location.href = "yourapp://deeplink/path";
  });
</script>
```

### iOS

1. **Update `Info.plist`**:

```xml
<key>CFBundleURLTypes</key>
<array>
  <dict>
    <key>CFBundleURLSchemes</key>
    <array>
      <string>yourapp</string>
    </array>
  </dict>
</array>

<key>DEEPLINKLY_API_KEY</key>
<string>your_api_key_here</string>
```

2. **Enable Universal Links (Optional)** if using custom domain & `apple-app-site-association`.

---

## 🔧 Usage

In your `main.dart`:

```dart
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final path = await FlutterDeeplinklyPlugin.getInitialLink();

  if (path != null) {
    print('Received deep link: $path');
    // Navigate accordingly
  }

  runApp(MyApp());
}
```

---

## 🧪 Testing

- Use `adb` to simulate install referrer:

```bash
adb shell am broadcast -a com.android.vending.INSTALL_REFERRER -n your.package.name/com.google.android.gms.measurement.AppMeasurementInstallReferrerReceiver --es "referrer" "utm_source=test&utm_medium=deeplink&utm_campaign=demo"
```

---

## 🌐 Backend

This SDK expects a backend hosted with [deeplinkly.com](https://deeplinkly.com) or your own self-hosted server. Ensure the API endpoints return a JSON response with a `path` key for deferred deep linking.

---

## 📄 License

MIT © Sahil Asopa  
https://github.com/sahilasopa/flutter_deeplinkly

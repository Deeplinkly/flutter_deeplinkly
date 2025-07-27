# Deeplinkly Flutter SDK

**Deeplinkly** is a developer-first deep linking and deferred deep linking SaaS — a smarter, more affordable alternative to Branch.io and AppsFlyer.
Engineered for seamless integration and rich insights, Deeplinkly gives you more actionable data than any other platform — without the complexity or bloated pricing.
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
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="yourapp" android:host="deeplink" />
    </intent-filter>
</activity>
```

2. **Set your Deeplinkly API key** in `AndroidManifest.xml`:

```xml

<meta-data android:name="DEEPLINKLY_API_KEY" android:value="your_api_key_here" />
```

### iOS

1. **Update `Info.plist`**:

```xml

<key>CFBundleURLTypes</key><array>
<dict>
    <key>CFBundleURLSchemes</key>
    <array>
        <string>yourapp</string>
    </array>
</dict>
</array>

<key>DEEPLINKLY_API_KEY</key><string>your_api_key_here</string>
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

## Support

For support, questions, or licensing inquiries, please contact:

📧 **Sahil Asopa**  
✉️ [sahilasopa12@gmail.com](mailto:sahilasopa12@gmail.com)
---

## 📄 License

Copyright (c) 2025 Sahil Asopa

This software is proprietary and confidential. Unauthorized copying of this file, via any medium, is strictly prohibited.

All rights reserved.

This software and its source code may not be copied, modified, distributed, or used in any way without express written permission from the author or the owning company.


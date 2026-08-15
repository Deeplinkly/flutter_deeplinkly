import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Deeplinkly
  FlutterDeeplinkly.init();

  // Optional: Enable debug mode for development
  FlutterDeeplinkly.setDebugMode(true);

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  Map<dynamic, dynamic>? _deeplinkData;
  StreamSubscription<Map<dynamic, dynamic>>? _deeplinkSubscription;

  @override
  void initState() {
    super.initState();
    // Listen to deep link stream
    _deeplinkSubscription = FlutterDeeplinkly.instance.deepLinkStream.listen((
      data,
    ) {
      debugPrint('Received deep link: $data');
      if (mounted) {
        setState(() {
          _deeplinkData = data;
        });
      }
    });
  }

  @override
  void dispose() {
    _deeplinkSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Deeplinkly Example',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: MyHomePage(
        title: 'Deeplinkly Example',
        deeplinkData: _deeplinkData,
        showPasteButton: _deeplinkData == null,
      ),
    );
  }
}

class MyHomePage extends StatelessWidget {
  const MyHomePage({
    super.key,
    required this.title,
    this.deeplinkData,
    this.showPasteButton = false,
  });

  final String title;
  final Map<dynamic, dynamic>? deeplinkData;

  /// Whether to offer the deferred-link paste button. Hidden once a link has
  /// arrived, since there is nothing left to recover.
  final bool showPasteButton;

  String _formatDeeplinkData() {
    if (deeplinkData == null || deeplinkData!.isEmpty) {
      return 'No deep link data received yet.\n\nOpen a deep link to see data here.';
    }

    final buffer = StringBuffer();
    deeplinkData!.forEach((key, value) {
      buffer.writeln('$key: $value');
    });
    return buffer.toString();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: Text(title),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Deep Link Data',
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 16),
            Expanded(
              child: Card(
                elevation: 2,
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: SingleChildScrollView(
                    child: SelectableText(
                      _formatDeeplinkData(),
                      style: Theme.of(
                        context,
                      ).textTheme.bodyLarge?.copyWith(fontFamily: 'monospace'),
                    ),
                  ),
                ),
              ),
            ),
            // Deferred deep linking on iOS: a link tapped before the app was
            // installed is waiting on the pasteboard. A system paste button
            // recovers it with no "Pasted from…" banner, because the user's tap
            // is the grant. Renders nothing on Android or iOS below 16.
            if (showPasteButton) ...[
              const SizedBox(height: 16),
              Row(
                children: [
                  const Expanded(
                    child: Text('Tapped a link to get here? Restore it:'),
                  ),
                  const SizedBox(width: 8),
                  DeeplinklyPasteButton(
                    onPasted: (handled) {
                      // The link itself arrives on deepLinkStream; this only
                      // says whether what was pasted was one of ours.
                      debugPrint('Paste button handled a Deeplinkly link: $handled');
                    },
                  ),
                ],
              ),
            ],
            if (deeplinkData != null && deeplinkData!.isNotEmpty) ...[
              const SizedBox(height: 16),
              Card(
                color: Colors.green.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(12.0),
                  child: Row(
                    children: [
                      Icon(Icons.check_circle, color: Colors.green.shade700),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          'Deep link received successfully!',
                          style: TextStyle(color: Colors.green.shade900),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

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
      print('Received deep link: $data');
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
      ),
    );
  }
}

class MyHomePage extends StatelessWidget {
  const MyHomePage({super.key, required this.title, this.deeplinkData});

  final String title;
  final Map<dynamic, dynamic>? deeplinkData;

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

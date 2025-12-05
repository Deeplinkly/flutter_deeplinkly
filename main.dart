import 'package:flutter/material.dart';
import 'package:flutter_deeplinkly/flutter_deeplinkly.dart';
import 'dart:developer' as developer;

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  
  // Initialize Deeplinkly SDK
  FlutterDeeplinkly.init();
  
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Deeplinkly Deferred Test',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const DeepLinkTestScreen(),
    );
  }
}

class DeepLinkTestScreen extends StatefulWidget {
  const DeepLinkTestScreen({super.key});

  @override
  State<DeepLinkTestScreen> createState() => _DeepLinkTestScreenState();
}

class _DeepLinkTestScreenState extends State<DeepLinkTestScreen> {
  Map<dynamic, dynamic>? _deepLinkData;
  String _status = 'Waiting for deep link...';
  List<String> _logMessages = [];

  @override
  void initState() {
    super.initState();
    _setupDeepLinkListener();
    _checkInstallAttribution();
  }

  void _setupDeepLinkListener() {
    // Listen to deep link stream
    FlutterDeeplinkly.instance.deepLinkStream.listen((data) {
      setState(() {
        _deepLinkData = data;
        _status = 'Deep link received!';
      });
      
      _log('Deep link received: $data');
      developer.log('DEEPLINKLY_TEST: Deep link data received', 
          name: 'DeepLinkTest', 
          error: data.toString());
      
      // Print to console for Firebase Test Lab
      print('DEEPLINKLY_TEST: Deep link received');
      print('DEEPLINKLY_TEST: Data: $data');
    });
  }

  void _checkInstallAttribution() async {
    try {
      final attribution = await FlutterDeeplinkly.getInstallAttribution();
      if (attribution.isNotEmpty) {
        _log('Install attribution: $attribution');
        developer.log('DEEPLINKLY_TEST: Install attribution found', 
            name: 'DeepLinkTest', 
            error: attribution.toString());
        print('DEEPLINKLY_TEST: Install attribution: $attribution');
        
        setState(() {
          _status = 'Install attribution found';
          _deepLinkData = attribution.map((key, value) => MapEntry(key, value));
        });
      } else {
        _log('No install attribution found');
        print('DEEPLINKLY_TEST: No install attribution found');
      }
    } catch (e) {
      _log('Error getting install attribution: $e');
      print('DEEPLINKLY_TEST: Error: $e');
    }
  }

  void _log(String message) {
    setState(() {
      _logMessages.insert(0, '${DateTime.now().toString().substring(11, 19)}: $message');
      if (_logMessages.length > 50) {
        _logMessages.removeLast();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text('Deeplinkly Deferred Test'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Status:',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      _status,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: _deepLinkData != null 
                            ? Colors.green 
                            : Colors.orange,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            
            const SizedBox(height: 16),
            
            // Deep Link Data Card
            if (_deepLinkData != null)
              Card(
                color: Colors.green.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Deep Link Data:',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 8),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: SelectableText(
                          _formatData(_deepLinkData!),
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 12,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              )
            else
              Card(
                color: Colors.grey.shade100,
                child: const Padding(
                  padding: EdgeInsets.all(16.0),
                  child: Center(
                    child: Text('No deep link data received yet'),
                  ),
                ),
              ),
            
            const SizedBox(height: 16),
            
            // Log Messages
            Expanded(
              child: Card(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Text(
                        'Log Messages:',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    Expanded(
                      child: ListView.builder(
                        itemCount: _logMessages.length,
                        itemBuilder: (context, index) {
                          return Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16.0,
                              vertical: 4.0,
                            ),
                            child: Text(
                              _logMessages[index],
                              style: const TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 11,
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  String _formatData(Map<dynamic, dynamic> data) {
    final buffer = StringBuffer();
    data.forEach((key, value) {
      buffer.writeln('$key: $value');
    });
    return buffer.toString();
  }
}


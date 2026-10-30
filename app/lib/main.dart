// Phase 0 spike screen: install the rootfs, run the self-test, start `opencode serve`, show it in a WebView.
// The real onboarding/tabs UI comes in Phase 4.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:opencode_runtime/opencode_runtime.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'server_guard.dart';

void main() => runApp(const SpikeApp());

class SpikeApp extends StatelessWidget {
  const SpikeApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'OpenCode',
        theme: ThemeData(colorSchemeSeed: Colors.blueGrey, useMaterial3: true),
        darkTheme: ThemeData(colorSchemeSeed: Colors.blueGrey, brightness: Brightness.dark, useMaterial3: true),
        home: const SpikeScreen(),
      );
}

class SpikeScreen extends StatefulWidget {
  const SpikeScreen({super.key});

  @override
  State<SpikeScreen> createState() => _SpikeScreenState();
}

class _SpikeScreenState extends State<SpikeScreen> {
  final _runtime = OpencodeRuntime();
  final _stopwatch = Stopwatch();

  String _status = 'Not installed yet';
  int _entries = 0;
  bool _busy = false;
  bool _installed = false;
  Map<String, String> _selfTest = const {};
  final Map<String, Duration> _timings = {};
  WebViewController? _web;

  Future<T> _timed<T>(String label, Future<T> Function() f) async {
    _stopwatch
      ..reset()
      ..start();
    try {
      return await f();
    } finally {
      _stopwatch.stop();
      _timings[label] = _stopwatch.elapsed;
    }
  }

  Future<void> _run(String status, Future<void> Function() step) async {
    setState(() {
      _busy = true;
      _status = status;
    });
    try {
      await step();
    } on PlatformException catch (e) {
      setState(() => _status = 'Error: ${e.message}\n${e.details ?? ''}');
    } catch (e) {
      setState(() => _status = 'Error: $e');
    } finally {
      setState(() => _busy = false);
    }
  }

  Future<void> _install() => _run('Installing Linux environment…', () async {
        await _timed('install', () async {
          final done = Completer<void>();
          _runtime.install().listen(
                (n) => setState(() => _entries = n),
                onError: done.completeError,
                onDone: done.complete,
              );
          await done.future;
        });
        setState(() {
          _installed = true;
          _status = 'Installed';
        });
      });

  Future<void> _test() => _run('Running self-test…', () async {
        final r = await _timed('self-test', _runtime.selfTest);
        setState(() {
          _selfTest = r;
          _status = 'Self-test passed';
        });
      });

  Future<void> _start() => _run('Starting OpenCode server…', () async {
        final s = await _timed('server start', _runtime.startServer);
        final controller = WebViewController()
          ..setJavaScriptMode(JavaScriptMode.unrestricted)
          ..setNavigationDelegate(NavigationDelegate(
            // The server answers 401 with a Basic challenge; answering it lets every request carry the password.
            onHttpAuthRequest: (req) => shouldAnswerAuth(host: req.host, realm: req.realm)
                ? req.onProceed(WebViewCredential(user: 'opencode', password: s.password))
                : req.onCancel(),
            onNavigationRequest: (req) => isOwnServer(Uri.parse(req.url), s.port)
                ? NavigationDecision.navigate
                : NavigationDecision.prevent,
          ))
          ..loadRequest(Uri.parse('http://127.0.0.1:${s.port}/'));
        setState(() {
          _web = controller;
          _status = 'Server on port ${s.port}';
        });
      });

  Future<void> _showLog() async {
    final log = await _runtime.serverLog();
    if (!mounted) return;
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) => SafeArea(
        child: SizedBox(
          height: MediaQuery.of(context).size.height * 0.7,
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            reverse: true,
            child: SelectableText(log.isEmpty ? '(empty)' : log, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final web = _web;
    return Scaffold(
      appBar: AppBar(
        title: const Text('OpenCode'),
        actions: [IconButton(onPressed: _showLog, icon: const Icon(Icons.article_outlined), tooltip: 'Server log')],
      ),
      body: web != null
          ? WebViewWidget(controller: web)
          : ListView(
              padding: const EdgeInsets.all(16),
              children: [
                Text(_status, style: Theme.of(context).textTheme.titleMedium),
                if (_busy) ...[
                  const SizedBox(height: 12),
                  const LinearProgressIndicator(),
                  if (!_installed) Text('$_entries files extracted'),
                ],
                const SizedBox(height: 16),
                FilledButton(onPressed: _busy ? null : _install, child: const Text('1. Install')),
                const SizedBox(height: 8),
                FilledButton(onPressed: _busy || !_installed ? null : _test, child: const Text('2. Self-test')),
                const SizedBox(height: 8),
                FilledButton(onPressed: _busy || !_installed ? null : _start, child: const Text('3. Start server')),
                const SizedBox(height: 16),
                for (final e in _selfTest.entries) ListTile(dense: true, title: Text(e.key), trailing: Text(e.value)),
                for (final t in _timings.entries)
                  ListTile(dense: true, title: Text('${t.key} time'), trailing: Text('${(t.value.inMilliseconds / 1000).toStringAsFixed(1)} s')),
              ],
            ),
    );
  }
}

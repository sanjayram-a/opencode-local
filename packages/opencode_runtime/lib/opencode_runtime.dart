/// Dart API for the on-device OpenCode runtime (Alpine + proot inside the app sandbox).
library;

import 'package:flutter/services.dart';

class OpencodeRuntime {
  static const _methods = MethodChannel('opencode_runtime');
  static const _install = EventChannel('opencode_runtime/install');

  /// Extracts the bundled rootfs if it is missing or outdated. Emits the number of entries extracted so far
  /// and completes when the rootfs is ready (immediately when it already is).
  Stream<int> install() => _install.receiveBroadcastStream().map((e) => e as int);

  /// Runs `uname -m`, `node -v`, `python3 -V` and `opencode --version` inside the sandbox.
  /// Keys: arch, node, python, opencode.
  Future<Map<String, String>> selfTest() async =>
      Map<String, String>.from(await _methods.invokeMethod<Map>('selfTest') ?? const {});

  /// Starts (or restarts) `opencode serve` on 127.0.0.1. Throws [PlatformException] with code
  /// `server_start` and the server's log tail in `details` when it cannot start.
  Future<({int port, String password})> startServer({Map<String, String> env = const {}}) async {
    final r = await _methods.invokeMethod<Map>('startServer', {'env': env});
    return (port: r!['port'] as int, password: r['password'] as String);
  }

  Future<void> stopServer() => _methods.invokeMethod<void>('stopServer');

  /// The last lines of the server's output.
  Future<String> serverLog() async => await _methods.invokeMethod<String>('serverLog') ?? '';
}

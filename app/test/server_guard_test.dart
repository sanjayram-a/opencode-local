import 'package:flutter_test/flutter_test.dart';
import 'package:opencode_app/server_guard.dart';

void main() {
  group('isOwnServer', () {
    test('accepts our exact origin', () {
      expect(isOwnServer(Uri.parse('http://127.0.0.1:4100/session/abc'), 4100), isTrue);
    });
    test('rejects another localhost port', () {
      expect(isOwnServer(Uri.parse('http://127.0.0.1:9999/'), 4100), isFalse);
    });
    test('rejects other hosts and schemes', () {
      expect(isOwnServer(Uri.parse('http://localhost:4100/'), 4100), isFalse);
      expect(isOwnServer(Uri.parse('https://127.0.0.1:4100/'), 4100), isFalse);
      expect(isOwnServer(Uri.parse('https://example.com/'), 4100), isFalse);
    });
  });

  group('shouldAnswerAuth', () {
    test('answers only the loopback host with our realm', () {
      expect(shouldAnswerAuth(host: '127.0.0.1', realm: 'Secure Area'), isTrue);
      expect(shouldAnswerAuth(host: 'example.com', realm: 'Secure Area'), isFalse);
      expect(shouldAnswerAuth(host: '127.0.0.1', realm: 'Other'), isFalse);
      expect(shouldAnswerAuth(host: '127.0.0.1', realm: null), isFalse);
    });
  });
}

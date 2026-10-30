// Rules that keep the WebView (and the server password) tied to our own OpenCode server.

/// Realm that `opencode serve` sends in its `WWW-Authenticate: Basic` challenge.
const opencodeRealm = 'Secure Area';

/// True only for our server's exact origin: `http://127.0.0.1:<port>`. Another localhost port could be any app.
bool isOwnServer(Uri uri, int port) => uri.scheme == 'http' && uri.host == '127.0.0.1' && uri.port == port;

/// Android reports the host of an auth challenge without its port, so the port cannot be checked here.
/// Other localhost ports are kept out by [isOwnServer] for navigations, and by the OpenCode UI's own CSP for
/// sub-resources (img/font/media/frame only allow 'self', data: and https:).
bool shouldAnswerAuth({required String host, required String? realm}) =>
    host == '127.0.0.1' && realm == opencodeRealm;

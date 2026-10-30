# Phase 0 spike results

## Rootfs (built by `rootfs/build.sh`, Alpine 3.24.3, OpenCode 1.19.4)

| ABI | compressed (.tar.zst, -19) | uncompressed | tar entries |
|---|---|---|---|
| arm64-v8a | 112.3 MB | 393 MB | 5,991 |
| x86_64 | 115.0 MB | 398 MB | 5,992 |

Contents: bash, git, curl, ripgrep, openssh-client, ca-certificates, Node v24.22.1 + npm 11.12.1,
Python 3.14.10 + pip, uv 0.11.19, tzdata, OpenCode 1.19.4 (musl). Verified with `chroot` in Docker
(arm64 under qemu): `uname -m`, `node -v`, `python3 -V`, `opencode --version` all succeed.

## On-device results — emulator (oc36: API 36 / Android 16, x86_64, 4 KB pages, 4 GB RAM)

| Measure | Result |
|---|---|
| First install (stream zstd → untar 5,992 entries) | **15.3 s** |
| Rootfs on disk | 423 MB |
| Self-test through proot (`uname`, node, python, opencode) | **1.9 s** — x86_64 / v24.22.1 / Python 3.14.10 / 1.19.4 |
| `opencode serve` cold start → healthy (3 runs, app force-stopped between) | **3.6 s, 5.7 s, 5.6 s** |
| `PROOT_NO_SECCOMP` retry needed | No |
| Memory while serving (RSS) | opencode 378 MB · proot 3.6 MB · app process 478 MB (WebView + Flutter) |
| OpenCode web UI in WebView (Basic auth via `onHttpAuthRequest`) | Renders; projects/sessions view loads |
| PTY bridge (`PtyInstrumentedTest`) | 3/3 pass |
| Guest networking (DNS + HTTPS from inside proot) | Works. Emulator uplink is slow (444 ms ping), so OpenCode's `models.dev` fetch sometimes hits its ~10 s timeout — re-check on a phone. |

### Things learned
- Processes started with `adb shell run-as` (outside the app's own process tree) start `opencode serve` but never accept
  connections. Launched from the app (ProcessBuilder) they work every time. This only affects manual debugging; use the
  app's server log, not `run-as`, to measure.
- `curl` with no `-m` timeout hangs forever against such a server; the app's probe has 1 s connect / 2 s read timeouts.

## Still to do (needs you)
1. **Real arm64 phone:** plug one in with USB debugging, then `bash tool/copy_rootfs.sh arm64-v8a && cd app && flutter run`.
   Record the same table. This is the real test of proot + Bun on a phone kernel and OEM seccomp.
2. **Chat round-trip:** needs a provider API key (for example an Anthropic or OpenAI key) entered in OpenCode's UI.
   Ask it to create `/root/projects/hello.py` and run it.

## Verdict
**GO on the emulator**: the server is healthy in under 6 s (target: under 20 s), the UI works and no seccomp fallback was needed.
The final go/no-go waits for the two items above on a real arm64 phone.

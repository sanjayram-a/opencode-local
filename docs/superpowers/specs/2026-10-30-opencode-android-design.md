# Plan: "OpenCode on Android" — Flutter app running `opencode serve` inside an Alpine + proot sandbox

## Context
This project runs the **OpenCode server** on the phone inside an Alpine + proot sandbox, alongside **npm/Node, Python, common CLI tools and a lightweight Linux userland**, and opens it in an app UI.

> Note: the internal Play policy research guide and Hermes report referenced in this design are not part of this public repository. References to “guide §” below are historical notes from that internal research.

Decisions made with the user:
- **Distribution:** Google Play. Follow Google Play's device/network abuse and interpreted-code policies.
- **Linux env:** Alpine (musl) + proot.
- **UI:** a native app that hosts OpenCode's own web UI in a WebView, plus a built-in terminal, plus an optional LAN mode so a laptop can connect.
- **App stack:** Flutter for the UI. Kotlin underneath for processes, PTY, extraction and the foreground service.

Facts checked:
- OpenCode publishes a `linux-arm64-musl` binary. The glibc build cannot run on bare Android because `/lib/ld-linux-aarch64.so.1` is missing. An existing unofficial app (yuga-hashimoto/opencode-android) already runs Alpine + the OpenCode musl binary under proot, so the approach has been shown to work.
- `opencode serve --hostname --port`: the default is 127.0.0.1:4096. `OPENCODE_SERVER_PASSWORD` turns on HTTP basic auth (the user is `opencode`). The v2 docs differ a little (they mention port 49374 and `opencode pair`), so pin the OpenCode version and check its flags.

This is a greenfield project.

---

## Architecture

```
┌──────────────────── Flutter (Dart) ─────────────────────┐
│ Onboarding/disclosure │ Setup progress │ OpenCode (WebView)│
│ Terminal (xterm.dart) │ Environment mgr │ Settings/keys    │
└──────────┬───────────── MethodChannel / EventChannel ─────┘
           │
┌──────────▼──────────── Kotlin host (plugin: opencode_runtime) ─────────┐
│ RuntimeService  (FGS, type=specialUse)  ── owns all child processes    │
│ RootfsInstaller (asset pack → zstd stream → untar, resumable, atomic)  │
│ EnvBuilder      (explicit envp, never inherited)                       │
│ ProotLauncher   (builds proot argv)                                    │
│ PtyBridge (JNI forkpty+execve)   ServerSupervisor (health, restart)    │
└──────────┬─────────────────────────────────────────────────────────────┘
           │ execve from nativeLibraryDir only
┌──────────▼─────────── jniLibs/arm64-v8a ──────────┐
│ libproot.so, libproot-loader.so (termux proot)    │
└──────────┬────────────────────────────────────────┘
           │ ptrace path translation, fake root (-0)
┌──────────▼──── Alpine rootfs  files/rootfs  (data, not Android code) ─┐
│ /usr/local/bin/opencode (musl)  bash git curl ripgrep openssh-client  │
│ nodejs npm  python3 py3-pip uv  ca-certificates                       │
│ /root  ← bind-mounted from files/home (user data survives upgrades)   │
└───────────────────────────────────────────────────────────────────────┘
```

### 1. Exec model (Play-legal)
- Only **proot** and its loader run from `nativeLibraryDir`. They are shipped as `jniLibs/arm64-v8a/libproot.so` and `libproot-loader.so` (built from termux/proot with talloc linked statically). Set `useLegacyPackaging = true` and `extractNativeLibs="true"` (guide §4.2). Nothing is ever exec'd from `files/` directly.
- Everything else (OpenCode, node, python, bash) is guest userland that proot runs. This is the "interpreter lane" described in guide §2.1.
- The launch command is built in one place, `ProotLauncher`:
  `libproot.so --kill-on-exit -r $ROOTFS -b /dev -b /proc -b /sys -b $FILES/home:/root -b $FILES/tmp:/tmp -0 -w /root --link2symlink <cmd>`
- Environment variables: `PROOT_LOADER`, `PROOT_TMP_DIR`, and `PROOT_NO_SECCOMP=1` as an automatic retry if the first start fails (guide §13).

### 2. Rootfs: built on CI, shipped as data
- `rootfs/Dockerfile` builds from `alpine:3.2x`, using `docker buildx --platform linux/arm64`. Steps:
  - `apk add bash git curl ripgrep openssh-client ca-certificates nodejs npm python3 py3-pip uv tzdata`
  - Download and verify a **pinned** OpenCode `linux-arm64-musl` release into `/usr/local/bin/opencode`.
  - Delete docs/man pages and caches. Precompile Python with `compileall`.
  - Write `/.manifest.json` containing the version, OpenCode version and checksum.
- Export with `docker export | zstd -19` to produce `rootfs-<date>.tar.zst`. Expected size is roughly 120–200 MB compressed. Measure it in Phase 0.
- Deliver it as an **install-time Play Asset Delivery pack** (`android/assetpacks/rootfs`, guide §5.2).
- `build-base` (gcc/make) is **not bundled**. The Environment screen offers it as a one-tap `apk add build-base`, which keeps the base size small.

### 3. First run and upgrades (`RootfsInstaller`)
- Runs as a background worker, never on the main thread. It streams pack asset → zstd decoder (`com.github.luben:zstd-jni`) → tar reader (commons-compress) → `files/rootfs.new/`.
- It restores mode bits and creates symlinks with `Os.symlink`. Progress goes to Flutter over an EventChannel. It records how many entries it has extracted so it can resume after an interruption.
- When extraction finishes it fsyncs, then renames `rootfs.new` to `rootfs` atomically. If the manifest version differs from the bundled one, it re-extracts into `.new` and swaps. Old rootfs is deleted only after the swap succeeds (guide §12).
- **User data survives upgrades** because `/root` (projects, OpenCode config and sessions, npm/pip/uv caches) lives in `files/home` and is bind-mounted in. Packages the user adds with apk are recorded in `files/home/.apk-extra` and installed again after a rootfs swap.

### 4. Running the OpenCode server efficiently (`ServerSupervisor`)
- **There is one resident server process**, not one per request. It runs `proot … /usr/local/bin/opencode serve --hostname 127.0.0.1 --port <free port>` with `OPENCODE_SERVER_PASSWORD=<random per boot>`. All tools OpenCode starts (bash, npm, python) are children of that proot, so they stay traced and sandboxed without a second proot layer.
- To decide when the server is ready, it polls the HTTP endpoint with backoff. There are no fixed sleeps. Stdout and stderr go to a ring-buffered log that the UI can view.
- **Lifecycle:** the server starts when the user opens OpenCode. It stays alive under the foreground service while sessions are open, and stops after a configurable idle period (default 15 min) with no WebView or client attached. It restarts with backoff if it crashes (at most 3 tries, then shows the error and logs).
- **Warm-up:**
  - `NODE_COMPILE_CACHE` and `UV_CACHE_DIR` point at `/root/.cache`.
  - `npm_config_prefer_offline=true` and `PIP_PREFER_BINARY=1` reduce network use.
  - `NODE_OPTIONS=--max-old-space-size` is sized from device RAM (guide §15.2).
- **Memory guard:** the app listens to `onTrimMemory`. On a critical level it stops idle terminal sessions first and the server last.
- **LAN mode** is off by default. When turned on, the server restarts with `--hostname 0.0.0.0` and the password is mandatory. The app shows the URL and password, plus a QR code. A persistent notification stays up while LAN mode is on.

### 5. API keys and model access
- Provider keys are stored with `flutter_secure_storage` (backed by Android Keystore). They are passed to the server process as environment variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, …) at spawn and are never written to rootfs files by the app.
- Users can instead log in through OpenCode's own UI. OpenCode then keeps its credentials in `/root/.local/share/opencode`, inside the private sandbox.

### 6. Flutter UI
- **Onboarding:** a disclosure screen ("a Linux environment runs in this app's private sandbox, and the AI can run commands there") and a consent toggle (guide §2.3 and §8.3).
- **Setup:** extraction progress, then the result of a self-test (`uname -m`, `node -v`, `python3 -V`, `opencode --version`).
- **OpenCode tab:** `webview_flutter` loads `http://127.0.0.1:<port>`. It injects the basic-auth header, keeps JavaScript enabled and allows only localhost navigation; any external link opens in the system browser.
- **Terminal tab:**
  - `xterm` (xterm.dart) shows the output. A PTY from the JNI `forkpty` helper (guide §4.5) runs `proot … /bin/bash -l`.
  - Bytes travel over an EventChannel. Keystrokes and resize events go back over a MethodChannel (`updateSize`, guide §6).
  - There is an extra key row (Esc/Ctrl/Tab/arrows) and multiple sessions.
  - Users can run `opencode` (the TUI) or `opencode attach` to the running server here.
- **Environment tab:** shows installed runtimes and versions. Offers one-tap optional packs (build-base, python dev headers, extra npm globals) that run `apk add` in a visible PTY so the user sees exactly what runs. Also has disk usage and a "reset environment" button that keeps `/root`.
- **Projects:** files live in `/root/projects`. Users bring code in with `git clone` or import/export through the Storage Access Framework (copy in and copy out). Do **not** request `MANAGE_EXTERNAL_STORAGE`, because Play is strict about it.
- **Settings:** API keys, idle timeout, LAN mode, server log viewer, OpenCode version.

### 7. Manifest and Play compliance
- Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`. Nothing else.
- `RuntimeService` uses `foregroundServiceType="specialUse"` with an honest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (guide §9.1).
- Set `targetSdk` to the current Play requirement, `allowBackup=false`, and Play Integrity / licensing.
- The store listing and Data safety form must say that prompts go to the provider the user configures.
- Updating OpenCode in place by downloading it at runtime is **off by default**. New versions arrive with app updates through the bundled rootfs. An advanced "update OpenCode inside the environment" action can exist because it is Linux userland, but flag it in the policy review.
- **ABI:** release builds ship `arm64-v8a` only. Asset packs can't be split by ABI, so a single arm64 rootfs is shipped. An `x86_64` proot and rootfs are made only for emulator debug builds.

---

## Repo layout (new)
```
E:\opencodeapk\
  app/                         Flutter app
    lib/  (features: onboarding, setup, opencode_web, terminal, environment, settings)
    android/app/src/main/jniLibs/arm64-v8a/libproot.so, libproot-loader.so
    android/assetpacks/rootfs/src/main/assets/rootfs/rootfs.tar.zst
  packages/opencode_runtime/   Flutter plugin: Dart API + Kotlin (RuntimeService, RootfsInstaller,
                               EnvBuilder, ProotLauncher, ServerSupervisor, PtyBridge) + C (pty.c via CMake)
  rootfs/Dockerfile, rootfs/build.sh   Alpine arm64 image → tar.zst + manifest
  native/proot/build.sh        cross-compile termux/proot with NDK → jniLibs
  .github/workflows/           rootfs.yml, app.yml (guide §12)
```

## Phases
0. **Spike, on a real arm64 phone. This is the biggest risk, so do it first.** Make a minimal Kotlin app that bundles libproot plus an Alpine minirootfs with OpenCode musl, then run `opencode serve` and open it in a WebView. Measure:
   - cold start time and RAM use
   - whether Bun works under proot (`/proc/self/exe`, io_uring/epoll fallbacks)
   - whether `PROOT_NO_SECCOMP` is needed
   
   If Bun misbehaves, the fallback is the npm `opencode-ai` package running on Alpine's node, if that version supports it.
1. Native layer: build proot, the JNI pty helper, and `RootfsInstaller` with resumable extraction.
2. CI for the rootfs, the asset pack wiring, and the manifest/upgrade swap.
3. `ServerSupervisor` and the foreground service: lifecycle, health checks, idle stop, memory guard.
4. Flutter UI: onboarding, setup, WebView, terminal, environment, settings.
5. LAN mode, SAF import/export, secure key storage.
6. Hardening and the device test matrix (guide §11), then the Play checklist (guide §10).

## Verification
- **Unit:** EnvBuilder and ProotLauncher argv/env snapshots. Tar extraction keeps modes, symlinks and resume working (JVM tests against a fixture tar.zst).
- **On device (instrumented):**
  - After a fresh install, setup completes.
  - The self-test reports `aarch64`, plus node, python and opencode versions.
  - `opencode serve` answers on 127.0.0.1 within N seconds.
  - The WebView loads and a chat session with a real API key edits a file in `/root/projects`.
  - In the terminal, `npm i -g` and `pip install requests` succeed, and `vim`/`htop` resize correctly.
- **Lifecycle:** swiping the app away keeps the server running (FGS); the idle timeout stops it; killing the server triggers a restart; after a rootfs version bump, `/root` data survives.
- **LAN:** a laptop on the same Wi-Fi opens the URL; a wrong password gets 401.
- **Play:** upload to the internal testing track and check the pre-launch report for exec errors such as `EACCES`/`ENOENT`. Test on Pixel, Samsung One UI, Xiaomi HyperOS and Android 12/14/15/16.

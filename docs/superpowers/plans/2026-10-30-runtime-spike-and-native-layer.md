# Runtime Spike + Native Layer Implementation Plan (Phases 0–1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove that `opencode serve` runs inside Alpine under proot from an Android app and shows in a WebView. Then build the reusable native layer: proot binaries, rootfs installer, env/launcher, PTY bridge and server supervisor.

**Architecture:** There is a Flutter host app (`app/`) and a Flutter plugin (`packages/opencode_runtime/`). The plugin does all the Android-side work in Kotlin plus a small C file.
- proot ships as fake `lib*.so` files in `jniLibs`.
- The Alpine rootfs is built in WSL with `apk.static` (no Docker or qemu needed). For this phase it ships as a plain APK asset; the Play asset pack comes in Phase 2.
- The installer streams zstd → tar into `files/rootfs.new` and swaps it in atomically.

**Tech Stack:** Flutter 3.44 / Dart 3.12, Kotlin, JDK 17, AGP from the Flutter template, NDK 28.2.13676358, CMake (from the SDK), `commons-compress` 1.27.1, `zstd-jni` 1.5.7-4 (aar), `webview_flutter`, JUnit4. Build scripts run in WSL Ubuntu.

**Spec:** `docs/superpowers/specs/2026-10-30-opencode-android-design.md`

## Global Constraints
- Android package / Kotlin namespace: app `dev.opencodeapk.app`, plugin `dev.opencodeapk.runtime` (my call; rename before the first Play upload if wanted).
- ABIs: `arm64-v8a` and `x86_64` (x86_64 is for the emulator only). `useLegacyPackaging = true`, `android:extractNativeLibs="true"`.
- `minSdk 26`, `targetSdk 36`, `compileSdk 36`.
- Nothing is ever exec'd from `filesDir`. The only exec targets are `nativeLibraryDir/libproot.so` (and the loader, which proot itself uses).
- Every child process gets an explicit env from `EnvBuilder` and never inherits the app's env.
- Pin Alpine `3.24` and OpenCode version `1.18.3` in one file: `rootfs/versions.env`. Before starting, check the exact OpenCode asset names with `gh release view v1.18.3 -R anomalyco/opencode`.
- The server binds `127.0.0.1` with a random `OPENCODE_SERVER_PASSWORD`. LAN mode is not part of this plan.
- Emulator tests use the **4 KB page** image `system-images;android-36;google_apis_playstore`, not the `ps16k` images (see Task 2 for 16 KB alignment).
- Licensing: proot is GPL-2.0 and talloc is LGPL-3.0. Record the source URL and version in `native/proot/SOURCES.md`.

## Review Focus
1. **Extraction killed halfway** (app swiped or killed mid-install). The next launch must resume or restart cleanly. It must never boot a half-extracted rootfs. → Task 3 `resumesAfterInterruption`, `neverExposesPartialRootfs`.
2. **Crash during the swap step**, leaving only `rootfs.old`. The next start must restore it. → Task 3 `recoversFromInterruptedSwap`.
3. **The chosen port is already in use.** The supervisor must pick another free port and not hang. → Task 6 `picksFreePortEachStart`.
4. **proot dies at once on a device with seccomp quirks.** Retry once with `PROOT_NO_SECCOMP=1`, then report the error with the log tail. → Task 6 `retriesWithNoSeccompOnEarlyExit`.
5. **Tar entries that try to escape the folder** (`../`, absolute paths, symlink tricks) must not write outside the rootfs folder. → Task 3 `rejectsPathTraversal`.

---

## File Structure
```
.gitignore
app/                                     Flutter host (flutter create)
  lib/main.dart                          spike UI: Install → Self-test → Start → WebView
  android/app/build.gradle.kts           ABIs, legacy packaging, noCompress "zst"
  android/app/src/main/AndroidManifest.xml
  android/app/src/main/assets/rootfs/    rootfs-<abi>.tar.zst (git-ignored, copied by script)
packages/opencode_runtime/               Flutter plugin (android only)
  lib/opencode_runtime.dart              Dart API (Task 7)
  android/build.gradle                   deps, CMake, jniLibs
  android/src/main/jniLibs/{arm64-v8a,x86_64}/libproot.so, libproot-loader.so, libtalloc.so
  android/src/main/cpp/{CMakeLists.txt,pty.c}
  android/src/main/kotlin/dev/opencodeapk/runtime/
    Paths.kt              all on-disk locations
    FileOps.kt            interface + AndroidFileOps (Os.chmod/symlink/link)
    RootfsInstaller.kt    streaming extract, resume, swap, recovery
    EnvBuilder.kt         explicit envp
    ProotLauncher.kt      argv builder
    Pty.kt                JNI bindings + PtySession
    ServerSupervisor.kt   spawn/health/log/retry
    OpencodeRuntimePlugin.kt  channels
  android/src/test/kotlin/dev/opencodeapk/runtime/  JVM unit tests
native/proot/fetch.sh, SOURCES.md        fetch+patch termux proot for 2 ABIs
rootfs/versions.env, rootfs/build.sh     Alpine rootfs → out/rootfs-<abi>.tar.zst + manifest
docs/spike-results.md                    Phase 0 measurements
```

---

### Task 1: Repo and project scaffolding

**Files:** Create `.gitignore`, `app/` (via `flutter create`), `packages/opencode_runtime/` (via `flutter create --template=plugin`). Modify `app/pubspec.yaml`, `app/android/app/build.gradle.kts`, `app/android/app/src/main/AndroidManifest.xml`, `packages/opencode_runtime/android/build.gradle`.

**Interfaces:** Produces the Gradle module `:opencode_runtime`. It is used by `app` through `path: ../packages/opencode_runtime`.

- [ ] **Step 1:** `git init` in `E:\opencodeapk`. `.gitignore` covers Flutter/Gradle defaults plus `out/`, `*.tar.zst`, `native/proot/work/`, `rootfs/work/`.
- [ ] **Step 2:** `flutter create --org dev.opencodeapk --project-name opencode_app --platforms android app`, then `flutter create --template=plugin --org dev.opencodeapk --platforms android --android-language kotlin packages/opencode_runtime` (plugin class `OpencodeRuntimePlugin`, package `dev.opencodeapk.runtime`). Delete the plugin's `example/` folder.
- [ ] **Step 3:** Add the dependency `opencode_runtime: {path: ../packages/opencode_runtime}` and `webview_flutter` to the app. In `app/android/app/build.gradle.kts`:
  - `applicationId "dev.opencodeapk.app"`, `minSdk 26`, `targetSdk 36`
  - `ndk { abiFilters += listOf("arm64-v8a","x86_64") }`
  - `packaging { jniLibs { useLegacyPackaging = true } }`
  - `androidResources { noCompress += "zst" }`
- [ ] **Step 4:** In the manifest, set `android:extractNativeLibs="true"` and `android:allowBackup="false"`, and add the permissions `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`. Allow cleartext only for localhost with `res/xml/network_security_config.xml` (`<domain includeSubdomains="false">127.0.0.1</domain>` cleartextTrafficPermitted=true).
- [ ] **Step 5:** In plugin `android/build.gradle`: namespace `dev.opencodeapk.runtime`, `minSdk 26`, deps `org.apache.commons:commons-compress:1.27.1`, `com.github.luben:zstd-jni:1.5.7-4@aar`, `testImplementation "junit:junit:4.13.2"`.
- [ ] **Step 6: Verify:** `cd app && flutter build apk --debug` succeeds.
- [ ] **Step 7: Commit** `chore: scaffold flutter app and opencode_runtime plugin`.

### Task 2: proot binaries for both ABIs

**Files:** Create `native/proot/fetch.sh`, `native/proot/SOURCES.md`. Output goes to `packages/opencode_runtime/android/src/main/jniLibs/<abi>/`.

**Interfaces:** Produces `libproot.so`, `libproot-loader.so` and `libtalloc.so` per ABI. `EnvBuilder` (Task 4) relies on these file names.

- [ ] **Step 1:** Write `fetch.sh`, which runs in WSL. It needs `apt install binutils patchelf curl` and takes no arguments. For each Termux arch `aarch64→arm64-v8a`, `x86_64→x86_64` it:
  1. Downloads `https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-<arch>/Packages`.
  2. Finds the `Filename` and `SHA256` for `proot` and `libtalloc`, downloads both `.deb` files, and checks their SHA-256.
  3. Unpacks them with `dpkg-deb -x` into `work/<arch>`.
  4. Copies `usr/bin/proot` → `libproot.so`, `usr/libexec/proot/loader` → `libproot-loader.so`, and `usr/lib/libtalloc.so.2*` → `libtalloc.so`.
  5. Runs `patchelf --remove-rpath --replace-needed libtalloc.so.2 libtalloc.so libproot.so` and `patchelf --set-soname libtalloc.so libtalloc.so`.
  6. Appends the package versions and URLs to `SOURCES.md`.
- [ ] **Step 2: Verify:** run `wsl bash native/proot/fetch.sh`. Then `readelf -d libproot.so | grep NEEDED` shows `libtalloc.so` and has no RUNPATH. `file` shows the correct arch for each of the 3 files in each ABI.
- [ ] **Step 3: Check 16 KB alignment:** run `readelf -lW libproot.so libtalloc.so | grep LOAD` and write the `Align` values into `SOURCES.md`. If they are `0x1000`, add a Phase 2 TODO line in `SOURCES.md`: "rebuild from source with `-Wl,-z,max-page-size=16384` before the Play upload". This is not a blocker for the spike.
- [ ] **Step 4: Commit** `build: fetch and patch termux proot for arm64/x86_64`. Commit the binaries. They are small and pinned in `SOURCES.md`.

### Task 3: RootfsInstaller (streaming extract, resume, atomic swap)

**Files:** Create `Paths.kt`, `FileOps.kt`, `RootfsInstaller.kt`. Test: `android/src/test/kotlin/dev/opencodeapk/runtime/RootfsInstallerTest.kt`.

**Interfaces:**
- Produces:
  - `data class Paths(val filesDir: File, val nativeLibDir: File)` with `rootfs`, `rootfsNew`, `rootfsOld`, `home`, `tmp`, `prootTmp` (= `filesDir/proot-tmp`) and `progressFile` (= `rootfsNew/.extract-progress`)
  - `interface FileOps { fun writeFile(dst: File, input: InputStream, mode: Int); fun mkdir(dir: File, mode: Int); fun symlink(target: String, link: File); fun hardlink(existing: File, link: File); fun deleteRecursively(f: File) }`
  - `class AndroidFileOps : FileOps`, which uses `android.system.Os`
  - `class RootfsInstaller(paths: Paths, ops: FileOps)` with:
    - `fun recover()`
    - `fun installedVersion(): String?` (reads `rootfs/.manifest.json` → `"version"`)
    - `fun install(openTar: () -> InputStream, bundledVersion: String, onProgress: (entriesDone: Int) -> Unit)`
- Tests use a `RecordingFileOps(root: File)`. It writes real files under a temp dir and records modes and links in maps, so the tests also pass on Windows.

- [ ] **Step 1: Write failing tests.** Build fixture tars in memory with `TarArchiveOutputStream` (POSIX long names on). Tests:
  - `extractsFilesDirsModesAndLinks`: the fixture has dir `bin/` (0755), file `bin/sh` (0755, content `"x"`), symlink `usr/bin/env → /bin/sh` and hardlink `bin/ash → bin/sh`. Assert that `rootfs/bin/sh` content is `"x"`, its recorded mode is `0b111_101_101`, the symlink is recorded with target `/bin/sh`, and the hardlink is recorded.
  - `resumesAfterInterruption`: the stream throws `IOException` after entry 2 of 4. Assert that `progressFile` holds `2`. A second `install` call with a fresh stream completes. `onProgress` in the second run starts at 3, and all 4 files exist.
  - `neverExposesPartialRootfs`: after the failed run above, `paths.rootfs` does not exist.
  - `swapsAtomicallyAndKeepsHome`: with an existing `rootfs/` (version `"1"`) and `home/a.txt`, install version `"2"`. Then `installedVersion() == "2"`, `rootfsOld` is gone and `home/a.txt` is still there.
  - `recoversFromInterruptedSwap`: `rootfsOld` exists and `rootfs` does not. `recover()` renames it back, and `installedVersion()` returns the old version.
  - `rejectsPathTraversal`: entries `../evil` and `/abs`, and symlink `x → ../../..` followed by file `x/escape`. `install` throws `SecurityException`, and nothing exists outside `rootfsNew`.
- [ ] **Step 2:** Run `cd app/android && ./gradlew :opencode_runtime:testDebugUnitTest`. Expected: FAIL (classes missing).
- [ ] **Step 3: Implement.**
  - `install` writes `.manifest.json` last. The tar must contain it as the final entry; the rootfs build in Task 5 makes sure of that.
  - It writes `progressFile` after each finished entry. On resume it skips that many entries.
  - When done: delete `progressFile`. If `rootfs` exists, rename `rootfs`→`rootfsOld`. Rename `rootfsNew`→`rootfs`, then delete `rootfsOld`.
  - For path safety, normalise each entry name, then reject an absolute name, a name containing `..` after normalising, or a parent folder that is a symlink in the already-extracted tree.
- [ ] **Step 4:** Run the tests. Expected: PASS.
- [ ] **Step 5: Commit** `feat(runtime): resumable atomic rootfs installer`.

### Task 4: EnvBuilder + ProotLauncher

**Files:** Create `EnvBuilder.kt`, `ProotLauncher.kt`. Test: `LauncherTest.kt`.

**Interfaces:**
- Produces:
  - `object EnvBuilder { fun build(paths: Paths, extra: Map<String,String> = emptyMap(), noSeccomp: Boolean = false, heapMb: Int = 1024): Map<String,String> }`
  - `object ProotLauncher { fun argv(paths: Paths, guestCmd: List<String>, cwd: String = "/root"): List<String> }`

- [ ] **Step 1: Write failing tests:**
  - `argvMatchesSpec`: `argv(paths, listOf("/bin/sh","-c","true"))` equals:
    ```
    [<nativeLibDir>/libproot.so, --kill-on-exit, -r, <rootfs>, -b, /dev, -b, /proc, -b, /sys,
     -b, <home>:/root, -b, <tmp>:/tmp, -0, -w, /root, --link2symlink, /bin/sh, -c, true]
    ```
  - `envHasRequiredKeys`: the keys are exactly `HOME=/root`, `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`, `TMPDIR=/tmp`, `LANG=C.UTF-8`, `TERM=xterm-256color`, `PROOT_LOADER=<nativeLibDir>/libproot-loader.so`, `PROOT_TMP_DIR=<prootTmp>`, `LD_LIBRARY_PATH=<nativeLibDir>`, `NODE_COMPILE_CACHE=/root/.cache/node-compile`, `UV_CACHE_DIR=/root/.cache/uv`, `npm_config_prefer_offline=true`, `PIP_PREFER_BINARY=1`, `NODE_OPTIONS=--max-old-space-size=1024`, plus anything in `extra`. There is no `PROOT_NO_SECCOMP` unless `noSeccomp=true`, in which case it is `"1"`.
  - `envDoesNotLeakHostEnv`: no key from `System.getenv()` appears unless it is listed above.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit** `feat(runtime): env builder and proot argv`.

### Task 5: Alpine rootfs build (WSL, no Docker)

**Files:** Create `rootfs/versions.env` (`ALPINE=3.24`, `OPENCODE_VERSION=1.18.3`, `ROOTFS_VERSION=2026.10.0`) and `rootfs/build.sh <arm64|x86_64>`.

**Interfaces:** Produces `out/rootfs-<abi>.tar.zst` (abi = `arm64-v8a`/`x86_64`). Its **last entry** is `.manifest.json` = `{"version":ROOTFS_VERSION,"opencode":OPENCODE_VERSION,"arch":...}`. Task 3 depends on that ordering.

- [ ] **Step 1:** Write `build.sh`, which needs `apt install zstd curl` in WSL. It:
  1. Downloads `alpine-minirootfs-<latest 3.24.x>-<aarch64|x86_64>.tar.gz` and `apk.static` for the host, verifying both against Alpine's `.sha256` files.
  2. Unpacks the minirootfs into `work/<abi>` (with `sudo` so ownership is kept).
  3. Runs `apk.static --arch <arch> --root work/<abi> --repositories-file work/<abi>/etc/apk/repositories --allow-untrusted --no-scripts --update-cache add bash git curl ripgrep openssh-client ca-certificates nodejs npm python3 py3-pip uv tzdata`.
  4. Downloads the OpenCode `linux-<arm64|x64>-musl` release asset, checks it against the release checksum, and installs it to `usr/local/bin/opencode` (0755).
  5. Removes `usr/share/{man,doc,info}` and `var/cache/apk/*`. Writes `etc/resolv.conf` (`nameserver 8.8.8.8` / `1.1.1.1`).
  6. Creates the tar sorted by name with `.manifest.json` appended last: `tar --exclude=./.manifest.json -cf - . ; tar -rf` the manifest. Compresses with `zstd -19 -T0`.
- [ ] **Step 2:** **Verify:**
  - `wsl bash rootfs/build.sh arm64 && wsl bash rootfs/build.sh x86_64` both produce files.
  - `tar --zstd -tf out/rootfs-x86_64.tar.zst | tail -1` prints `./.manifest.json`.
  - `sudo chroot rootfs/work/x86_64 /usr/local/bin/opencode --version` prints `1.18.3` (x86_64 only, as a native smoke test).
  - Record the compressed sizes in `docs/spike-results.md`.
- [ ] **Step 3: Commit** `build: alpine rootfs with opencode, node, python`.

### Task 6: PTY bridge + ServerSupervisor

**Files:** Create `cpp/CMakeLists.txt`, `cpp/pty.c`, `Pty.kt`, `ServerSupervisor.kt`. Test: `ServerSupervisorTest.kt` (JVM, using a fake spawner).

**Interfaces:**
- Consumes `EnvBuilder.build` and `ProotLauncher.argv` (Task 4) and `Paths` (Task 3).
- Produces:
  - JNI `object Pty { external fun spawn(argv: Array<String>, env: Array<String>, cwd: String, rows: Int, cols: Int): IntArray /*[masterFd, pid]*/; external fun resize(fd: Int, rows: Int, cols: Int); external fun waitFor(pid: Int): Int; external fun kill(pid: Int, sig: Int) }`
  - `class PtySession(argv, env, cwd, rows, cols)` with `val output: InputStream`, `fun write(bytes: ByteArray)`, `fun resize(rows: Int, cols: Int)`, `fun close()`
  - `interface Spawner { fun spawn(argv: List<String>, env: Map<String,String>, cwd: File): Proc }`, where `Proc` has `stdout: InputStream`, `isAlive()`, `exitCode(): Int?`, `destroy()`. The real implementation uses `ProcessBuilder` with `redirectErrorStream(true)` and `environment().clear()`.
  - `class ServerSupervisor(paths: Paths, spawner: Spawner, probe: (port: Int, password: String) -> Boolean, freePort: () -> Int, clock: Clock)` with:
    - `fun start(extraEnv: Map<String,String>): ServerInfo`, where `data class ServerInfo(val port: Int, val password: String)`. It blocks until healthy or for at most 60 s, then throws `ServerStartException(logTail: String)`.
    - `fun stop()`
    - `val log: RingLog` (last 2,000 lines)
- The guest command is `/usr/local/bin/opencode serve --hostname 127.0.0.1 --port <port>`. `OPENCODE_SERVER_PASSWORD` is 24 random URL-safe chars from `SecureRandom`. The real probe is an HTTP `GET /` with basic auth `opencode:<password>`; any status below 500 counts as healthy.

- [ ] **Step 1: Write failing tests (FakeSpawner/FakeProbe):**
  - `picksFreePortEachStart`: `freePort` returns 4100 and then 4101. Start → stop → start gives ports 4100 and 4101, and the spawned argv contains `--port 4101`.
  - `retriesWithNoSeccompOnEarlyExit`: the first fake process exits with code 1 within 2 s. Assert that a second spawn happens with env `PROOT_NO_SECCOMP=1`, and that `start` succeeds when the probe then returns true.
  - `failsWithLogTailAfterSecondEarlyExit`: both spawns exit. Expect `ServerStartException` whose `logTail` contains the fake stdout line `"boom"`.
  - `passwordInEnvNotArgv`: the password appears in env only and never in argv.
- [ ] **Step 2:** Run. Expected: FAIL. **Step 3:** Implement the supervisor. Implement `pty.c` (forkpty/execve, `setsid` handled by forkpty, `_exit(127)` if exec fails, `TIOCSWINSZ` for resize) and its CMake target `opencode_pty`. **Step 4:** Run. Expected: PASS.
- [ ] **Step 5: Commit** `feat(runtime): pty bridge and server supervisor`.

### Task 7: Plugin channels + spike UI

**Files:** Modify `OpencodeRuntimePlugin.kt` and `packages/opencode_runtime/lib/opencode_runtime.dart`. Create `app/lib/main.dart`, plus the script `tool/copy_rootfs.sh` (copies `out/rootfs-*.tar.zst` into `app/android/app/src/main/assets/rootfs/`).

**Interfaces:**
- Consumes `RootfsInstaller`, `ServerSupervisor` and `PtySession`.
- Produces this Dart API, which Phase 4 UI builds on:
  ```dart
  class OpencodeRuntime {
    Stream<int> install();                       // EventChannel 'opencode_runtime/install', entries done; completes on success
    Future<Map<String,String>> selfTest();       // keys: arch,node,python,opencode
    Future<({int port, String password})> startServer({Map<String,String> env = const {}});
    Future<void> stopServer();
    Future<String> serverLog();
  }
  ```
- Method names on channel `opencode_runtime`: `selfTest`, `startServer`, `stopServer`, `serverLog`.
- Bundled asset: `rootfs/rootfs-<Build.SUPPORTED_ABIS[0]>.tar.zst`, opened via `AssetManager` and wrapped in `ZstdInputStream`. All work runs on a background executor and never on the main thread.
- `selfTest` runs `uname -m; node -v; python3 -V; opencode --version` through proot (ProcessBuilder) and parses one value per line.

- [ ] **Step 1:** Implement the channels and the Dart API.
- [ ] **Step 2:** Build `main.dart`: one screen with an Install button and a progress counter, a Self-test result list, and a Start server button that opens a `WebViewWidget`. The WebView loads `http://127.0.0.1:<port>/` with header `Authorization: Basic base64("opencode:<pw>")`. The screen also has a "Show log" bottom sheet.
- [ ] **Step 3: Verify on the emulator:**
  1. `sdkmanager "system-images;android-36;google_apis_playstore;x86_64"` (if missing), then create an AVD with 4 GB RAM and 8 GB storage.
  2. `wsl bash tool/copy_rootfs.sh`, then `cd app && flutter run -d emulator-5554`.
  3. Install completes. The self-test shows `x86_64`, `v2x.x`, `Python 3.1x` and `1.18.3`. The OpenCode web UI renders in the WebView.
- [ ] **Step 4: Commit** `feat: spike UI running opencode serve in webview`.

### Task 8: Spike measurements and go/no-go

**Files:** Create `docs/spike-results.md`.

- [ ] **Step 1:** On the emulator, and on a real arm64 phone once you connect one with USB debugging, record:
  - install time
  - rootfs size on disk (`du -sh` in the self-test shell)
  - server cold start (from `start()` call to healthy)
  - RSS of the proot and opencode processes (`adb shell dumpsys meminfo dev.opencodeapk.app`)
  - whether `PROOT_NO_SECCOMP` retry triggered
  - a chat round-trip with a real API key that creates `/root/projects/hello.py` and runs it
- [ ] **Step 2:** Write a verdict. **GO** if the server becomes healthy in under 20 s on the phone and the chat/tool round-trip works. **NO-GO** triggers the spec's fallback: the npm `opencode-ai` package on Alpine's node. Write that up as a follow-up plan.
- [ ] **Step 3: Commit** `docs: phase 0 spike results`.

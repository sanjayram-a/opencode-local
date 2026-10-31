# Third-party notices

This repository contains original project code and third-party software. The
license in [`LICENSE`](LICENSE) (GPL-3.0-or-later) applies to the original
project code. Third-party components remain under their own licenses.

## proot

- **Version:** 5.1.107.101 (Termux package)
- **License:** GPL-2.0-or-later (Termux package metadata says GPL-2.0)
- **Source:** <https://github.com/termux/proot>
- **Build recipe:** <https://github.com/termux/termux-packages/tree/master/packages/proot>
- **Binary in this repo:** `packages/opencode_runtime/android/src/main/jniLibs/*/libproot.so`,
  `libproot-loader.so`
- **Changes:** the binaries are patched with `patchelf` to remove the Termux
  RUNPATH and rename the talloc dependency. The exact commands are in
  `native/proot/fetch.sh`. No proot source code is modified.

## talloc

- **Version:** 2.5.0 (Termux package)
- **License:** Termux package metadata lists GPL-3.0; upstream Samba talloc is
  LGPL-3.0-or-later. Treat the shipped binary as GPL-3.0 until this discrepancy
  is reconciled.
- **Source:** <https://www.samba.org/ftp/talloc/>
- **Build recipe:** <https://github.com/termux/termux-packages/tree/master/packages/libtalloc>
- **Binary in this repo:** `packages/opencode_runtime/android/src/main/jniLibs/*/libtalloc.so`
- **Changes:** SONAME changed to `libtalloc.so` with `patchelf`; no source changes.

## libandroid-shmem

- **Version:** 0.7 (Termux package)
- **License:** BSD-3-Clause
- **Source:** <https://github.com/termux/libandroid-shmem>
- **Binary in this repo:** `packages/opencode_runtime/android/src/main/jniLibs/*/libandroid-shmem.so`
- **Changes:** RUNPATH removed with `patchelf`; no source changes.

## OpenCode

- **Version:** 1.19.4
- **License:** MIT
- **Source:** <https://github.com/sst/opencode>
- **Binary:** downloaded into the Alpine rootfs by `rootfs/build.sh`; not stored in
  this repository.

## Alpine Linux rootfs

- **Version:** Alpine 3.24, rootfs version `2026.10.0`
- **Packages:** see `rootfs/versions.env`
- **Licenses:** each Alpine package keeps its own license. The packed rootfs
  contains per-package metadata in `/lib/apk/db/installed`; those notices are
  generated when the rootfs is packed.
- **Source:** <https://gitlab.alpinelinux.org/alpine/aports>

## Flutter, AndroidX and other app dependencies

- **Flutter:** BSD-3-Clause
- **AndroidX / Jetpack:** Apache-2.0
- **Other Dart/Kotlin dependencies:** see `app/pubspec.lock` and the Gradle
  dependency reports. Their license texts are in `LICENSES/`.

Full license texts for the main third-party licenses are in [`LICENSES/`](LICENSES/).

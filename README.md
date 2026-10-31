# opencode-local

An experimental Android app that runs the [OpenCode](https://github.com/sst/opencode)
server inside an Alpine Linux userland using proot, then opens the OpenCode web UI
in a WebView.

> **Status:** Phase 0/1 spike. Not production-ready. The server lifecycle,
> foreground service, release packaging and arm64 asset are still in progress.
> This is an unofficial project and is not affiliated with the OpenCode project.

## Layout

- `app/` — Flutter Android app and spike UI.
- `packages/opencode_runtime/` — Kotlin runtime: rootfs installer, proot launcher,
  server supervisor and PTY bridge.
- `native/proot/` — fetches and patches the Termux proot binaries.
- `rootfs/` — builds the Alpine + OpenCode rootfs.
- `docs/` — design notes and spike results.

## Build

The app uses Flutter and the Android Gradle plugin. The rootfs asset is not
committed; build it with `rootfs/build.sh`, pack it with `rootfs/pack.sh`, and
copy it with `tool/copy_rootfs.sh <abi>`.

## License

Original project code is licensed under **GPL-3.0-or-later**. Third-party
components remain under their own licenses; see
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and
[`SOURCE_OFFER.md`](SOURCE_OFFER.md). Full license texts are in
[`LICENSES/`](LICENSES/).

“OpenCode” is a trademark of its respective owner. This project does not grant
any trademark rights.

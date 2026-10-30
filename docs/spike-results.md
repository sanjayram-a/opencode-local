# Phase 0 spike results

## Rootfs (built by `rootfs/build.sh`, Alpine 3.24.3, OpenCode 1.19.4)

| ABI | compressed (.tar.zst, -19) | uncompressed | tar entries |
|---|---|---|---|
| arm64-v8a | 112.3 MB | 393 MB | 5,991 |
| x86_64 | 115.0 MB | 398 MB | 5,992 |

Contents: bash, git, curl, ripgrep, openssh-client, ca-certificates, Node v24.22.1 + npm 11.12.1,
Python 3.14.10 + pip, uv 0.11.19, tzdata, OpenCode 1.19.4 (musl). Verified with `chroot` in Docker
(arm64 under qemu): `uname -m`, `node -v`, `python3 -V`, `opencode --version` all succeed.

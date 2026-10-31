# Source offer

This repository distributes GPL/LGPL binaries for the Android runtime. The
corresponding source is available from the upstream projects at the exact
versions listed below. If you need a source copy from this project, open an
issue at <https://github.com/sanjayram-a/opencode-local/issues>.

| Component | Version | Source |
|---|---|---|
| proot | 5.1.107.101 | <https://github.com/termux/proot> |
| talloc | 2.5.0 | <https://www.samba.org/ftp/talloc/> |
| libandroid-shmem | 0.7 | <https://github.com/termux/libandroid-shmem> |
| Alpine packages | Alpine 3.24 | <https://gitlab.alpinelinux.org/alpine/aports> |
| OpenCode | 1.19.4 | <https://github.com/sst/opencode> |

Build recipes and exact package URLs are in `native/proot/SOURCES.md` and
`rootfs/versions.env`. The rootfs package list is in `rootfs/versions.env`; the
packed rootfs includes `/lib/apk/db/installed` with per-package license metadata.

This offer is valid for at least three years from the date of distribution.

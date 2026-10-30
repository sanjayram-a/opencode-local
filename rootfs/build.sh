#!/usr/bin/env bash
# Build the Alpine rootfs for one ABI with Docker (uses Docker's qemu emulation for arm64).
#   bash rootfs/build.sh arm64     -> out/rootfs-arm64-v8a.tar.zst
#   bash rootfs/build.sh x86_64    -> out/rootfs-x86_64.tar.zst
set -euo pipefail
cd "$(dirname "$0")/.."
. rootfs/versions.env
case "${1:-}" in
  arm64)  ABI=arm64-v8a; PLATFORM=linux/arm64; OC_ARCH=arm64; ARCH=aarch64 ;;
  x86_64) ABI=x86_64;    PLATFORM=linux/amd64; OC_ARCH=x64;   ARCH=x86_64 ;;
  *) echo "usage: $0 arm64|x86_64" >&2; exit 2 ;;
esac
# Docker on Windows (Git Bash) needs a Windows path for the volume and no MSYS path mangling.
SRC="$PWD"; command -v cygpath >/dev/null && SRC="$(cygpath -w "$PWD")"
export MSYS_NO_PATHCONV=1
NAME="ocrootfs-$ABI"
mkdir -p rootfs/work out
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run --name "$NAME" --platform "$PLATFORM" -v "$SRC:/w" "alpine:$ALPINE" sh /w/rootfs/setup.sh "$OC_ARCH"
docker export "$NAME" -o "rootfs/work/$ABI.export.tar"
docker rm "$NAME" >/dev/null
docker run --rm -v "$SRC:/w" "alpine:$ALPINE" sh /w/rootfs/pack.sh "$ABI" "$ARCH"
rm -f "rootfs/work/$ABI.export.tar"
ls -l "out/rootfs-$ABI.tar.zst"

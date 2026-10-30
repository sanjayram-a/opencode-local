#!/usr/bin/env bash
# Copy built rootfs archives into the app's assets (Phase 0/1; Phase 2 moves them to a Play asset pack).
#   bash tool/copy_rootfs.sh               -> both ABIs
#   bash tool/copy_rootfs.sh x86_64        -> emulator only (smaller debug APK)
set -euo pipefail
cd "$(dirname "$0")/.."
. rootfs/versions.env
DST=app/android/app/src/main/assets/rootfs
rm -rf "$DST"; mkdir -p "$DST"
for abi in "${@:-arm64-v8a x86_64}"; do
  for a in $abi; do cp "out/rootfs-$a.tar.zst" "$DST/"; done
done
printf '%s\n' "$ROOTFS_VERSION" > "$DST/rootfs.version"
ls -l "$DST"

#!/bin/sh
# Runs INSIDE a plain alpine container. Turns a `docker export` tar into rootfs-<abi>.tar.zst whose LAST entry
# is ./.manifest.json (the installer relies on that to know the archive is complete).
set -eu
. /w/rootfs/versions.env
ABI="$1"; ARCH="$2"
apk add --no-cache tar zstd >/dev/null
mkdir /r
tar -xpf "/w/rootfs/work/$ABI.export.tar" -C /r --numeric-owner
rm -f /r/.dockerenv
printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' > /r/etc/resolv.conf
printf '127.0.0.1 localhost\n::1 localhost ip6-localhost\n' > /r/etc/hosts
printf 'opencode\n' > /r/etc/hostname
printf '{"version":"%s","opencode":"%s","arch":"%s","alpine":"%s"}\n' \
  "$ROOTFS_VERSION" "$OPENCODE_VERSION" "$ARCH" "$(cat /r/etc/alpine-release)" > /r/.manifest.json
cd /r
tar --sort=name --numeric-owner --owner=0 --group=0 --exclude=./.manifest.json -cf /tmp/r.tar .
tar --numeric-owner --owner=0 --group=0 -rf /tmp/r.tar ./.manifest.json
mkdir -p /w/out
zstd -19 -T0 -q -f /tmp/r.tar -o "/w/out/rootfs-$ABI.tar.zst"
echo "entries: $(tar -tf /tmp/r.tar | wc -l)  uncompressed: $(du -sh /r | cut -f1)"

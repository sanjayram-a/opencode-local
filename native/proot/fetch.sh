#!/usr/bin/env bash
# Fetch Termux's prebuilt proot + libtalloc for arm64-v8a and x86_64 and patch them so they can
# run from the app's nativeLibraryDir (no Termux prefix, no RUNPATH, lib*.so names only).
#
# Run from the repo root inside Docker (needs root for apt):
#   docker run --rm -v "$PWD:/w" -w /w debian:bookworm-slim bash native/proot/fetch.sh
set -euo pipefail

REPO="https://packages.termux.dev/apt/termux-main"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$HERE/work"
OUT="$HERE/../../packages/opencode_runtime/android/src/main/jniLibs"
PREFIX="data/data/com.termux/files/usr"

if ! command -v patchelf >/dev/null || ! command -v curl >/dev/null || ! command -v readelf >/dev/null; then
  apt-get update -qq && apt-get install -y -qq --no-install-recommends patchelf curl ca-certificates binutils file >/dev/null
fi

# field <Packages-file> <package> <Field>: print one field of one stanza.
field() {
  awk -v pkg="$2" -v key="$3" '
    $0 == "Package: " pkg { hit = 1; next }
    /^Package: / { hit = 0 }
    hit && index($0, key ": ") == 1 { print substr($0, length(key) + 3); exit }
  ' "$1"
}

{
  echo "# proot binaries — sources"
  echo
  echo "Fetched by \`native/proot/fetch.sh\` from the Termux apt repo ($REPO)."
  echo "proot is GPL-2.0-or-later (source: https://github.com/termux/proot); talloc upstream is LGPL-3.0-or-later (source: https://www.samba.org/ftp/talloc/), but the Termux package metadata lists GPL-3.0. Treat the shipped talloc as GPL-3.0 until this discrepancy is reconciled."
  echo "Build recipes: https://github.com/termux/termux-packages/tree/master/packages/{proot,libtalloc}"
  echo
} > "$HERE/SOURCES.md"

for pair in aarch64:arm64-v8a x86_64:x86_64; do
  arch="${pair%%:*}"; abi="${pair##*:}"
  dir="$WORK/$arch"; rm -rf "$dir" "$OUT/$abi"; mkdir -p "$dir/x" "$OUT/$abi"
  curl -fsSL "$REPO/dists/stable/main/binary-$arch/Packages" -o "$dir/Packages"

  for pkg in proot libtalloc libandroid-shmem; do
    file="$(field "$dir/Packages" "$pkg" Filename)"
    sha="$(field "$dir/Packages" "$pkg" SHA256)"
    ver="$(field "$dir/Packages" "$pkg" Version)"
    [ -n "$file" ] && [ -n "$sha" ] || { echo "package $pkg not found for $arch" >&2; exit 1; }
    curl -fsSL "$REPO/$file" -o "$dir/$pkg.deb"
    echo "$sha  $dir/$pkg.deb" | sha256sum -c --quiet -
    dpkg-deb -x "$dir/$pkg.deb" "$dir/x"
    echo "- $abi: \`$pkg\` $ver — $REPO/$file (sha256 $sha)" >> "$HERE/SOURCES.md"
  done

  cp "$dir/x/$PREFIX/bin/proot"             "$OUT/$abi/libproot.so"
  cp "$dir/x/$PREFIX/libexec/proot/loader"  "$OUT/$abi/libproot-loader.so"
  cp "$(ls "$dir/x/$PREFIX/lib/"libtalloc.so.2* | sort | tail -1)" "$OUT/$abi/libtalloc.so"
  cp "$dir/x/$PREFIX/lib/libandroid-shmem.so" "$OUT/$abi/libandroid-shmem.so"
  chmod 0755 "$OUT/$abi/"*.so

  patchelf --remove-rpath --replace-needed libtalloc.so.2 libtalloc.so "$OUT/$abi/libproot.so"
  patchelf --remove-rpath --set-soname libtalloc.so "$OUT/$abi/libtalloc.so"
  patchelf --remove-rpath "$OUT/$abi/libandroid-shmem.so"
  # Every NEEDED entry must be either one of ours or an Android system library.
  for f in libproot.so libtalloc.so libandroid-shmem.so; do
    for need in $(readelf -d "$OUT/$abi/$f" | awk -F'[][]' '/NEEDED/ { print $2 }'); do
      case "$need" in
        libtalloc.so|libandroid-shmem.so|libc.so|libdl.so|libm.so|liblog.so) ;;
        *) echo "$abi/$f needs unexpected $need" >&2; exit 1 ;;
      esac
    done
  done
done

{
  echo
  echo "## ELF LOAD alignment (Play requires 16 KB = 0x4000 for targetSdk 35+)"
  echo
  for abi in arm64-v8a x86_64; do
    for f in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
      align="$(readelf -lW "$OUT/$abi/$f" | awk '$1 == "LOAD" { print $NF }' | sort -u | tr '\n' ' ')"
      echo "- $abi/$f: $align"
    done
  done
  echo
  echo "arm64-v8a: every LOAD is >= 0x4000, so 16 KB page devices and the Play 16 KB rule are satisfied."
  echo "x86_64: has 0x1000 LOAD segments. It is for the emulator (4 KB page image) only and must not ship in a"
  echo "Play release unless rebuilt from source with -Wl,-z,max-page-size=16384 (Phase 2 TODO)."
} >> "$HERE/SOURCES.md"

echo "done:"; ls -l "$OUT"/*/

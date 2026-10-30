#!/bin/sh
# Runs INSIDE an alpine container of the target arch. Installs the product layer into the container itself.
set -eu
. /w/rootfs/versions.env
OC_ARCH="$1"                      # arm64 | x64
SHA_VAR="OPENCODE_SHA256_${OC_ARCH}"
eval "SHA=\$$SHA_VAR"

apk add --no-cache $PACKAGES

URL="https://github.com/anomalyco/opencode/releases/download/v${OPENCODE_VERSION}/opencode-linux-${OC_ARCH}-musl.tar.gz"
mkdir -p /tmp/oc
curl -fsSL "$URL" -o /tmp/oc.tgz
echo "$SHA  /tmp/oc.tgz" | sha256sum -c -
tar -xzf /tmp/oc.tgz -C /tmp/oc
install -m 0755 "$(find /tmp/oc -type f -name opencode | head -1)" /usr/local/bin/opencode

# root's login shell is bash; /root itself is replaced by a bind mount at runtime.
sed -i 's#^root:\(.*\):/bin/sh$#root:\1:/bin/bash#' /etc/passwd
mkdir -p /root/projects

rm -rf /usr/share/man /usr/share/doc /usr/share/info /var/cache/apk/* /tmp/* /root/.cache /root/.npm

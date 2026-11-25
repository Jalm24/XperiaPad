#!/usr/bin/env bash
# Copyright (C) 2026 Jalm24
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Additional GPLv3 §7(b) term: this author attribution must be
# preserved in modified source files and in the project's legal notices.

# Build firmado para publicacion. La clave NO vive en el repo:
#   export XPD_KEYSTORE=/ruta/release.keystore
#   export XPD_STOREPASS=... XPD_KEYPASS=... XPD_KEYALIAS=xperiapad
# o definir XPD_RELEASE_ENV (por defecto ~/.config/XperiaPad/release.env).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${XPD_RELEASE_ENV:-$HOME/.config/XperiaPad/release.env}"
if [ -f "$ENV_FILE" ]; then
  # shellcheck disable=SC1090
  . "$ENV_FILE"
fi

: "${XPD_KEYSTORE:?falta XPD_KEYSTORE}"
: "${XPD_STOREPASS:?falta XPD_STOREPASS}"
: "${XPD_KEYPASS:?falta XPD_KEYPASS}"
: "${XPD_KEYALIAS:?falta XPD_KEYALIAS}"

if [ ! -f "$XPD_KEYSTORE" ]; then
  echo "ERROR: no existe $XPD_KEYSTORE" >&2
  exit 1
fi

JDK8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}"
SDK_CAND="$HOME/Android/Sdk"
if [ -d "$SDK_CAND/build-tools/25.0.3" ]; then
  SDK="$SDK_CAND"
else
  SDK="${ANDROID_SDK_ROOT:-$SDK_CAND}"
fi
BT="$SDK/build-tools/25.0.3"
APKSIGNER="$BT/apksigner"

# apksigner 25.0.3 necesita JDK 8 (en JDK 9+ falla por sun.security.x509).
export JAVA_HOME="$JDK8"
export PATH="$JDK8/bin:$PATH"

echo "==> build comun (debug + nativo)"
bash "$ROOT/src/build.sh"

ALIGNED="$ROOT/build/intermediates/XperiaPad-aligned.apk"
SIGNED="$ROOT/out/apk/XperiaPad-release.apk"

echo "==> signing (release alias=$XPD_KEYALIAS; v1+v2, SHA-256)"
"$APKSIGNER" sign \
  --ks "$XPD_KEYSTORE" \
  --ks-pass "pass:$XPD_STOREPASS" \
  --key-pass "pass:$XPD_KEYPASS" \
  --ks-key-alias "$XPD_KEYALIAS" \
  --min-sdk-version 10 \
  --out "$SIGNED" "$ALIGNED"

echo "==> verify"
"$APKSIGNER" verify --verbose --print-certs "$SIGNED" | head -n 6

echo "==> OK: $SIGNED"

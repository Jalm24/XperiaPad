# Copyright (C) 2026 Jalm24
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Additional GPLv3 §7(b) term: this author attribution must be
# preserved in modified source files and in the project's legal notices.

#!/usr/bin/env bash
set -euo pipefail

# Build XperiaPad: intermedios en build/, resultado en out/
# Requiere: JDK 8, Android SDK (platforms/android-10, build-tools/25.0.3), NDK r16b.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/src"
META="$ROOT/meta"
BUILD="$ROOT/build"
OUT="$ROOT/out"

SDK_CAND="$HOME/Android/Sdk"
if [ -d "$SDK_CAND/build-tools/25.0.3" ]; then
  SDK="$SDK_CAND"
else
  SDK="${ANDROID_SDK_ROOT:-$SDK_CAND}"
fi
BT="$SDK/build-tools/25.0.3"
PLAT="$SDK/platforms/android-10/android.jar"
NDK="${NDK_HOME:-$SDK/ndk/16.1.4479499}"
JDK8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}"
JAVAC="$JDK8/bin/javac"
JARSIGNER="$JDK8/bin/jarsigner"
KEYTOOL="$JDK8/bin/keytool"

AAPT="$BT/aapt"
DX="$BT/dx"
ZIPALIGN="$BT/zipalign"

GEN="$BUILD/gen"
INT="$BUILD/intermediates"
NAT="$BUILD/native"
APKDIR="$OUT/apk"
mkdir -p "$GEN" "$INT/classes" "$INT/dex" "$INT/assets" "$INT/res/drawable-mdpi" "$INT/res/drawable-hdpi" "$INT/res/drawable-xhdpi" "$INT/res/values" "$NAT" "$APKDIR" "$OUT/logs"

echo "==> icons from meta/icon/app_icon_512.png"
ICON="$META/icon/app_icon_512.png"
if [ -f "$ICON" ]; then
  magick "$ICON" -resize 48x48  "$INT/res/drawable-mdpi/ic_launcher.png"
  magick "$ICON" -resize 72x72  "$INT/res/drawable-hdpi/ic_launcher.png"
  magick "$ICON" -resize 96x96  "$INT/res/drawable-xhdpi/ic_launcher.png"
else
  echo "WARN: missing $ICON"
fi

# Recursos localized: ingles (values/) + espanol (values-es/)
cp -r "$SRC/app/res/." "$INT/res/"

echo "==> aapt: R.java and resources"
"$AAPT" package -f -m \
  -J "$GEN" \
  -M "$META/android/AndroidManifest.xml" \
  -S "$INT/res" \
  -I "$PLAT"

echo "==> assets: framework patch disabled"
# P1 removed (E27): no patched jar is embedded (framework stays stock).
# El fallback historico vive en /tmp/opencode/p1-safe-build-0.62/ y en docs/.

echo "==> assets: legal notices"
mkdir -p "$INT/assets/legal"
cp "$ROOT/LICENSE" "$INT/assets/legal/LICENSE"
cp "$ROOT/NOTICE" "$INT/assets/legal/NOTICE"
cp "$ROOT/CREDITS.md" "$INT/assets/legal/CREDITS.md"
cp "$ROOT/CREDITS-en.md" "$INT/assets/legal/CREDITS-en.md"

echo "==> javac"
"$JAVAC" -source 1.6 -target 1.6 -bootclasspath "$PLAT" -classpath "$PLAT" \
  -d "$INT/classes" \
  $(find "$GEN" "$SRC/app/java" -name '*.java')

echo "==> dx"
"$DX" --dex --output="$INT/dex/classes.dex" "$INT/classes"

echo "==> ndk-build (daemon xpd)"
"$NDK/ndk-build" \
  NDK_PROJECT_PATH="$NAT" \
  APP_BUILD_SCRIPT="$SRC/daemon/jni/Android.mk" \
  NDK_APPLICATION_MK="$SRC/daemon/jni/Application.mk" \
  NDK_OUT="$NAT/obj" \
  NDK_LIBS_OUT="$NAT/libs"

echo "==> packaging APK"
UNSIGNED="$INT/XperiaPad-unsigned.apk"
"$AAPT" package -f \
  -M "$META/android/AndroidManifest.xml" \
  -S "$INT/res" \
  -A "$INT/assets" \
  -I "$PLAT" \
  -F "$UNSIGNED" \
  "$INT/dex"

# añadir daemon como lib nativa (extraída por el instalador en /data/data/<pkg>/lib/)
XPD_BIN="$NAT/libs/armeabi-v7a/xpd"
if [ -f "$XPD_BIN" ]; then
  mkdir -p "$INT/lib/armeabi-v7a"
  cp "$XPD_BIN" "$INT/lib/armeabi-v7a/libxpd.so"
  (cd "$INT" && zip -q "$UNSIGNED" lib/armeabi-v7a/libxpd.so)
else
  echo "WARN: $XPD_BIN not found"
fi
echo "==> zipalign"
ALIGNED="$INT/XperiaPad-aligned.apk"
"$ZIPALIGN" -f 4 "$UNSIGNED" "$ALIGNED"

echo "==> signing (debug)"
DEBUG_KS="$BUILD/debug.keystore"
if [ ! -f "$DEBUG_KS" ]; then
  "$KEYTOOL" -genkeypair -v -keystore "$DEBUG_KS" -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$JARSIGNER" -sigalg SHA1withRSA -digestalg SHA1 -keystore "$DEBUG_KS" -storepass android -keypass android \
  -signedjar "$APKDIR/XperiaPad-debug.apk" "$ALIGNED" androiddebugkey

echo "==> OK: $APKDIR/XperiaPad-debug.apk"

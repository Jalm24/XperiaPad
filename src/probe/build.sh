#!/usr/bin/env bash
set -euo pipefail
# APK-sonda de permisos (sin root). Artefactos en build/probe/, out/apk/.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/src/probe"
META="$ROOT/meta/android/probe/AndroidManifest.xml"
BUILD="$ROOT/build/probe"
OUT="$ROOT/out/apk"
SDK="$HOME/Android/Sdk"
BT="$SDK/build-tools/25.0.3"
PLAT="$SDK/platforms/android-10/android.jar"
NDK="${NDK_HOME:-$SDK/ndk/16.1.4479499}"
JDK8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}"
JAVAC="$JDK8/bin/javac"
JARSIGNER="$JDK8/bin/jarsigner"
KS="$ROOT/build/debug.keystore"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/res" "$BUILD/lib/armeabi-v7a" "$OUT"

echo "==> aapt"
"$BT/aapt" package -f -m -J "$BUILD/gen" -M "$META" -I "$PLAT" -S "$BUILD/res"

echo "==> javac"
"$JAVAC" -source 1.6 -target 1.6 -bootclasspath "$PLAT" -classpath "$PLAT" \
  -d "$BUILD/classes" $(find "$BUILD/gen" "$SRC/java" -name '*.java')

echo "==> dx"
"$BT/dx" --dex --output="$BUILD/dex/classes.dex" "$BUILD/classes"

echo "==> ndk-build (libprobe.so)"
"$NDK/ndk-build" \
  NDK_PROJECT_PATH="$SRC" \
  APP_BUILD_SCRIPT="$SRC/jni/Android.mk" \
  NDK_APPLICATION_MK="$SRC/jni/Application.mk" \
  NDK_OUT="$BUILD/obj" \
  NDK_LIBS_OUT="$BUILD/native"

LIB="$BUILD/native/armeabi-v7a/libprobe.so"
if [ ! -f "$LIB" ]; then echo "ERROR: falta $LIB"; exit 1; fi

echo "==> empaquetar"
UNSIGNED="$BUILD/probe-unsigned.apk"
"$BT/aapt" package -f -M "$META" -I "$PLAT" -F "$UNSIGNED" "$BUILD/dex"
cp "$LIB" "$BUILD/lib/armeabi-v7a/libprobe.so"
(cd "$BUILD" && zip -q "$UNSIGNED" lib/armeabi-v7a/libprobe.so)

echo "==> zipalign + firma"
"$BT/zipalign" -f 4 "$UNSIGNED" "$BUILD/probe-aligned.apk"
"$JARSIGNER" -sigalg SHA1withRSA -digestalg SHA1 -keystore "$KS" -storepass android \
  -keypass android -signedjar "$OUT/XPadProbe.apk" "$BUILD/probe-aligned.apk" androiddebugkey
echo "==> OK: $OUT/XPadProbe.apk"

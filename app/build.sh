#!/usr/bin/env bash
# ==============================================================================
# tvOS Detox: Build Script for Apple-Style Android TV Home Launcher
# Builds bin/tvhome.apk using Android SDK tools (aapt2, javac, d8, zipalign, apksigner)
# ==============================================================================

set -euo pipefail
cd "$(dirname "$0")"

echo "==> Building tvOS Detox Launcher..."

# Locate Java 17+
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64; do
    if [ -d "$j" ]; then
      export JAVA_HOME="$j"
      break
    fi
  done
fi

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Locate Android SDK
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do
    if [ -d "$d" ]; then
      SDK="$d"
      break
    fi
  done
fi

if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "[-] Error: Android SDK not found." >&2
  echo "    Set ANDROID_HOME or install platforms;android-34 and build-tools;34.0.0" >&2
  exit 1
fi

# Auto-detect latest build-tools and android platform
BUILD_TOOLS_DIR=$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)
PLATFORM_DIR=$(find "$SDK/platforms" -maxdepth 1 -name "android-*" | sort -V | tail -n 1)
JAR="$PLATFORM_DIR/android.jar"

if [ ! -f "$JAR" ] || [ ! -d "$BUILD_TOOLS_DIR" ]; then
  echo "[-] Error: Missing android.jar or build-tools in $SDK" >&2
  exit 1
fi

echo "    Using Build Tools: $BUILD_TOOLS_DIR"
echo "    Using Platform:    $PLATFORM_DIR"

rm -rf out && mkdir -p out/classes out/dex out/gen/com/ansub/tvhome

# 1. Compile resources with aapt2
"$BUILD_TOOLS_DIR/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34

# 2. Compile Java source
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$JAR" \
  -d out/classes $(find src -name "*.java")

# 3. Dex with d8
"$BUILD_TOOLS_DIR/d8" --min-api 21 --release --lib "$JAR" --output out/dex $(find out/classes -name "*.class")

# 4. Package classes.dex into APK
cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)

# 5. Zipalign
"$BUILD_TOOLS_DIR/zipalign" -f 4 out/unsigned.apk out/aligned.apk

# 6. Sign APK
KEYSTORE="${KEYSTORE:-key.jks}"
PASS="${KEYSTORE_PASS:-tvhome123}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$PASS" -keypass "$PASS" -alias tvhome \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
fi

mkdir -p ../bin
"$BUILD_TOOLS_DIR/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$PASS" --out ../bin/tvhome.apk out/aligned.apk

echo "[+] Successfully built and signed: ../bin/tvhome.apk ($(ls -lh ../bin/tvhome.apk | awk '{print $5}'))"

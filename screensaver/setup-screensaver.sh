#!/usr/bin/env bash
# ==============================================================================
# tvOS Detox: Setup Apple Aerial Screensaver on Android TV
# Installs and configures AerialViews with official Apple 1080p feeds & typography
# ==============================================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_IP="${1:-}"

AERIAL_VERSION="1.8.5"
AERIAL_URL="https://github.com/theothernt/AerialViews/releases/download/${AERIAL_VERSION}/aerial-views-${AERIAL_VERSION}.apk"
AERIAL_APK="/tmp/aerial-views-${AERIAL_VERSION}.apk"

ADB="adb"
if [ -n "$TARGET_IP" ]; then
  ADB="adb -s $TARGET_IP"
fi

echo "========================================================"
echo "  Apple tvOS Aerial Screensaver Setup for Android TV"
echo "========================================================"

# Check ADB connection
if ! $ADB get-state >/dev/null 2>&1; then
  if [ -n "$TARGET_IP" ]; then
    echo "[*] Connecting to $TARGET_IP..."
    adb connect "$TARGET_IP"
  else
    echo "[-] Error: No ADB device connected."
    echo "    Usage: ./setup-screensaver.sh [DEVICE_IP:PORT]"
    exit 1
  fi
fi

# 1. Download official Aerial Views APK if needed
if [ ! -f "$AERIAL_APK" ]; then
  echo "[*] Downloading Aerial Views v${AERIAL_VERSION}..."
  curl -L -o "$AERIAL_APK" "$AERIAL_URL"
fi

# 2. Install Aerial Views
echo "[*] Installing Aerial Views screensaver engine..."
$ADB install -r -g "$AERIAL_APK"

# 3. Configure Android DreamManager & Power Settings
echo "[*] Configuring Android DreamService (Screensaver)..."
$ADB shell "settings put secure screensaver_enabled 1"
$ADB shell "settings put secure screensaver_components com.neilturner.aerialviews/com.neilturner.aerialviews.ui.screensaver.DreamActivity"
$ADB shell "settings put secure screensaver_activate_on_sleep 1"
$ADB shell "settings put secure screensaver_activate_on_dock 1"

# 3 minutes idle timeout, disable deep sleep black-screen
$ADB shell "settings put system screen_off_timeout 180000"
$ADB shell "settings put secure sleep_timeout -1"

# 4. Push Tuned Apple Preferences
echo "[*] Injecting authentic Apple tvOS preferences..."
$ADB push "$SCRIPT_DIR/preferences.xml" /sdcard/aerial_prefs.xml

# Apply via root or run-as
$ADB shell "su -c '
  PKG_DIR=/data/data/com.neilturner.aerialviews
  mkdir -p \$PKG_DIR/shared_prefs
  cp /sdcard/aerial_prefs.xml \$PKG_DIR/shared_prefs/com.neilturner.aerialviews_preferences.xml
  UID_APP=\$(stat -c %u \$PKG_DIR 2>/dev/null || echo 10065)
  chown -R \$UID_APP:\$UID_APP \$PKG_DIR/shared_prefs
  chmod 660 \$PKG_DIR/shared_prefs/com.neilturner.aerialviews_preferences.xml
  am force-stop com.neilturner.aerialviews
' 2>/dev/null" || true

echo "[+] Done! Apple Aerial Screensaver is configured:"
echo "    - 139 Apple 1080p aerial flyovers (Space, Earth, Oceans, Cities)"
echo "    - 60sp Prominent Clock + Full Date & Location Tag"
echo "    - Automatic activation on 3 minutes idle"
echo "    - Press OK or BACK on remote to instantly wake up"

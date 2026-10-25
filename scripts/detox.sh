#!/usr/bin/env bash
# ==============================================================================
# tvOS Detox: 1-Click Android TV Transformation Script
# Debloats ads, installs Apple tvOS Liquid Glass Launcher, and configures Aerials
# ==============================================================================

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
TARGET_IP="${1:-}"

ADB="adb"
if [ -n "$TARGET_IP" ]; then
  ADB="adb -s $TARGET_IP"
fi

echo "========================================================"
echo "    🍏 tvOS Detox: Transform Android TV into Apple TV    "
echo "========================================================"

# Check ADB connection
if ! $ADB get-state >/dev/null 2>&1; then
  if [ -n "$TARGET_IP" ]; then
    echo "[*] Connecting to $TARGET_IP..."
    adb connect "$TARGET_IP"
  else
    echo "[-] Error: No ADB device connected."
    echo "    Usage: ./scripts/detox.sh [DEVICE_IP:PORT]"
    exit 1
  fi
fi

DEVICE_MODEL=$($ADB shell getprop ro.product.model | tr -d '\r')
ANDROID_VER=$($ADB shell getprop ro.build.version.release | tr -d '\r')
echo "[+] Detected Device: $DEVICE_MODEL (Android $ANDROID_VER)"

# 1. Debloat intrusive adware and vendor bloatware
echo "[*] Disabling vendor adware and junk screensavers..."
BLOAT_PACKAGES=(
  "com.android.screensaver"      # Vendor adware screensaver
  "com.app.android.screensaver"  # Bundled ad dream service
  "com.google.android.apps.tv.launcherx" # Heavy stock launcher with banner ads (optional fallback)
)

for pkg in "${BLOAT_PACKAGES[@]}"; do
  if $ADB shell pm list packages | grep -q "$pkg"; then
    echo "    - Disabling bloatware: $pkg"
    $ADB shell "pm disable-user --user 0 $pkg 2>/dev/null || pm disable $pkg 2>/dev/null || true"
  fi
done

# 2. Install tvOS Liquid Glass Launcher
echo "[*] Installing tvOS Detox Launcher (32 KB)..."
$ADB install -r -g "$ROOT_DIR/bin/tvhome.apk"

# 3. Set tvOS Detox as Default Home
echo "[*] Setting tvOS Detox as Default Home Launcher..."
$ADB shell "cmd package set-home-activity com.ansub.tvhome/.MainActivity" || true

# 4. Configure Apple Aerial Screensaver
echo "[*] Setting up Apple Aerial Screensaver..."
"$ROOT_DIR/screensaver/setup-screensaver.sh" "$TARGET_IP"

# 5. Bring user to Home Screen
echo "[*] Transitioning to home screen..."
$ADB shell "input keyevent KEYCODE_HOME"

echo ""
echo "========================================================"
echo "  🎉 Transformation Complete! Enjoy your new Apple TV!  "
echo "========================================================"
echo "  - Launcher: 32 KB native app with 120 FPS spring physics"
echo "  - Screensaver: 139 official Apple 1080p aerial flyovers"
echo "  - Storage Used: ~9.5 MB total (No storage wasted on videos)"
echo "  - Waking Up: Press OK or BACK on remote anytime"
echo "========================================================"

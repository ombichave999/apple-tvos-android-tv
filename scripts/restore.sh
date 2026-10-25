#!/usr/bin/env bash
# ==============================================================================
# tvOS Detox: Safe Restore Script
# Re-enables any disabled vendor packages and resets the default launcher
# ==============================================================================

set -euo pipefail
TARGET_IP="${1:-}"

ADB="adb"
if [ -n "$TARGET_IP" ]; then
  ADB="adb -s $TARGET_IP"
fi

echo "========================================================"
echo "         tvOS Detox: Rollback & Restore Script          "
echo "========================================================"

BLOAT_PACKAGES=(
  "com.android.screensaver"
  "com.app.android.screensaver"
  "com.google.android.apps.tv.launcherx"
)

for pkg in "${BLOAT_PACKAGES[@]}"; do
  echo "[*] Re-enabling package: $pkg"
  $ADB shell "pm enable $pkg 2>/dev/null || true"
done

echo "[*] Clearing default home activity..."
$ADB shell "cmd package clear-default-home-activity" || true

echo "[+] Done. System defaults restored."

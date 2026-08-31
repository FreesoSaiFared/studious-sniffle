#!/data/data/com.termux/files/usr/bin/bash
# Explicit install/rollback boundary for Self-Nudge Tier-3 builds.
set -euo pipefail
STATE="${STATE_DIR:-$HOME/.local/share/self-nudge-build}"
mode="current"
transport="package-installer"
confirmed=0

for arg in "$@"; do
  case "$arg" in
    --confirm-install) confirmed=1 ;;
    --rollback) mode="previous" ;;
    --adb) transport="adb" ;;
    *) echo "Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [ "$confirmed" -ne 1 ]; then
  cat >&2 <<EOF
No installation was performed.
Use:
  $0 --confirm-install
to open the current APK in Android's package installer.

Rollback:
  $0 --rollback --confirm-install

Owner/developer ADB install, only when explicitly desired:
  $0 --adb --confirm-install
EOF
  exit 3
fi

if [ "$mode" = previous ]; then apk="$STATE/previous-built.apk"; else apk="$STATE/last-built.apk"; fi
test -s "$apk" || { echo "APK_NOT_FOUND=$apk" >&2; exit 2; }

if [ "$transport" = adb ]; then
  command -v adb >/dev/null || { echo "MISSING_TOOL=adb" >&2; exit 2; }
  adb install -r "$apk"
  echo "INSTALL_TRANSPORT=ADB_EXPLICIT"
else
  command -v termux-open >/dev/null || { echo "MISSING_TOOL=termux-open" >&2; exit 2; }
  termux-open "$apk"
  echo "INSTALL_TRANSPORT=ANDROID_PACKAGE_INSTALLER"
fi

echo "INSTALL_REQUESTED_APK=$apk"

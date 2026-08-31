#!/data/data/com.termux/files/usr/bin/bash
# Self-Nudge Tier-3 direct Android build profile.
# Adapted from rune-lynx/brb-build@3a49b374b339d19202e3af024c6a5145487e94f9 (Apache-2.0).
set -euo pipefail

REPO="${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
ROOT="$REPO/ci/reconstruction"
APP="$ROOT/android/app/src/main"
CORE="$ROOT/core/src"
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
R8JAR="${R8JAR:-$HOME/android-sdk/r8-8.13.19.jar}"
R8_URL="${R8_URL:-https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.19/r8-8.13.19.jar}"
STATE="${STATE_DIR:-$HOME/.local/share/self-nudge-build}"
BUILD="$STATE/work"
KEYSTORE="${SELF_NUDGE_KEYSTORE:-$STATE/self-nudge.keystore}"
APK="$BUILD/self-nudge-tier3.apk"

need(){ command -v "$1" >/dev/null 2>&1 || { echo "MISSING_TOOL=$1" >&2; exit 2; }; }
for t in aapt2 javac java jar zip zipalign apksigner keytool sha256sum awk grep find sort; do need "$t"; done
test -f "$ANDROID_JAR" || { echo "MISSING_ANDROID_JAR=$ANDROID_JAR" >&2; exit 2; }
test -f "$APP/AndroidManifest.xml" || { echo "BAD_REPO=$REPO" >&2; exit 2; }

mkdir -p "$STATE"
if [ ! -s "$R8JAR" ]; then
  need curl
  mkdir -p "$(dirname "$R8JAR")"
  tmp="$R8JAR.tmp.$$"
  curl -fL --retry 3 "$R8_URL" -o "$tmp"
  test -s "$tmp"
  mv "$tmp" "$R8JAR"
fi

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -noprompt     -keystore "$KEYSTORE" -storepass android -keypass android     -alias selfnudge -dname "CN=Self-Nudge On-Device,O=Transductive,C=NL"     -keyalg RSA -keysize 2048 -validity 10000
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "=== AAPT2 COMPILE ==="
aapt2 compile --dir "$APP/res" -o "$BUILD/resources.zip"

echo "=== AAPT2 LINK + R.JAVA ==="
aapt2 link   -o "$BUILD/resources.apk"   --manifest "$APP/AndroidManifest.xml"   -I "$ANDROID_JAR"   --java "$BUILD/gen"   --min-sdk-version 26   --target-sdk-version 35   --version-code 1   --version-name 0.1-tier3   "$BUILD/resources.zip"

echo "=== JAVAC ==="
find "$CORE" "$APP/java" "$BUILD/gen" -name '*.java' -print | sort > "$BUILD/sources.list"
javac -source 8 -target 8 -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.list"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .

echo "=== D8 VIA MODERN R8 ==="
java -cp "$R8JAR" com.android.tools.r8.D8   --lib "$ANDROID_JAR"   --min-api 26   --output "$BUILD/dex"   "$BUILD/classes.jar"
test -s "$BUILD/dex/classes.dex"

echo "=== APK ASSEMBLY ==="
cp "$BUILD/resources.apk" "$BUILD/with-dex.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/with-dex.apk" classes*.dex)
zipalign -f -p 4 "$BUILD/with-dex.apk" "$BUILD/aligned.apk"
zipalign -c -p 4 "$BUILD/aligned.apk"

echo "=== APK SIGN ==="
apksigner sign   --ks "$KEYSTORE" --ks-key-alias selfnudge   --ks-pass pass:android --key-pass pass:android   --out "$APK" "$BUILD/aligned.apk"
apksigner verify --verbose --print-certs "$APK" | tee "$BUILD/apksigner-verify.txt"

sha="$(sha256sum "$APK" | awk '{print $1}')"
bytes="$(wc -c < "$APK" | tr -d ' ')"
git_sha="$(git -C "$REPO" rev-parse HEAD 2>/dev/null || printf unknown)"

if [ -f "$STATE/last-built.apk" ]; then
  cp -f "$STATE/last-built.apk" "$STATE/previous-built.apk"
fi
cp -f "$APK" "$STATE/last-built.apk"

cat > "$STATE/last-build-receipt.json" <<EOF
{
  "schema": "SELF_NUDGE_TIER3_ON_DEVICE_BUILD_RECEIPT/1",
  "status": "PASS",
  "git_sha": "$git_sha",
  "apk": {
    "path": "$STATE/last-built.apk",
    "bytes": $bytes,
    "sha256": "$sha"
  },
  "package": "science.transductive.nudge",
  "min_sdk": 26,
  "target_sdk": 35,
  "toolchain": {
    "android_jar": "$ANDROID_JAR",
    "r8_jar": "$R8JAR"
  },
  "install_performed": false,
  "acceptance_boundary": "BUILT_ON_ANDROID_DEVICE_NOT_INSTALLED_NOT_RUNTIME_VERIFIED"
}
EOF

echo "TIER3_ON_DEVICE_BUILD_PASS=1"
echo "APK_SHA256=$sha"
echo "APK_BYTES=$bytes"
echo "APK=$STATE/last-built.apk"
echo "INSTALL_NOT_PERFORMED=1"

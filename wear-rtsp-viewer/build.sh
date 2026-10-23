#!/usr/bin/env bash
# Builds the APK without Gradle, using only the standalone Android build tools.
#
# Requirements (Debian/Ubuntu): sudo apt install aapt apksigner dalvik-exchange zipalign openjdk-17-jdk
# plus an API 34 android.jar, passed as ANDROID_JAR (defaults to $ANDROID_HOME/platforms/android-34/android.jar).
# Set DEFAULT_STREAM_URL (and optionally HA_URL, HA_TOKEN, HA_CAMERA) to build an APK that already
# contains your camera.
set -euo pipefail

cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-$HOME/Android/Sdk}/platforms/android-34/android.jar}"
DX="$(command -v dalvik-exchange || command -v dx)"
SRC=app/src/main
OUT=build/manual
KEYSTORE="${KEYSTORE:-$OUT/debug.keystore}"

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/res.zip" "$OUT"/*.apk "$OUT/classes.dex"
mkdir -p "$OUT/gen" "$OUT/classes"

echo "== Compiling resources"
# The manifest has no package attribute (Gradle sets it from 'namespace'), so inject it here.
sed 's|<manifest |<manifest package="com.omerbitas.rtspwatch" |' "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
# Optionally bake settings into the APK so it works with no setup on the watch:
# DEFAULT_STREAM_URL (the camera), and HA_URL / HA_TOKEN / HA_CAMERA for viewing away from home.
rm -rf "$OUT/res" && cp -r "$SRC/res" "$OUT/res"
python3 - "$OUT/res/values/strings.xml" <<'PY'
import os, sys
from xml.sax.saxutils import escape
path = sys.argv[1]
text = open(path).read()
for env, name in [("DEFAULT_STREAM_URL", "default_stream_url"), ("HA_URL", "ha_url"),
                  ("HA_TOKEN", "ha_token"), ("HA_CAMERA", "ha_camera")]:
    value = os.environ.get(env, "")
    if value:
        value = escape(value).replace("'", "\\'").replace('"', '\\"')
        text = text.replace('name="%s" translatable="false"></string>' % name,
                            'name="%s" translatable="false">%s</string>' % (name, value))
open(path, "w").write(text)
PY
aapt2 compile --dir "$OUT/res" -o "$OUT/res.zip"
aapt2 link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 30 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 \
    "$OUT/res.zip"

echo "== Compiling Java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
    $(find "$SRC/java" "$OUT/gen" -name '*.java')

echo "== Dexing"
"$DX" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "== Aligning and signing"
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/RtspWatch.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/RtspWatch.apk"
echo "== Built $OUT/RtspWatch.apk"

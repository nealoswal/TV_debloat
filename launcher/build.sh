#!/usr/bin/env bash
# Builds Glass Home without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Needs a JDK (11+) on PATH and two pieces of the Android SDK:
#   BUILD_TOOLS  folder with aapt2, d8, zipalign, apksigner   (build-tools 34)
#   ANDROID_JAR  android.jar from platform 34
# e.g.  BUILD_TOOLS=~/sdk/build-tools/34.0.0 ANDROID_JAR=~/sdk/platforms/android-34/android.jar ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
BT=${BUILD_TOOLS:?set BUILD_TOOLS to an Android build-tools folder}
JAR=${ANDROID_JAR:?set ANDROID_JAR to platforms/android-34/android.jar}
OUT=build

# Windows ships .exe/.bat wrappers, Linux/macOS plain binaries.
tool() { for c in "$BT/$1" "$BT/$1.exe" "$BT/$1.bat"; do [ -e "$c" ] && { echo "$c"; return; }; done; echo "missing $1 in $BT" >&2; exit 1; }
AAPT2=$(tool aapt2); D8=$(tool d8); ZIPALIGN=$(tool zipalign); APKSIGNER=$(tool apksigner)

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/unsigned.apk" -A assets -0 jpg \
    --java "$OUT/gen" --min-sdk-version 21 --target-sdk-version 26 "$OUT/res.zip"

javac -nowarn --release 8 -classpath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v "^warning: \[options\]" || true
ls "$OUT/classes/app/glasshome/tv/HomeActivity.class" >/dev/null

"$D8" --release --min-api 21 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
python3 -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
    "$OUT/unsigned.apk" "$OUT/dex/classes.dex" 2>/dev/null \
  || python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" \
    "$OUT/unsigned.apk" "$OUT/dex/classes.dex"

"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
# A local debug key, generated once and never committed.
if [ ! -f debug.keystore ]; then
  # keytool is often missing from PATH (e.g. Oracle's javapath shim), so ask java where it lives.
  KEYTOOL=$(command -v keytool || true)
  if [ -z "$KEYTOOL" ]; then
    JH=${JAVA_HOME:-$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | tr -d '\r')}
    for c in "$JH/bin/keytool" "$JH/bin/keytool.exe"; do [ -e "$c" ] && KEYTOOL=$c; done
  fi
  [ -n "$KEYTOOL" ] || { echo "keytool not found: set JAVA_HOME to a JDK" >&2; exit 1; }
  "$KEYTOOL" -genkeypair -noprompt -keystore debug.keystore -storepass android -keypass android -alias glasshome \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Glass Home" </dev/null >/dev/null
fi
"$APKSIGNER" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out GlassHome.apk "$OUT/aligned.apk"
ls -l GlassHome.apk

#!/bin/sh
# Build the Music Ambient Light mod into dist/:
#   ambient-music-kr-<v>.zip                    the signed HMG-API mod (HMGAPI/updates on a stick, or adb)
#   ambient-music-kr-<v>-nemesis.zip            USB stick: unzip to the stick's root, NEMESIS installs it
#   ambient-music-kr-<v>-nemesis-package.zip    the NEMESIS package alone (nemesis install / Package Store)
#
#   tools/build.sh [all|test|apk|mod]   (default all: test, then apk, then mod)
#   tools/build.sh keygen               make the app signing key (once; keep it, outside the repo)
#
# Needs: a JDK (javac, java, keytool), the Android SDK (a platforms/android-*/android.jar, build-tools
# with aapt2, d8, zipalign, apksigner), python3, zip, and HMG-API's source next to this folder
# (HMGAPI_DIR, default ../HMG-API) for hmgapi-client, signing and the NEMESIS wrapper (only read).
#   ANDROID_HOME  SDK (default: $HOME/Android, else Homebrew's android-commandlinetools)
#   MOD_KEY       mod signing key (default ~/hmg-private/keys/ev6kr-mods.key, the wireless-carplay-kr author key)
#   APP_KEY       APK signing key, PKCS12, password in <key without .p12>.pass
#                 (default ~/hmg-private/keys/ev6kr-ambient-music.p12; keep it: updates need the same key)
set -e
cd "$(dirname "$0")/.."
ROOT=$PWD
HMG=${HMGAPI_DIR:-$ROOT/../HMG-API}
[ -f "$HMG/core/runtime/java/hmgapi/engine/Signing.java" ] || { echo "no HMG-API source at $HMG (set HMGAPI_DIR)"; exit 1; }
HMG=$(cd "$HMG" && pwd)
for s in "${ANDROID_HOME:-}" "$HOME/Android" /opt/homebrew/share/android-commandlinetools; do
    [ -n "$s" ] && ls "$s"/platforms/android-*/android.jar >/dev/null 2>&1 && { SDK=$s; break; }
done
[ -n "$SDK" ] || { echo "no Android SDK with a platforms/android-*/android.jar (set ANDROID_HOME)"; exit 1; }
ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar | tail -1)   # compile only; the code uses API 19
BT=$(ls -d "$SDK"/build-tools/* | tail -1)
for t in aapt2 d8 zipalign apksigner; do [ -x "$BT/$t" ] || { echo "no $t in $BT"; exit 1; }; done
MOD_KEY=${MOD_KEY:-$HOME/hmg-private/keys/ev6kr-mods.key}
APP_KEY=${APP_KEY:-$HOME/hmg-private/keys/ev6kr-ambient-music.p12}
B=$ROOT/build
DIST=$ROOT/dist
mkdir -p "$B" "$DIST"
JAVAC="javac --release 8 -Xlint:-options -nowarn -encoding UTF-8"
ID=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' mod/mod.json)
V=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' mod/mod.json)
AV=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' app/AndroidManifest.xml)
[ "$V" = "$AV" ] || { echo "mod.json version $V and the app's versionName $AV differ"; exit 1; }
APK_NAME=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' mod/mod.json)

# zipdir <dir> <zip>: fixed order and times, so the same input gives the same zip
zipdir() {
    rm -f "$2"
    (cd "$1" && find . -type f | TZ=UTC xargs touch -d 2026-01-01T00:00:00 && find . -type f | sed 's#^\./##' | sort | zip -q -X -D "$2" -@)
}

engine() {
    [ -f "$B/engine-host/hmgapi/engine/Signing.class" ] && return 0
    rm -rf "$B/engine-host" && mkdir -p "$B/engine-host"
    $JAVAC -d "$B/engine-host" "$HMG"/core/runtime/java/hmgapi/engine/*.java 2>/dev/null
}

client() {
    rm -rf "$B/client" && mkdir -p "$B/client"
    $JAVAC -cp "$ANDROID_JAR" -d "$B/client" $(find "$HMG/core/client/java" -name '*.java')
}

keygen() {
    [ -f "$APP_KEY" ] && { echo "$APP_KEY already exists; not replacing it (the installed app is tied to its key)"; exit 1; }
    mkdir -p "$(dirname "$APP_KEY")"
    P=${APP_KEY%.p12}.pass
    (umask 077 && python3 -I -c 'import secrets; print(secrets.token_hex(24))' > "$P")
    (umask 077 && keytool -genkeypair -keystore "$APP_KEY" -storetype pkcs12 -storepass "$(cat "$P")" -keypass "$(cat "$P")" \
        -alias app -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=ev6kr ambient music" >/dev/null 2>&1)
    echo "made $APP_KEY (password in $P): keep both somewhere safe and never commit them"
}

# test: the analysis and the command pacing, on this computer (no car, no Android)
test_() {
    rm -rf "$B/test" && mkdir -p "$B/test"
    $JAVAC -cp "$ANDROID_JAR" -d "$B/test" app/java/kr/ev6/ambientmusic/Analyzer.java app/java/kr/ev6/ambientmusic/Controller.java \
        app/java/kr/ev6/ambientmusic/Palette.java app/java/kr/ev6/ambientmusic/Settings.java app/java/kr/ev6/ambientmusic/CarLight.java app/java/kr/ev6/ambientmusic/MicSignals.java tests/LogicTest.java
    java -cp "$B/test:$ANDROID_JAR" kr.ev6.ambientmusic.LogicTest
}

apk() {
    [ -f "$APP_KEY" ] || { echo "no app signing key at $APP_KEY: run tools/build.sh keygen first (or set APP_KEY)"; exit 1; }
    client
    W=$B/apk
    rm -rf "$W" && mkdir -p "$W/classes" "$W/gen"
    "$BT/aapt2" compile --dir app/res -o "$W/res.zip"
    "$BT/aapt2" link -I "$ANDROID_JAR" --manifest app/AndroidManifest.xml --min-sdk-version 19 --target-sdk-version 19 \
        --java "$W/gen" -o "$W/base.apk" "$W/res.zip"
    $JAVAC -cp "$ANDROID_JAR:$B/client" -d "$W/classes" $(find app/java "$W/gen" -name '*.java')
    cp -r "$B/client/hmgapi" "$W/classes/"   # hmgapi-client goes inside the APK
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$W" $(find "$W/classes" -name '*.class')
    cp "$W/base.apk" "$W/unsigned.apk"
    (cd "$W" && zip -q -X unsigned.apk classes.dex)
    "$BT/zipalign" -f 4 "$W/unsigned.apk" "$W/aligned.apk"
    # v1 (JAR) signing: what API 19 reads (the head unit's install allowlist is handled by HMG-API)
    "$BT/apksigner" sign --ks "$APP_KEY" --ks-pass "file:${APP_KEY%.p12}.pass" --min-sdk-version 19 \
        --v2-signing-enabled false --v3-signing-enabled false --v4-signing-enabled false --out "$W/app.apk" "$W/aligned.apk"
    "$BT/apksigner" verify --min-sdk-version 19 "$W/app.apk"
    echo "$W/app.apk"
}

mod() {
    [ -f "$MOD_KEY" ] || { echo "no mod signing key at $MOD_KEY (set MOD_KEY)"; exit 1; }
    [ -f "$B/apk/app.apk" ] || apk
    engine
    P=$B/mod/pkg/$ID
    rm -rf "$B/mod" && mkdir -p "$P/$(dirname "$APK_NAME")" "$P/icons"
    cp mod/mod.json "$P/"
    cp mod/icons/*.png "$P/icons/"
    cp "$B/apk/app.apk" "$P/$APK_NAME"
    java -cp "$B/engine-host" hmgapi.engine.Signing sign "$P" "$MOD_KEY"
    java -cp "$B/engine-host" hmgapi.engine.Signing verify "$P"
    Z=$DIST/$ID-$V.zip
    zipdir "$P" "$Z"
    echo "$Z"
    sh "$HMG/tools/mknemesismod.sh" "$Z" "$DIST"
}

# diag: the diagnosis stick (usb/diag) as a zip to unzip onto a stick's root
diag() {
    rm -rf "$B/stick-diag" && mkdir -p "$B/stick-diag"
    cat usb/diag/NEMESIS.data > "$B/stick-diag/NEMESIS.data"
    cat usb/diag/NEMESIS.sh > "$B/stick-diag/NEMESIS.sh"
    zipdir "$B/stick-diag" "$DIST/ev6kr-ambient-diag-usb.zip"
    echo "$DIST/ev6kr-ambient-diag-usb.zip"
}

# usmtest: the stock ambient light path test (usb/usmtest) as a zip to unzip onto a stick's root
usmtest() {
    W=$B/stick-usm
    rm -rf "$W" && mkdir -p "$W/classes" "$W/dex" "$W/stick"
    $JAVAC -cp "$ANDROID_JAR" -d "$W/classes" usb/usmtest/src/UsmProbe.java
    "$D8" --release --min-api 19 --lib "$ANDROID_JAR" --output "$W/dex" $(find "$W/classes" -name '*.class')
    (cd "$W/dex" && zip -q -X "$W/stick/usmprobe.jar" classes.dex)
    cat usb/usmtest/NEMESIS.data > "$W/stick/NEMESIS.data"
    cat usb/usmtest/NEMESIS.sh > "$W/stick/NEMESIS.sh"
    zipdir "$W/stick" "$DIST/ev6kr-ambient-usmtest-usb.zip"
    echo "$DIST/ev6kr-ambient-usmtest-usb.zip"
}

D8=$BT/d8
case "${1:-all}" in
    usmtest) usmtest ;;
    keygen) keygen ;;
    test) test_ ;;
    apk) apk ;;
    mod) mod ;;
    diag) diag ;;
    all) test_ && apk && mod && diag ;;
    *) echo "usage: tools/build.sh [all|test|apk|mod|diag|keygen]"; exit 1 ;;
esac

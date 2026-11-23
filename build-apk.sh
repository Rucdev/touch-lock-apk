#!/usr/bin/env bash
# Gradle を使わず Android SDK ツールだけでデバッグ APK を作る代替ビルド。
# 必要なもの: JDK 17, Android SDK (platforms;android-34, build-tools;34.0.0)
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT を設定してください}
BT=$SDK/build-tools/34.0.0
AJAR=$SDK/platforms/android-34/android.jar
export PATH=$JAVA_HOME/bin:$PATH

rm -rf build
mkdir -p build/gen build/classes build/dex

$BT/aapt2 compile --dir app/src/main/res -o build/compiled.zip
$BT/aapt2 link -o build/app-unsigned.apk -I "$AJAR" \
    --manifest app/src/main/AndroidManifest.xml \
    -R build/compiled.zip --java build/gen --auto-add-overlay

find app/src/main/java build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -cp "$AJAR" -d build/classes @build/sources.txt

$BT/d8 --min-api 26 --output build/dex $(find build/classes -name '*.class')
cp build/app-unsigned.apk build/app-with-dex.apk
(cd build/dex && jar uf ../app-with-dex.apk classes.dex)
$BT/zipalign -f -p 4 build/app-with-dex.apk build/app-aligned.apk

if [ ! -f build/debug.keystore ]; then
  keytool -genkeypair -keystore build/debug.keystore -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi
$BT/apksigner sign --ks build/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out TouchLock.apk build/app-aligned.apk
echo "Built: TouchLock.apk"

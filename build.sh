#!/bin/sh
# Builds vidrio.apk with the bare Android SDK tools: no Gradle, no libraries.
# The signing key and its password live in firma/ and stay out of git: without them, updates
# won't install over the app. The first build creates both, with a random password.
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/Library/Java/JavaVirtualMachines/jdk-17*/Contents/Home | head -1)}"
export PATH="$JAVA_HOME/bin:$PATH"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

if [ ! -f firma/vidrio.jks ]; then
    mkdir -p firma
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24 > firma/clave.txt
    keytool -genkeypair -keystore firma/vidrio.jks -alias vidrio -storepass "$(cat firma/clave.txt)" \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vidrio" >/dev/null
fi

rm -rf build && mkdir -p build/gen build/classes
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 24 --target-sdk-version 28 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    -o build/unsigned.apk build/res.zip
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks firma/vidrio.jks --ks-pass file:firma/clave.txt --out vidrio.apk build/aligned.apk
echo "vidrio.apk listo ($(du -h vidrio.apk | cut -f1))"

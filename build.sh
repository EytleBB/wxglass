#!/usr/bin/env bash
# Standalone LSPosed (libxposed API 102) module for WeChat glass bubbles.
# javac + d8 + aapt2 + apksigner, same shape as the bottom-bar module build.
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_HOME="${JAVA_HOME:-$PROJ/../tools/jdk-17.0.21+9}"
SDK="${SDK:-$PROJ/../sdk}"
BT="$SDK/build-tools/android-14"
PLAT="$SDK/platforms/android-34-ext12/android.jar"
XAPI="$PROJ/tools/classes.jar"
OUT="$PROJ/build"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJ/AndroidManifest.xml")"

export PATH="$JAVA_HOME/bin:$PATH"
for f in "$PLAT" "$BT/aapt2" "$BT/lib/d8.jar" "$XAPI"; do
    [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] javac"
find "$PROJ/src" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 --release 11 -Xlint:-options \
    -classpath "$PLAT:$XAPI" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "[2/6] jar + d8"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --lib "$PLAT" --min-api 26 \
    --output "$OUT/dex" \
    "$OUT/classes.jar"

echo "[3/6] aapt2 link"
"$BT/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLAT" \
    --manifest "$PROJ/AndroidManifest.xml"

echo "[4/6] inject dex + xposed meta"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$PROJ" "$OUT" <<'PYZIP'
import os, sys, zipfile
proj, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(os.path.join(out, 'unsigned.apk'), 'a') as z:
    z.write(os.path.join(out, 'dex', 'classes.dex'), 'classes.dex',
            compress_type=zipfile.ZIP_DEFLATED)
    root = os.path.join(proj, 'META-INF')
    for base, _, files in os.walk(root):
        for f in sorted(files):
            full = os.path.join(base, f)
            z.write(full, os.path.relpath(full, proj), compress_type=zipfile.ZIP_DEFLATED)
PYZIP

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
KS="$PROJ/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -validity 10000 \
        -dname 'CN=WxGlass Debug,O=Android,C=US'
fi
java -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$PROJ/WxGlass-$VERSION.apk" "$OUT/aligned.apk"
java -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify --print-certs \
    "$PROJ/WxGlass-$VERSION.apk" | head -4
ls -l "$PROJ/WxGlass-$VERSION.apk"

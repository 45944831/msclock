#!/bin/bash
# 手工构建 APK（aapt2 + javac + d8 + zipalign + apksigner），不依赖 Gradle
set -e

SDK="${ANDROID_SDK:-/d/SDK}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
PKG="com.wb.msclock"
OUT="build"
APK_NAME="毫秒悬浮时钟.apk"

cd "$(dirname "$0")"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> 1/6 编译资源"
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"

echo "==> 2/6 链接资源与清单"
"$BT/aapt2.exe" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java "$OUT/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 33 \
    --version-code 2 \
    --version-name 1.1 \
    "$OUT/res.zip"

echo "==> 3/6 编译 Java"
find src "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
# 编译失败必须中断脚本：输出写入日志，单独判定 javac 退出码
if ! "${JAVA_BIN}javac" -J-Dfile.encoding=UTF-8 -source 8 -target 8 -encoding UTF-8 \
    -classpath "$PLATFORM" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt" > "$OUT/javac.log" 2>&1; then
    echo "!!! 编译失败 !!!"
    cat "$OUT/javac.log"
    exit 1
fi
grep -v "bootstrap class path\|source value 8\|target value 8\|deprecat\|已过时\|警告" "$OUT/javac.log" || true
echo "   编译通过，class 文件数: $(find "$OUT/classes" -name '*.class' | wc -l)"

echo "==> 4/6 转 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classes.txt"
for c in MainActivity FloatingClockService ClockView TimeSync; do
    if [ ! -f "$OUT/classes/com/wb/msclock/$c.class" ]; then
        echo "!!! 缺少类文件: $c.class —— 构建中止 !!!"
        exit 1
    fi
done
"${JAVA_BIN}java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$PLATFORM" \
    --min-api 26 \
    --output "$OUT/dex" \
    @"$OUT/classes.txt"

echo "==> 5/6 打包"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
D:/python/python.exe - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
shutil.move(apk, tmp)
with zipfile.ZipFile(tmp) as zin, zipfile.ZipFile(apk, "w", zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, "classes.dex")
os.remove(tmp)
print("   classes.dex 已加入")
PY

"$BT/zipalign.exe" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/6 签名"
KS="debug.keystore"   # 持久化密钥库，避免每次构建换签名导致无法覆盖安装
if [ ! -f "$KS" ]; then
    "${JAVA_BIN}keytool" -genkeypair -v -keystore "$KS" -alias clock \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android -keypass android \
        -dname "CN=MsClock, OU=WB, O=WB, L=Beijing, C=CN" >/dev/null 2>&1
fi
"${JAVA_BIN}java" -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "../$APK_NAME" "$OUT/aligned.apk"

"${JAVA_BIN}java" -jar "$BT/lib/apksigner.jar" verify "../$APK_NAME" && echo "签名校验通过"
ls -lh "../$APK_NAME"

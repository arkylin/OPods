#!/bin/sh
# OPods / LibXposed 模块 Linux 打包脚本（等价于仓库自带 build.ps1）
# 用法: sh build.sh [项目目录] [输出名提示]
set -eu

PROJECT="${1:-$(cd "$(dirname "$0")" && pwd)}"
HINT="${2:-}"

cd "$PROJECT"

MANIFEST="AndroidManifest.xml"
RES_DIR="res"
XPOSED_DIR="xposed"
LIBXPOSED="libs/libxposed-api.jar"

for p in "$MANIFEST" "$RES_DIR" "$XPOSED_DIR" "$LIBXPOSED"; do
  [ -e "$p" ] || { echo "缺少必需路径: $p" >&2; exit 10; }
done

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
[ -d "$SDK" ] || { echo "找不到 Android SDK: $SDK" >&2; exit 11; }

PLATFORM_VER=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sed 's#.*/android-##' | sort -n | tail -1)
[ -n "${PLATFORM_VER:-}" ] || { echo "SDK 中没有 platforms/android-*" >&2; exit 12; }
ANDROID_JAR="$SDK/platforms/android-$PLATFORM_VER/android.jar"

BT_DIR=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -n "${BT_DIR:-}" ] || { echo "SDK 中没有 build-tools" >&2; exit 13; }

AAPT2_BIN="${TAIXU_AAPT2_PATH:-$BT_DIR/aapt2}"
for tool in "$AAPT2_BIN" "$BT_DIR/d8" "$BT_DIR/zipalign" "$BT_DIR/apksigner"; do
  [ -x "$tool" ] || { echo "构建工具缺失或不可执行: $tool" >&2; exit 14; }
done
for cmd in javac jar keytool python3; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "缺少命令: $cmd（需 JDK17+ 与 python3）" >&2; exit 15; }
done

VERSION_NAME=$(python3 - "$MANIFEST" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'android:versionName="([^"]+)"', s)
print(m.group(1) if m else '')
PY
)
[ -n "$VERSION_NAME" ] || { echo "AndroidManifest.xml 没有 versionName" >&2; exit 16; }

OUT="${OPODS_OUT:-dist/OPPO-AirPods-Enhanced-v$VERSION_NAME.apk}"

BUILD="$PROJECT/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$(dirname "$OUT")"

echo "==> 编译 Java 源码（platform android-$PLATFORM_VER）"
find src -name '*.java' | sort > "$BUILD/sources.txt"
[ -s "$BUILD/sources.txt" ] || { echo "src/ 下没有 Java 源码" >&2; exit 17; }
javac -encoding UTF-8 -nowarn -cp "$ANDROID_JAR:$LIBXPOSED" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 生成 classes.jar 并 dex"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT_DIR/d8" --min-api 33 --lib "$ANDROID_JAR" --classpath "$LIBXPOSED" --output "$BUILD/dex" "$BUILD/classes.jar"

echo "==> 处理资源与清单"
"$AAPT2_BIN" compile --dir "$RES_DIR" -o "$BUILD/res.zip"
"$AAPT2_BIN" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" --manifest "$MANIFEST" \
  -R "$BUILD/res.zip" --auto-add-overlay --java "$BUILD/gen"

echo "==> 注入 classes.dex 与 META-INF/xposed 元数据"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
python3 - "$BUILD/unsigned.apk" "$BUILD/dex/classes.dex" "$XPOSED_DIR" <<'PY'
import sys, zipfile
apk, dex, xposed = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
    for name in ('module.prop', 'scope.list', 'java_init.list'):
        z.write(xposed + '/' + name, 'META-INF/xposed/' + name)
PY

echo "==> 对齐与签名"
"$BT_DIR/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

KS="${OPODS_KEYSTORE:-}"
KS_PASS="${OPODS_KEYSTORE_PASSWORD:-android}"
KS_ALIAS="${OPODS_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${OPODS_KEY_PASSWORD:-$KS_PASS}"

if [ -z "$KS" ] && [ -f "$PROJECT/.keystore/opods-release.jks" ] && [ -f "$PROJECT/.keystore/opods-release.pass" ]; then
  KS="$PROJECT/.keystore/opods-release.jks"
  KS_PASS=$(tr -d '\r\n' < "$PROJECT/.keystore/opods-release.pass")
  KS_ALIAS="opods-release"
  KEY_PASS="$KS_PASS"
  echo "    使用项目内 release keystore"
fi
if [ -z "$KS" ]; then
  KS="$PROJECT/.keystore/oppo-airpods-debug.keystore"
  mkdir -p "$(dirname "$KS")"
  if [ ! -f "$KS" ]; then
    echo "    生成 debug keystore: $KS"
    keytool -genkeypair -noprompt -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname 'CN=Android Debug,O=Android,C=US' >/dev/null 2>&1
  fi
fi

rm -f "$OUT"
OPODS_KS_PASS="$KS_PASS" OPODS_KEY_PASS="$KEY_PASS" "$BT_DIR/apksigner" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass env:OPODS_KS_PASS --key-pass env:OPODS_KEY_PASS \
  --out "$OUT" "$BUILD/aligned.apk"
"$BT_DIR/apksigner" verify "$OUT"

echo "==> 完成"
echo "APK: $OUT"
echo "Size: $(wc -c < "$OUT") bytes"
echo "SHA-256: $(sha256sum "$OUT" | cut -d' ' -f1)"
echo "versionName=$VERSION_NAME  hint=${HINT:-none}"

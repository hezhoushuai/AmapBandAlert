#!/usr/bin/env bash
# 无Gradle构建: aapt2 + javac + d8 + zipalign + apksigner
# 参考 amap-companion 的构建方式, 依赖 runner 自带的 Android SDK build-tools
set -x

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
echo "build-tools: $BT"
echo "platform: $PLATFORM"

rm -rf build_out && mkdir -p build_out/gen build_out/obj build_out/apk

# 1. aapt2 编译链接资源 → 生成 R.java 和资源包
"$BT/aapt2" compile --dir app/src/main/res -o build_out/res.zip
"$BT/aapt2" link -o build_out/base.apk \
  -I "$PLATFORM/android.jar" \
  --manifest app/src/main/AndroidManifest.xml \
  --java build_out/gen \
  --auto-add-overlay \
  build_out/res.zip

# 2. javac 编译 (需要 androidx? 不用! 我们把代码改成纯android SDK API)
mkdir -p build_out/classes
find build_out/gen -name "*.java" > build_out/sources.txt
find app/src/main/java -name "*.java" >> build_out/sources.txt
javac --release 11 -classpath "$PLATFORM/android.jar" -d build_out/classes @build_out/sources.txt

# 3. d8 转dex
"$BT/d8" --release --lib "$PLATFORM/android.jar" \
  --output build_out $(find build_out/classes -name '*.class')

# 4. 打包: 把classes.dex加入apk
cd build_out && zip -q base.apk classes.dex && cd ..

# 5. zipalign
"$BT/zipalign" -f 4 build_out/base.apk build_out/aligned.apk

# 6. 签名 (debug keystore, CI上临时生成)
keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
  -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --out AmapBandAlert-debug.apk build_out/aligned.apk

ls -la AmapBandAlert-debug.apk
echo "BUILD_OK"

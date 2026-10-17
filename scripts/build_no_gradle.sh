#!/usr/bin/env bash
# 无Gradle构建: aapt2 + javac + d8 + zipalign + apksigner
# 参考 amap-companion 的构建方式, 依赖 runner 自带的 Android SDK build-tools
set -x
echo "SDK=$SDK"
echo "BT=$BT"
echo "PLATFORM=$PLATFORM"

SDK="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)
echo "build-tools: $BT"
echo "platform: $PLATFORM"

rm -rf build_out && mkdir -p build_out/gen build_out/obj build_out/apk

# 1. aapt2 编译链接资源 → 生成 R.java 和资源包
echo "STEP1: aapt2 compile"
"$BT/aapt2" compile --dir android-app/app/src/main/res -o build_out/res.zip || { echo "FAIL: aapt2 compile"; exit 1; }
echo "STEP2: aapt2 link"
"$BT/aapt2" link -o build_out/base.apk \
  -I "$PLATFORM/android.jar" \
  --manifest android-app/app/src/main/AndroidManifest.xml \
  --java build_out/gen \
  --auto-add-overlay \
  build_out/res.zip || { echo "FAIL: aapt2 link"; exit 1; }

# 2. javac 编译 (需要 androidx? 不用! 我们把代码改成纯android SDK API)
mkdir -p build_out/classes
find build_out/gen -name "*.java" > build_out/sources.txt
find android-app/app/src/main/java -name "*.java" >> build_out/sources.txt
echo "STEP3: javac"
javac -source 11 -target 11 -classpath "$PLATFORM/android.jar" -d build_out/classes @build_out/sources.txt || { echo "FAIL: javac"; exit 1; }

# 3. d8 转dex
echo "STEP4: d8"
"$BT/d8" --release --lib "$PLATFORM/android.jar" \
  --output build_out $(find build_out/classes -name '*.class') || { echo "FAIL: d8"; exit 1; }

# 4. 打包: 把classes.dex加入apk
cd build_out && zip -q base.apk classes.dex && cd ..

# 5. zipalign
"$BT/zipalign" -f 4 build_out/base.apk build_out/aligned.apk

# 6. 签名 (debug keystore, CI上临时生成)
keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
  -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
echo "STEP6: apksigner"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --out AmapBandAlert-debug.apk build_out/aligned.apk || { echo "FAIL: apksigner"; exit 1; }
echo "ALL_STEPS_PASSED"

ls -la AmapBandAlert-debug.apk
echo "BUILD_OK"

#!/usr/bin/env bash
# 纯 JDK 的解析器回归测试，不需要 Android SDK。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -d "$OUT" \
  "$ROOT/android-app/app/src/main/java/com/amapbandalert/NavigationParser.java" \
  "$ROOT/android-app/app/src/test/java/com/amapbandalert/NavigationParserTest.java"

java -cp "$OUT" com.amapbandalert.NavigationParserTest

#!/bin/bash
# 纯 JVM 校时算法单测：不依赖 Android SDK / JUnit，任何 JDK 8+ 均可运行
set -e
cd "$(dirname "$0")"
SRC="../../src/com/wb/msclock/TimeMath.java"
OUT="classes"
rm -rf "$OUT" && mkdir -p "$OUT"

echo "==> 编译 TimeMath + 测试"
javac -encoding UTF-8 -d "$OUT" "$SRC" TimeMathTest.java

echo "==> 运行测试"
java -cp "$OUT" TimeMathTest

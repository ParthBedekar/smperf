#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.0
DIST=target/dist
APP=$DIST/smperf

[ -f packaging/icon.png ] || { echo "missing packaging/icon.png"; exit 1; }

echo "== 1/4 native engine"
make -C native

echo "== 2/4 jar + dependencies"
mvn -q clean package -DskipTests

echo "== 3/4 jpackage app-image"
mkdir -p target/jpkg-mods
cp target/mods/gson-*.jar target/jpkg-mods/
cp target/mods/javafx-*-linux.jar target/jpkg-mods/
cp target/smperf-*.jar target/jpkg-mods/
jpackage --type app-image \
  --name smperf \
  --app-version "$VERSION" \
  --module-path target/jpkg-mods \
  --module com.pvb.smperf/com.pvb.smperf.App \
  --add-modules jdk.unsupported \
  --icon packaging/icon.png \
  --dest "$DIST"

echo "== 4/4 assemble layout"
install -m 755 native/bin/mperf "$APP/bin/mperf"
mkdir -p "$APP/include"
cp native/include/*.h "$APP/include/"
cp packaging/icon.png "$APP/icon.png"
[ -f packaging/LICENSE.txt ] && cp packaging/LICENSE.txt "$APP/LICENSE.txt" || true

echo
echo "== check: no sources shipped"
find "$APP" \( -name '*.c' -o -name '*.java' -o -name Makefile -o -name src \) | head
echo "(empty above = good)"
echo
ls "$APP" "$APP/bin" "$APP/include"
du -sh "$APP"
echo "== release archive"
REL=target/release/smperf-$VERSION-linux
rm -rf target/release && mkdir -p "$REL"
cp -a "$APP" "$REL/smperf"
cp packaging/install.sh "$REL/install.sh" && chmod +x "$REL/install.sh"
cp packaging/LICENSE.txt "$REL/LICENSE.txt"
tar -C target/release -czf "target/smperf-$VERSION-linux.tar.gz" "smperf-$VERSION-linux"
ls -lh "target/smperf-$VERSION-linux.tar.gz"
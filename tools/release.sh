#!/usr/bin/env bash
# 새 버전 OTA 배포:
#   1) app/build.gradle.kts 의 versionCode/versionName 을 올린 뒤
#   2) ./tools/release.sh ["릴리스 노트"]
# 빌드 → GitHub Release(vX.Y) 생성 + APK 업로드까지 한 번에 한다.
# 차량 앱은 시작 시 releases/latest 를 확인해 새 버전을 받아 설치한다.
#
# 주의: 기존 설치본과 같은 디버그 키스토어(같은 PC)로 빌드해야 업데이트 설치가 된다.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
[ -n "$VERSION" ] || { echo "versionName 을 읽지 못했습니다"; exit 1; }
CODE=$(sed -n 's/.*versionCode = \([0-9]*\)/\1/p' app/build.gradle.kts)

SRC="app/build/outputs/apk/prod/debug/app-prod-debug.apk"
./gradlew :app:assembleProdDebug

# 방금 빌드로 갱신된 파일인지 확인 — 2026-09 사고: 플레이버 도입 후에도 옛 경로(apk/debug/app-debug.apk)를
# 계속 복사해 v3.54~v7.21 수개월간 실제로는 v1.1 APK 를 재업로드했다. 다시는 안 속게 mtime + versionCode 둘 다 검증.
[ -f "$SRC" ] || { echo "✗ 빌드 산출물이 없습니다: $SRC"; exit 1; }
AGE=$(( $(date +%s) - $(stat -f %m "$SRC") ))
[ "$AGE" -lt 300 ] || { echo "✗ $SRC 가 방금 빌드된 게 아닙니다(${AGE}초 전) — 경로 확인 필요"; exit 1; }
AAPT=$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools" -maxdepth 1 -iname "aapt" 2>/dev/null | sort -V | tail -1)
if [ -n "$AAPT" ]; then
  GOT=$("$AAPT" dump badging "$SRC" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")
  [ "$GOT" = "$CODE" ] || { echo "✗ APK versionCode($GOT) != build.gradle.kts($CODE) — 잘못된 빌드"; exit 1; }
fi

APK="byd-karaoke-v${VERSION}.apk"
cp "$SRC" "$APK"

gh release create "v${VERSION}" "$APK" --title "v${VERSION}" --notes "${1:-v${VERSION}}"
echo "✅ v${VERSION} 릴리스 완료 — 차에서 앱을 재시작하면 업데이트가 내려갑니다."

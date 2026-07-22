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

./gradlew :app:assembleDebug
APK="byd-karaoke-v${VERSION}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$APK"

gh release create "v${VERSION}" "$APK" --title "v${VERSION}" --notes "${1:-v${VERSION}}"
echo "✅ v${VERSION} 릴리스 완료 — 차에서 앱을 재시작하면 업데이트가 내려갑니다."

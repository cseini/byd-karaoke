#!/usr/bin/env bash
# Vosk 한국어 소형 모델(~82MB)을 APK assets 로 내려받는다.
# git 에는 커밋하지 않고(용량), 빌드 전에 이 스크립트로 준비한다(CI 자동 실행).
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL=vosk-model-small-ko-0.22
DEST=app/src/main/assets/model-ko

if [ -f "$DEST/version" ] && [ "$(cat "$DEST/version")" = "$MODEL" ]; then
    echo "Vosk 모델 이미 준비됨: $DEST"
    exit 0
fi

rm -rf "$DEST"
mkdir -p "$DEST"
TMP=$(mktemp -d)
curl -sSL -o "$TMP/model.zip" "https://alphacephei.com/vosk/models/${MODEL}.zip"
unzip -q "$TMP/model.zip" -d "$TMP"
mv "$TMP/$MODEL"/* "$DEST"/
echo "$MODEL" > "$DEST/version"
rm -rf "$TMP"
echo "✅ Vosk 모델 준비 완료: $DEST"

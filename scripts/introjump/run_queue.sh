#!/bin/bash
# 간주점프 자동화 — 사용자 실재생 큐를 처리하는 launchd 진입점.
# 05:30 newpipe-autodeploy.sh(gradle 빌드 사용) 와 겹치지 않는 시간대에 돌린다.
set -uo pipefail
export PATH="/opt/homebrew/bin:/opt/homebrew/opt/node@22/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"

PROJ="/Users/sen/project/byd-karaoke"
cd "$PROJ" || exit 1

python3 scripts/introjump/pipeline.py --queue

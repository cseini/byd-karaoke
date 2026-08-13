#!/bin/bash
# NewPipeExtractor 자동 감시 + (검증 통과 시) 자동 빌드·배포 — 맥미니 launchd 용.
#
# 매일 1회:
#  A) 정상 & 새 버전 없음        → 아무것도 안 함
#  B) 새 정식 버전 있음          → 라이브러리 올려 스모크·채점 통과 시 자동 빌드·배포(선제 호환)
#  C) 유튜브 깨짐 + 정식 패치 없음 → dev(개발) 빌드로 시도, 되면 긴급 배포 / 안 되면 알림만
#  D) dev 빌드 상태에서 정식 나옴 → 정식으로 안정화(스모크 통과 시)
#
# 안전장치: git clean 확인 · 스모크+채점 게이트 · 서명 SHA 검증 · 버전 반영 검증 · 실패 시 롤백.
# 토큰: ~/.config/byd-karaoke/notify.env 에 TELEGRAM_BOT_TOKEN, CHAT_ID.

set -uo pipefail
export PATH="/opt/homebrew/bin:/opt/homebrew/opt/node@22/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"

PROJ="/Users/sen/project/byd-karaoke"
CERT_SHA="2a43fbe97591a2cda9357b99d928ec2f978a42d38c40b43bfa4ffe981ac2d009"
LOG="$PROJ/scripts/autodeploy.log"
cd "$PROJ" || exit 1
log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

[ -f ~/.config/byd-karaoke/notify.env ] && . ~/.config/byd-karaoke/notify.env
tg(){
  [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${CHAT_ID:-}" ] || { log "TG 미설정: $1"; return; }
  curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
    --data-urlencode "chat_id=$CHAT_ID" --data-urlencode "text=🎤 노래방 자동배포
$1" >/dev/null
}

AAPT=$(ls "$ANDROID_HOME/build-tools/"*/aapt2 2>/dev/null | sort -V | tail -1)
APKSIGNER=$(ls "$ANDROID_HOME/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)
GRADLE="./gradlew"

smoke(){ RUN_YT_SMOKE=1 $GRADLE :app:testProdReleaseUnitTest --tests "com.cseini.byd.karaoke.YoutubeSmokeTest" --rerun -q >/dev/null 2>&1; }
regress(){ $GRADLE :app:testProdReleaseUnitTest --tests "com.cseini.byd.karaoke.MelodyScorerTest" --rerun -q >/dev/null 2>&1; }

# NewPipe 참조를 목표 값으로 교체. $1=정식이면 "v0.26.5", dev면 "abc1234def"
set_np(){ sed -i '' -E "s#(com\.github\.[Tt]eam[Nn]ewpipe:NewPipeExtractor:)[A-Za-z0-9.]+#\1$1#" app/build.gradle.kts; }

# 버전 올려 빌드·서명검증·릴리스·랜딩·커밋. $1=릴리스노트 라이브러리표기, $2=urgent(0/1). 성공 0.
do_release(){
  local npnote="$1" urgent="$2"
  local CODE NAME NEWCODE NEWNAME APK GOTSHA
  CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
  NAME=$(grep -oE 'versionName = "[0-9.]+"' app/build.gradle.kts | grep -oE '[0-9.]+')
  NEWCODE=$((CODE+1))
  NEWNAME=$(python3 -c "print(f'{float('$NAME')+0.01:.2f}')")
  sed -i '' "s/versionCode = $CODE/versionCode = $NEWCODE/; s/versionName = \"$NAME\"/versionName = \"$NEWNAME\"/" app/build.gradle.kts
  log "빌드 v$NAME→v$NEWNAME ($npnote, urgent=$urgent)"
  if $GRADLE :app:assembleProdRelease :app:assembleLabRelease -q 2>&1 | grep -qiE "error:|FAILURE"; then
    tg "❌ v$NEWNAME 빌드 실패 → 롤백."; git checkout -- app/build.gradle.kts; return 1; fi
  APK="app/build/outputs/apk/prod/release/app-prod-release.apk"
  GOTSHA=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -i "SHA-256 digest" | grep -oE '[0-9a-f]{64}')
  [ "$GOTSHA" = "$CERT_SHA" ] || { tg "❌ v$NEWNAME 서명 불일치 → 중단."; git checkout -- app/build.gradle.kts; return 1; }
  "$AAPT" dump badging "$APK" 2>/dev/null | grep -q "versionName='$NEWNAME'" || {
    tg "❌ v$NEWNAME 빌드가 최신 아님 → 중단."; git checkout -- app/build.gradle.kts; return 1; }
  cp "$APK" "/tmp/byd-karaoke-v$NEWNAME.apk"; cp "$APK" "/tmp/karaoke-latest.apk"
  cp app/build/outputs/apk/lab/release/app-lab-release.apk "/tmp/byd-karaoke-v$NEWNAME-test.apk"
  local NOTE="- 유튜브 재생 호환성 업데이트(NewPipe $npnote 적용)"
  gh release create "v$NEWNAME" "/tmp/byd-karaoke-v$NEWNAME.apk" "/tmp/karaoke-latest.apk" \
    --repo cseini/byd-karaoke --title "v$NEWNAME" --notes "$NOTE" >/dev/null 2>&1
  gh release create "v$NEWNAME" "/tmp/byd-karaoke-v$NEWNAME-test.apk" \
    --repo cseini/byd-karaoke-test --title "v$NEWNAME-test" --notes "$NOTE" >/dev/null 2>&1
  ( cd landing
    python3 - "$NEWNAME" "$urgent" <<'PY'
import sys, re
new, urgent = sys.argv[1], sys.argv[2]
s = open('index.html', encoding='utf-8').read()
s = re.sub(r'byd-karaoke-v[0-9.]+\.apk', f'byd-karaoke-v{new}.apk', s)
s = re.sub(r'releases/download/v[0-9.]+/', f'releases/download/v{new}/', s)
s = re.sub(r'<span class="wn-ver">v[0-9.]+ 최신</span>', f'<span class="wn-ver">v{new} 최신</span>', s)
open('index.html','w',encoding='utf-8').write(s)
if urgent == '1':
    open('min.json','w',encoding='utf-8').write(
        f'{{"minVersion": "{new}", "message": "유튜브 재생 문제로 필수 업데이트입니다."}}')
PY
    rm -rf dist && mkdir dist && cp index.html dist/index.html && cp min.json dist/min.json && cp -r img dist/img
    npx wrangler pages deploy dist --project-name=karaoke --commit-dirty=true >/dev/null 2>&1 )
  git add -A
  git commit -q -m "v$NEWNAME: NewPipe $npnote 자동 적용(유튜브 호환)

맥미니 자동배포 — 유튜브 추출 스모크·채점 회귀 통과 후 빌드·서명·배포.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AW3ieMQE2zSGTP655W9mjv"
  git push -q origin main
  DEPLOYED="$NEWNAME"
  return 0
}

# ── 시작 ──
[ -n "$(git status --porcelain)" ] && { log "작업트리 dirty — 스킵"; exit 0; }
git pull -q origin main 2>/dev/null

if grep -q 'NewPipeExtractor:v[0-9]' app/build.gradle.kts; then
  STATE=stable; CUR_NP=$(grep -oE 'NewPipeExtractor:v[0-9.]+' app/build.gradle.kts | head -1 | sed 's/.*:v//')
else
  STATE=dev; CUR_NP="dev"
fi
LATEST_NP=$(curl -s "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('tag_name','').lstrip('v'))" 2>/dev/null)
[ -z "$LATEST_NP" ] && { log "정식 최신 조회 실패"; exit 0; }
if smoke; then CUR_OK=1; else CUR_OK=0; fi
log "STATE=$STATE 현재=$CUR_NP 최신=$LATEST_NP 스모크=$([ $CUR_OK = 1 ] && echo OK || echo FAIL)"

# A) 정상 & 변화 없음
if [ "$STATE" = stable ] && [ "$LATEST_NP" = "$CUR_NP" ] && [ "$CUR_OK" = 1 ]; then
  log "변화 없음 — 종료"; exit 0
fi

DEPLOYED=""

# C) 유튜브 깨짐 + 정식 패치 없음 → dev 시도
if [ "$STATE" = stable ] && [ "$LATEST_NP" = "$CUR_NP" ] && [ "$CUR_OK" = 0 ]; then
  DEV=$(curl -s "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/commits/dev" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('sha','')[:10])" 2>/dev/null)
  [ -z "$DEV" ] && { tg "⚠️ 유튜브 깨짐(스모크 실패), NewPipe dev 조회 실패 — 수동 확인 필요."; exit 0; }
  log "정식 패치 없음 → dev($DEV) 시도"
  set_np "$DEV"
  if smoke && regress; then
    tg "🧪 유튜브가 깨졌는데 dev($DEV) 로 추출 성공 — 긴급 배포 진행."
    do_release "dev-$DEV" 1 && tg "🚑 dev($DEV) 로 v$DEPLOYED 긴급 배포 완료. 사용자 앱 자동 필수 업데이트."
  else
    git checkout -- app/build.gradle.kts
    tg "🛑 유튜브 깨짐 + 정식·dev 모두 추출 실패. 자동 복구 불가 — 수동 대응 필요(NewPipe 이슈/유튜브 변경 조사)."
  fi
  exit 0
fi

# B/D) 정식 최신으로 교체(새 정식 릴리스, 또는 dev→정식 안정화)
log "정식 v$LATEST_NP 로 교체 시도 (STATE=$STATE)"
set_np "v$LATEST_NP"
if smoke && regress; then
  URGENT=$([ "$CUR_OK" = 0 ] && echo 1 || echo 0)
  do_release "v$LATEST_NP" "$URGENT" && {
    if [ "$URGENT" = 1 ]; then tg "🚑 유튜브 복구 — v$DEPLOYED 긴급 배포 완료(NewPipe v$LATEST_NP)."
    elif [ "$STATE" = dev ]; then tg "✅ 정식 NewPipe v$LATEST_NP 나와 v$DEPLOYED 로 안정화 배포."
    else tg "✅ NewPipe v$LATEST_NP 적용해 v$DEPLOYED 자동 배포(선제 호환)."; fi
  }
else
  git checkout -- app/build.gradle.kts
  # dev 상태에서 정식이 아직 안정적이지 않으면 조용히 유지(스팸 방지), 정식 상태에서 실패면 알림
  [ "$STATE" = stable ] && tg "🆕 NewPipe v$LATEST_NP 나왔지만 자동 검증 실패 → 롤백. 수동 확인 필요."
  log "정식 검증 실패 → 롤백(무배포)"
fi

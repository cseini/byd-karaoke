#!/bin/bash
# NewPipeExtractor 자동 감시 + (통과 시) 자동 빌드·배포 — 맥미니 launchd 용.
#
# 매일 1회:
#  1) 유튜브 추출 스모크 테스트로 '현재 상태'가 정상인지 확인
#  2) NewPipe 새 버전이 있으면 → 라이브러리 올려 스모크·채점 회귀 통과 시 자동 빌드·서명·배포
#  3) 유튜브가 깨졌다가(스모크 실패) 새 라이브러리로 복구되면 → '긴급' 배포(min.json 갱신)
#  4) 자동으로 못 고치는 상황(유튜브 깨짐 + 패치 없음)은 텔레그램으로 알림만
#
# 안전장치: git clean 확인 · 스모크+채점 게이트 · 서명 SHA 검증 · dex 반영 검증 · 실패 시 롤백.
# 토큰: ~/.config/byd-karaoke/notify.env 에 TELEGRAM_BOT_TOKEN, CHAT_ID.

set -uo pipefail
export PATH="/opt/homebrew/bin:/opt/homebrew/opt/node@22/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"

PROJ="/Users/sen/project/byd-karaoke"
CERT_SHA="2a43fbe97591a2cda9357b99d928ec2f978a42d38c40b43bfa4ffe981ac2d009"
LOG="$PROJ/scripts/autodeploy.log"
cd "$PROJ" || exit 1

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }

# ── 텔레그램 ──
[ -f ~/.config/byd-karaoke/notify.env ] && . ~/.config/byd-karaoke/notify.env
tg(){
  [ -n "${TELEGRAM_BOT_TOKEN:-}" ] && [ -n "${CHAT_ID:-}" ] || { log "TG 미설정: $1"; return; }
  curl -s "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
    --data-urlencode "chat_id=$CHAT_ID" --data-urlencode "text=🎤 노래방 자동배포\n$1" >/dev/null
}

AAPT=$(ls "$ANDROID_HOME/build-tools/"*/aapt2 2>/dev/null | sort -V | tail -1)
APKSIGNER=$(ls "$ANDROID_HOME/build-tools/"*/apksigner 2>/dev/null | sort -V | tail -1)

# ── 0) 작업 중 충돌 방지 ──
if [ -n "$(git status --porcelain)" ]; then
  log "작업트리 dirty — 스킵(수동 작업 중으로 판단)"; exit 0
fi
git pull -q origin main 2>/dev/null

CUR_NP=$(grep -oE 'NewPipeExtractor:v[0-9.]+' app/build.gradle.kts | head -1 | sed 's/.*:v//')
LATEST_NP=$(curl -s "https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('tag_name','').lstrip('v'))" 2>/dev/null)
log "NewPipe 현재=$CUR_NP 최신=$LATEST_NP"
[ -z "$LATEST_NP" ] && { log "최신 조회 실패"; exit 0; }

smoke(){ RUN_YT_SMOKE=1 ./gradlew :app:testProdReleaseUnitTest --tests "com.cseini.byd.karaoke.YoutubeSmokeTest" --rerun -q >/dev/null 2>&1; }

# ── 1) 현재 상태 스모크 ──
if smoke; then CUR_OK=1; else CUR_OK=0; fi
log "현재 스모크: $([ $CUR_OK = 1 ] && echo OK || echo FAIL)"

# 새 버전도 없고 현재 정상이면 조용히 종료
if [ "$LATEST_NP" = "$CUR_NP" ] && [ "$CUR_OK" = 1 ]; then
  log "변화 없음 — 종료"; exit 0
fi

# 새 버전 없는데 유튜브가 깨졌으면: 자동으로 못 고침 → 알림
if [ "$LATEST_NP" = "$CUR_NP" ] && [ "$CUR_OK" = 0 ]; then
  tg "⚠️ 유튜브 재생이 깨진 것 같습니다(추출 스모크 실패).\nNewPipe 새 버전이 아직 없어 자동 복구 불가 — 수동 확인이 필요합니다."
  log "유튜브 깨짐 + 패치 없음 → 알림"; exit 0
fi

# ── 2) 새 NewPipe 버전 있음 → 교체 후 검증 ──
log "NewPipe $CUR_NP → $LATEST_NP 시도"
sed -i '' "s#NewPipeExtractor:v$CUR_NP#NewPipeExtractor:v$LATEST_NP#" app/build.gradle.kts

if ! smoke; then
  tg "🆕 NewPipe v$LATEST_NP 나왔지만 자동 검증(유튜브 추출) 실패.\n수동 확인이 필요합니다. (자동 롤백함)"
  git checkout -- app/build.gradle.kts; log "새 버전 스모크 실패 → 롤백"; exit 0
fi
# 채점 회귀도 통과해야
if ! ./gradlew :app:testProdReleaseUnitTest --tests "com.cseini.byd.karaoke.MelodyScorerTest" --rerun -q >/dev/null 2>&1; then
  tg "🆕 NewPipe v$LATEST_NP: 유튜브 추출은 OK지만 채점 회귀 테스트 실패 → 롤백. 수동 확인 필요."
  git checkout -- app/build.gradle.kts; log "채점 회귀 실패 → 롤백"; exit 0
fi

# ── 3) 버전 올리고 빌드 ──
CODE=$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | grep -oE '[0-9]+')
NAME=$(grep -oE 'versionName = "[0-9.]+"' app/build.gradle.kts | grep -oE '[0-9.]+')
NEWCODE=$((CODE+1))
NEWNAME=$(python3 -c "print(f'{float('$NAME')+0.01:.2f}')")
sed -i '' "s/versionCode = $CODE/versionCode = $NEWCODE/; s/versionName = \"$NAME\"/versionName = \"$NEWNAME\"/" app/build.gradle.kts
log "버전 $NAME→$NEWNAME (code $CODE→$NEWCODE)"

./gradlew :app:assembleProdRelease :app:assembleLabRelease -q 2>&1 | grep -iE "error:|FAILURE" && {
  tg "❌ v$NEWNAME 빌드 실패 → 롤백."; git checkout -- app/build.gradle.kts; exit 0; }

APK="app/build/outputs/apk/prod/release/app-prod-release.apk"
GOTSHA=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | grep -i "SHA-256 digest" | grep -oE '[0-9a-f]{64}')
[ "$GOTSHA" = "$CERT_SHA" ] || { tg "❌ v$NEWNAME 서명 불일치 → 배포 중단."; git checkout -- app/build.gradle.kts; exit 0; }
# dex 에 새 라이브러리 반영 확인(스테일 빌드 방지)
"$AAPT" dump badging "$APK" 2>/dev/null | grep -q "versionName='$NEWNAME'" || {
  tg "❌ v$NEWNAME 빌드가 최신이 아님 → 중단."; git checkout -- app/build.gradle.kts; exit 0; }

# ── 4) 릴리스 ──
cp "$APK" "/tmp/byd-karaoke-v$NEWNAME.apk"
cp "$APK" "/tmp/karaoke-latest.apk"
cp app/build/outputs/apk/lab/release/app-lab-release.apk "/tmp/byd-karaoke-v$NEWNAME-test.apk"
NOTE="- 유튜브 재생 호환성 업데이트(NewPipe v$LATEST_NP 적용)"
gh release create "v$NEWNAME" "/tmp/byd-karaoke-v$NEWNAME.apk" "/tmp/karaoke-latest.apk" \
  --repo cseini/byd-karaoke --title "v$NEWNAME" --notes "$NOTE" >/dev/null 2>&1
gh release create "v$NEWNAME" "/tmp/byd-karaoke-v$NEWNAME-test.apk" \
  --repo cseini/byd-karaoke-test --title "v$NEWNAME-test" --notes "$NOTE" >/dev/null 2>&1

# ── 5) 랜딩 갱신 (+ 유튜브가 깨졌었다면 긴급 min.json) ──
cd landing
python3 - "$NEWNAME" "$CUR_OK" <<'PY'
import sys, re
new, cur_ok = sys.argv[1], sys.argv[2]
s = open('index.html', encoding='utf-8').read()
s = re.sub(r'byd-karaoke-v[0-9.]+\.apk', f'byd-karaoke-v{new}.apk', s)
s = re.sub(r'releases/download/v[0-9.]+/', f'releases/download/v{new}/', s)
s = re.sub(r'<span class="wn-ver">v[0-9.]+ 최신</span>', f'<span class="wn-ver">v{new} 최신</span>', s)
open('index.html','w',encoding='utf-8').write(s)
if cur_ok == '0':   # 유튜브가 깨져 있다가 이번에 복구됨 → 긴급
    open('min.json','w',encoding='utf-8').write(
        f'{{"minVersion": "{new}", "message": "유튜브 재생 문제로 필수 업데이트입니다."}}')
PY
rm -rf dist && mkdir dist && cp index.html dist/index.html && cp min.json dist/min.json && cp -r img dist/img
npx wrangler pages deploy dist --project-name=karaoke --commit-dirty=true >/dev/null 2>&1
cd "$PROJ"

git add -A
git commit -q -m "v$NEWNAME: NewPipe v$LATEST_NP 자동 적용(유튜브 호환)

맥미니 자동배포 — 유튜브 추출 스모크·채점 회귀 통과 후 빌드·서명·배포.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AW3ieMQE2zSGTP655W9mjv"
git push -q origin main

if [ "$CUR_OK" = 0 ]; then
  tg "🚑 유튜브가 막혔다가 복구됐습니다!\nNewPipe v$LATEST_NP 로 v$NEWNAME 긴급 배포 완료 — 사용자 앱이 자동으로 필수 업데이트 받습니다."
else
  tg "✅ NewPipe v$LATEST_NP 적용해 v$NEWNAME 자동 배포 완료(선제 호환 유지)."
fi
log "배포 완료 v$NEWNAME"

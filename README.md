# BYD Karaoke — 차량용 노래방 앱

BYD 돌핀(DiLink) 인포테인먼트용 노래방 앱. 유튜브 반주 영상 재생 + 마이크 자체 분석 채점 + 예약 대기열.

계획 전문: `~/.claude/plans/byd-apk-sharded-perlis.md`

## 현재 상태: Phase 0 스파이크

본 구현 전, **실차에서만 확정 가능한 두 개의 make-or-break 게이트**를 검증하는 최소 APK다.
채점·검색·예약 로직은 아직 없다(게이트 통과 후 Phase 1/2).

- **0-A 유튜브 렌더**: DiLink WebView 가 유튜브 IFrame 을 실제로 그리는가(블랙스크린/오디오온리 아닌가).
- **0-B 마이크 채점 성립**: 반주가 스피커로 나가는 중에 USB 마이크로 채점 가능한 보컬 F0 를 뜰 수 있는가.

## 빌드

```bash
export ANDROID_HOME=~/Library/Android/sdk
./gradlew :app:assembleDebug
# 산출물: app/build/outputs/apk/debug/app-debug.apk
```

## OTA 자동 업데이트

앱이 시작될 때 이 저장소의 **GitHub Releases 최신 태그(vX.Y)** 를 현재 버전과 비교해,
새 버전이면 APK 를 자동 다운로드하고 설치 화면을 띄운다(설치 확인 탭 1회는 Android 정책상 필요).

새 버전 배포:

```bash
# 1) app/build.gradle.kts 의 versionCode/versionName 올리기 (예: 0.3 → 0.4)
# 2) 빌드 + 릴리스 생성 + APK 업로드
./tools/release.sh "변경 내용 요약"
```

전제 조건:
- 차량이 인증 없이 릴리스를 조회하므로 **저장소가 public** 이어야 한다
  (private 이면 업데이트 확인이 조용히 실패하고 앱은 정상 동작).
- **기존 설치본과 같은 디버그 키스토어**(같은 PC의 `~/.android/debug.keystore`)로
  빌드해야 덮어쓰기 설치가 된다. 키가 다르면 차에서 기존 앱을 지우고 새로 설치해야 한다.
- 차에서 최초 1회 "출처를 알 수 없는 앱 설치" 허용을 이 앱에 켜줘야 한다.

## 돌핀에 설치 (사이드로드)

```bash
adb connect <차량 IP>:5555      # 또는 USB 연결
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep karaoke-spike  # 환경/입력장치 로그 확인
```

## 실차 검증 절차

앱 실행 후:

1. **③ 환경 정보 새로고침** → Android 버전 / WebView 버전 / UNPROCESSED·AEC 지원 / **입력 장치 목록** 확인.
   - USB 마이크 동글을 꽂았는데 목록에 `USB_DEVICE`/`USB_HEADSET` 가 **안 보이면** 0-B 는 그 시점에서 막힌 것.
2. **0-A**: `영상 로드` (기본 영상 또는 금영/태진 videoId 붙여넣기).
   - 소리+화면 정상 → 게이트 통과. **PLAYING 인데 화면이 검으면** 하드웨어가속/WebView 문제(계획의 0-A 리스크).
3. **0-B**: 반주를 재생(스피커로 나오게)한 상태에서 소스·AEC 조합을 바꿔가며 각각 ~15초 녹음:
   - 먼저 **노래 안 부르고 반주만** 1회(기준선).
   - 그다음 **노래 부르며**: `UNPROCESSED`(AEC off) / `VOICE_RECOGNITION`(off) / `VOICE_COMMUNICATION`(AEC **on**) 각각.
   - `USB 마이크로 강제 라우팅` 체크는 켜둔 채로.
4. WAV 회수 후 분석:
   ```bash
   adb pull /sdcard/Android/data/com.cseini.byd.karaoke/files/ ./wavs
   python3 tools/analyze_pitch.py wavs/spike_*반주만*.wav wavs/spike_*.wav
   ```
   - '반주만' 기준선의 **유성% 가 가장 낮으면서** 노래 파일 F0 가 잘 잡히는(안정 cents 낮은) 조합이 승자 → Phase 2 채점이 그 오디오 경로 위에 선다.

## 판정

- 0-A ✅ & 0-B ✅ → Phase 1(검색·예약·임베드) 착수.
- 0-A ❌ → 유튜브 전제 재검토(오디오 전용 등).
- 0-B ❌ → 클로즈토크 마이크 / 스피커 볼륨↓ / 레퍼런스 기반 반주 취소로 포크.

## 확정 기술 스택 (Phase 1/2)

- 유튜브: `com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0`
- 피치: `be.tarsos.dsp:core:2.5` (repo `https://mvn.0110.be/releases`) + YIN
- 검색: YouTube Data API v3 (사용자 API 키 필요)
- 예약: Room / 재생: android-youtube-player `onStateChange(ENDED)` → `loadVideoById`

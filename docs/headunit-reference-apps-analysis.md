# BYD 헤드유닛 서드파티 앱 분석 — 노래방 앱 적용 참조

> 2026-07-26 작성. BYD DiLink(안드로이드 IVI) 위에서 도는 서드파티 앱 3종을
> 디컴파일 분석해, **노래방 앱(com.cseini.byd.karaoke)에 써먹을 기법**을 정리한 문서.
> 원본 분석은 오토도어(byd-sniffer) 상시생존 작업 중 수행. 디컴파일은 jadx 사용.

## 분석 대상 3종

| 앱 | 패키지 | 성격 | targetSdk | 핵심 특징 |
|---|---|---|---|---|
| Electro | br.com.rory.electro | 충전/원격제어 | 25 | 접근성(SecondaryService) 단일프로세스 상시생존 |
| **diplus** | com.van.diplus | 센트리(주차녹화) | 28 | **접근성+별도프로세스+포그라운드 상시생존, 스티어링휠 버튼, BYD 제어 카탈로그** |
| kinex | com.lexwah.kinex | 런처+오버레이 | 36 | 오버레이 UI, pm grant 셀프권한, 알림리스너 |

디컴파일 원본(재분석 필요 시): 세 APK를 jadx로 풀면 됨.
- diplus: `com.van.diplus.service.KDService`, `MainService`, `byd/` 패키지
- kinex: `com.lexwah.kinex.services.KinexBottomBarOverlayService`, `MainActivity`

---

## 🥇 1. 스티어링휠 버튼으로 노래방 조작 (diplus KDService)

**가장 유용.** 운전대 물리버튼으로 다음곡/이전곡/볼륨/일시정지를 조작 — 주행 중 터치 없이 안전.

### 원리
접근성 서비스가 `onKeyEvent(KeyEvent)`로 물리 키를 가로챈다. 매니페스트 접근성 config에
`FLAG_REQUEST_FILTER_KEY_EVENTS`(flags 값 32)를 줘야 키 이벤트가 서비스로 전달됨.

```
// diplus KDService.onKeyEvent 에서 처리하는 keyCode
291, 292, 293, 294  → BYD 스티어링휠 커스텀 버튼(상/하/좌/우 또는 +/-)
87 (KEYCODE_MEDIA_NEXT)      → 다음곡
88 (KEYCODE_MEDIA_PREVIOUS)  → 이전곡
```

### 노래방 적용
1. `KaraokeKeyAccessibilityService extends AccessibilityService` 추가
2. 접근성 config xml: `android:accessibilityFlags="flagRequestFilterKeyEvents"` +
   런타임에서 `serviceInfo.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS; setServiceInfo(...)`
   (diplus는 onServiceConnected에서 `serviceInfo.flags |= 32` 후 setServiceInfo 호출)
3. `onKeyEvent`에서 keyCode 매핑 → PlaybackActivity/EmbeddedPlayer로 브로드캐스트(다음곡/볼륨)
4. 실제 스티어링휠 keyCode는 차량마다 다를 수 있으니, 최초엔 `onKeyEvent`에서 눌린 keyCode를
   전부 로깅해 실측 매핑부터 확인할 것(291~294 후보).

주의: 접근성 서비스는 사용자가 설정>접근성에서 켜야 활성. ROM이 `WRITE_SECURE_SETTINGS`를
자동 부여하면(오토도어에서 확인됨) `Settings.Secure.putString("enabled_accessibility_services", ...)`로
셀프 활성 가능.

---

## 🥈 2. 헤드유닛 화면 오버레이 (kinex KinexBottomBarOverlayService)

노래방 재생 중 **다른 앱(내비 등) 위에 가사/컨트롤 바**를 띄우거나, 전체화면 위 미니 컨트롤.

### 원리
- 권한: `SYSTEM_ALERT_WINDOW` (+ `FOREGROUND_SERVICE_SPECIAL_USE`)
- `WindowManager` + `LayoutParams(TYPE_APPLICATION_OVERLAY)`로 뷰를 화면에 addView
- kinex는 오버레이 서비스를 **별도 프로세스(`:kinex_overlay`)** + 포그라운드로 돌림
  (오버레이가 메인 UI와 독립적으로 유지되도록)

### 노래방 적용
- 백그라운드 재생 중 미니 컨트롤(다음곡/일시정지) 오버레이
- 가사 자막을 시스템 오버레이로(다른 앱 위에서도 보이게)
- 필요성 낮으면 후순위 — 큰 작업.

---

## 🥉 3. 백그라운드 상시 재생/생존 (diplus KDService+MainService)

노래방이 **화면 꺼져도/다른 앱 위에서도 재생 유지**가 필요하면 참고.
(현재 노래방은 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 보유 — 표준 재생 유지는 이미 가능)

### diplus 상시생존 3중 구조 (센트리라 시동 꺼도 녹화 유지)
1. **접근성 서비스**(BIND_ACCESSIBILITY_SERVICE) — 시스템이 바인딩·재바인딩하는 컴포넌트
2. **별도 프로세스**(`android:process=":remote"`) — 메인 죽어도 분리 생존
3. **포그라운드**(`startForeground` + `foregroundServiceType`) — 킬 우선순위 최하위

이 셋을 KDService 한 컴포넌트에 겹침. 메인(MainService) 죽으면 KDService가 재기동.
MainService는 추가로 `AlarmManager.set`으로 자기 재시작 백스톱.

**노래방엔 과할 수 있음** — 노래방은 사용자가 켤 때만 돌면 되니, 표준 MediaPlayback
포그라운드로 충분. 단, "주차 중에도 노래방 유지"를 원하면 이 구조 차용.

---

## 4. BYD 차량 제어 (diplus byd/ 패키지) — 노래방 시너지

diplus가 실제 쓰는 BYD SDK 제어. 노래방 관련 유용 후보:

| 기능 | API | 노래방 활용 |
|---|---|---|
| 공조 풍량/모드 | `BYDAutoAcDevice.setAcWindMode/setAcVentilationState` | 노래방 모드 진입 시 소음 줄이기(풍량↓) |
| 엔진음 시뮬레이터 | `setEngineVoiceSimulatorState` | 몰입 위해 끄기 |
| 앰비언트 라이트 | `BYDAutoLightDevice` 계열 | 노래방 분위기 조명 연출 |

BYD SDK 접근은 hidden API라 리플렉션 필요. 오토도어(byd-sniffer)에서 검증된 방식:
- `SnifferApp.exemptHiddenApis()` — `VMRuntime.setHiddenApiExemptions("L")` double-reflection으로
  hidden API blacklist 해제(순수 Java, 권한 불필요). 이거 없으면 `BYDAuto*Device` 리플렉션이 denied.
- device의 내부 `mContext`가 null이면 NPE → `VehicleContextWrapper`(권한체크 통과 구현) 강제 주입.
- 상세: 오토도어 메모리 `byd-relay-coldboot-bottleneck`, `byd-afterblow-relay` 참조.

---

## 5. 권한 셀프 부여 (kinex/diplus) — 필요 시

- **kinex**: 외부 adb 연결 상태에서 `pm grant`/`appops set`/`cmd notification allow_listener` 실행.
  adbd 권한 필요(사용자가 무선 adb 붙인 상태).
- **diplus**: `castremote/adblib` — 자체 무선 adb 클라이언트 내장(페어링까지). adb 앱 없이 자체 grant.
- BYD ROM은 설치만으로 `WRITE_SECURE_SETTINGS`를 자동 부여함(오토도어에서 확인) →
  접근성/알림리스너 셀프 활성에 이 권한 활용 가능(`Settings.Secure.putString`).

---

## 음성명령 (diplus KDService) — 아이디어

diplus는 접근성으로 중국어 음성명령("迪加关空调"=공조끄기 등)을 처리.
노래방에 "다음곡", "정지" 같은 한국어 음성명령을 붙이면 주행 중 핸즈프리 조작 가능.
(음성인식 엔진은 별도 필요 — Android SpeechRecognizer 또는 온디바이스)

---

## 요약 — 노래방 우선순위

1. **스티어링휠 버튼 조작** (접근성 onKeyEvent) — 주행 안전 + 편의, 가장 실용적
2. **BYD 제어 시너지** (노래방 모드 시 풍량↓/조명 연출) — hidden API 방식은 오토도어에 검증됨
3. **음성명령** — 핸즈프리
4. **오버레이 컨트롤** — 후순위(큰 작업)
5. 상시생존 3중 구조 — 노래방엔 대개 불필요(표준 MediaPlayback으로 충분)

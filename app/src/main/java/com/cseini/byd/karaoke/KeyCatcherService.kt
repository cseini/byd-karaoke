package com.cseini.byd.karaoke

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * (diplus KDService 방식) 접근성 onKeyEvent 로 물리 키를 전역 가로채기.
 * USB 마이크의 버튼(전원/마이크 등)이 보내는 키로 노래방 음성검색을 즉시 실행한다.
 * 앱이 포커스가 없어도(분할화면·다른 앱 위) 동작하는 것이 dispatchKeyEvent 와의 차이.
 *
 * 설정 > 접근성에서 사용자가 켜야 활성화된다.
 */
class KeyCatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "karaoke-keys"
        const val ACTION_VOICE = "com.cseini.byd.karaoke.VOICE_SEARCH"
        // 켜져 있으면 MainActivity 가 키보드 마이크 자동 클릭을 요청할 수 있다(꺼져 있으면 null).
        @Volatile var instance: KeyCatcherService? = null
        // 씨라이언 음성검색 종료 후 마이크를 소프트 재삽입하는 동안 트리거를 잠깐 막는다 — 재삽입이 만드는
        // USB ATTACH·BYD 팝업이 음성검색을 다시 띄워 무한 루프가 되는 것을 방지. MainActivity 가 재삽입 직전 설정.
        @Volatile var suppressTriggerUntil = 0L
        // 음성검색 트리거 후보(마이크/헤드셋 계열 표준 키). 볼륨은 차 시스템에 넘긴다.
        private val TRIGGER_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_VOICE_ASSIST,
            KeyEvent.KEYCODE_SEARCH,
        )
        private const val DEBOUNCE_MS = 800L
        // 휠 무음 버튼(device=8) — 실측 keyCode. 더블클릭으로 음성검색을 부른다.
        private const val KEYCODE_WHEEL_MUTE = 293   // KEYCODE_AUTO_VOLUME_MUTE
        private const val DOUBLE_MS = 500L
        // 씨라이언7 모드: 팝업 잔여 이벤트로 인한 중복 트리거는 막되, 사용자가 자동재생 카운트다운
        // 중(약 5초) 버튼을 다시 눌러 재검색하는 것은 막지 않도록 짧게 잡는다.
        const val SEALION_COOLDOWN_MS = 2000L

        // 씨라이언7 순정마이크(BYD-micTS02) 버튼 실측(v6.51 D1, 접근성 onKeyEvent): 수신기가 키보드처럼
        // F1(131)=전원 ON, F2(132)=전원 OFF, F3(133)=볼륨창(패널) 버튼을 보낸다. BYD 의 MicKeyService 도
        // 같은 키를 받아 micevent 브로드캐스트·볼륨창을 만든다. 즉 볼륨창 버튼은 F3 로 정확히 구분된다.
        const val KEYCODE_BYD_MIC_PANEL = KeyEvent.KEYCODE_F3

        /**
         * 씨라이언 모드 키 트리거 판정 — 순수 함수(단위 테스트). 전원 버튼(F1/F2)·볼륨(F4/F5)·일반 키는
         * 절대 트리거하지 않는다(전원 신호에 걸었다가 BYD 마이크를 망가뜨린 6.37~6.45 재발 방지).
         * @param sinceLast 직전 트리거 뒤 경과 ms — 한 번 누름이 F3 두 번으로 오는 실측(19:09:34/35) 중복 방지.
         */
        fun sealionKeyTrigger(code: Int, sealionMode: Boolean, sinceLast: Long): Boolean =
            sealionMode && code == KEYCODE_BYD_MIC_PANEL && sinceLast >= SEALION_COOLDOWN_MS
    }

    private var lastTrigger = 0L
    private var lastMuteDown = 0L
    private var lastPanel = 0L   // BYD 노래방 패널 등장 감지 디바운스
    private var lastWin = ""     // window 계측 중복 제거

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 매니페스트 config 에 더해 런타임에서도 키 필터 플래그를 확실히 켠다(diplus 동일 패턴).
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS   // 키보드 창 노드 접근용
        }
        instance = this
        Log.i(TAG, "KeyCatcher 연결됨 — 키 가로채기 시작")
        // 원격 로그에 '테스트 중 서비스가 실제로 붙어 있었다'는 증거를 남긴다(키가 안 찍힐 때 원인 구분용).
        runCatching { CrashLog.event(this, "a11y 연결 flags=${serviceInfo?.flags}") }
    }

    private var lastKeyLogCode = -1
    private var lastKeyLogAt = 0L

    /**
     * 들어오는 모든 물리 키를 원격 로그로 남긴다(동작 변경 없음). 씨라이언7 순정마이크 버튼이
     * 접근성 KeyEvent 로 오는지 지금까지 한 번도 원격에서 본 적이 없다 — BYD 자체 앱이
     * MicKeyService(접근성)로 마이크 키를 받는 것이 진단에 잡혀 확인이 필요하다.
     * 휠 옵션과 무관하게 찍되, 같은 키가 0.5초 안에 반복되면(연타·오토리핏) 한 번만 남긴다.
     */
    private fun logKey(e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount != 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (e.keyCode == lastKeyLogCode && now - lastKeyLogAt < 500L) return
        lastKeyLogCode = e.keyCode; lastKeyLogAt = now
        runCatching {
            val devName = android.view.InputDevice.getDevice(e.deviceId)?.name ?: "?"
            CrashLog.event(this, "a11y key=${e.keyCode}(${KeyEvent.keyCodeToString(e.keyCode)}) dev=${e.deviceId}/$devName src=${e.source} scan=${e.scanCode}")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * 키보드(IME) 창에서 '음성 입력' 버튼 노드를 찾아 클릭한다. Gboard 등 IME 의 음성모드로
     * 진입시켜, 앱이 부를 STT 가 없는 유닛에서도 음성검색이 되게 한다. 결과 텍스트는 검색창으로
     * 들어와 자동 검색된다. 성공·실패를 D1 로 남겨(원격) 노드 식별이 되는지 확인한다.
     */
    fun clickKeyboardMic(logFail: Boolean = true): Boolean {
        val cands = listOf("음성", "voice", "mic", "마이크", "말하기", "speak")
        val ws = windows ?: return false
        for (w in ws) {
            val root = w.root ?: continue
            // 정상 키보드(IME 창) 또는 Gboard 플로팅/최소화 툴바(창 타입이 IME 가 아닐 수 있음)를 모두 본다.
            // 플로팅 툴바는 좌/우/하 어디에 붙어도 노드 트리로 찾으므로 위치는 무관하다.
            val fromKeyboard = w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                root.packageName?.toString()?.contains("inputmethod") == true
            if (!fromKeyboard) continue
            val hit = findMicNode(root, cands) ?: continue
            var t: AccessibilityNodeInfo? = hit
            while (t != null && !t.isClickable) t = t.parent
            val ok = (t ?: hit).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            CrashLog.event(this, "kbdmic clicked=$ok desc='${hit.contentDescription}'")
            return ok
        }
        // 실패 시 창 목록(타입:패키지)을 남겨, 플로팅 툴바가 어떤 창으로 뜨는지 원격으로 식별한다.
        if (logFail) {
            val wins = ws.joinToString(",") { "${it.type}:${it.root?.packageName ?: "?"}" }.take(140)
            CrashLog.event(this, "kbdmic 못찾음 [$wins]")
        }
        return false
    }

    /**
     * 키보드가 뜨는 타이밍이 유닛마다 달라(ime=0 실패), 키보드 창이 실제로 올라와 음성 버튼을
     * 누를 수 있을 때까지 짧게 폴링하며 재시도한다. 성공 즉시 멈추고, 소진 시 마지막에만 실패를 남긴다.
     */
    fun clickKeyboardMicRetry(triesLeft: Int = 10) {
        if (clickKeyboardMic(logFail = false)) return
        if (triesLeft <= 1) { clickKeyboardMic(logFail = true); return }
        retryHandler.postDelayed({ clickKeyboardMicRetry(triesLeft - 1) }, 250)
    }

    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun findMicNode(node: AccessibilityNodeInfo, cands: List<String>): AccessibilityNodeInfo? {
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        if (desc.isNotEmpty() && cands.any { desc.contains(it) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findMicNode(child, cands)?.let { return it }
        }
        return null
    }

    private val settings by lazy { com.cseini.byd.karaoke.data.SettingsStore(this) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        logKey(event)
        // 씨라이언7 모드: 볼륨창 버튼(F3) → 음성검색. 키는 소비하지 않고 통과시킨다(return false) —
        // BYD MicKeyService 는 어차피 자기 몫의 키를 따로 받으므로 소비해도 볼륨창은 막히지 않는다.
        // 뒤따라오는 팝업 감지(onAccessibilityEvent)는 같은 lastTrigger 쿨다운으로 중복이 걸러진다.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= suppressTriggerUntil &&
                sealionKeyTrigger(event.keyCode, settings.sealionMode, now - lastTrigger)) {
                lastTrigger = now
                runCatching { CrashLog.event(this, "sealion F3 키 → 음성검색") }
                startVoiceSearch(sealion = true)
            }
        }
        if (!settings.wheelButtonControl) return false   // 설정 꺼짐 → 아무 키도 안 가로챔(기본 동작 유지)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        val code = event.keyCode
        Log.i(TAG, "keyDown=$code (${KeyEvent.keyCodeToString(code)}) device=${event.deviceId}")

        // 휠 무음 버튼 더블클릭 → 음성검색. 소비하지 않고 통과시켜(return false) 단일 누름의
        // 정상 음소거를 유지한다. 더블클릭이면 음소거가 두 번 토글돼 원상복귀 + 음성검색 실행.
        if (code == KEYCODE_WHEEL_MUTE) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastMuteDown in 1..DOUBLE_MS) {
                lastMuteDown = 0L
                startVoiceSearch()
            } else {
                lastMuteDown = now
            }
            return false
        }

        if (code in TRIGGER_KEYS) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTrigger < DEBOUNCE_MS) return true   // 따닥 중복 방지
            lastTrigger = now
            startVoiceSearch()
            return true   // 이 키는 우리가 소비(음악앱 등으로 안 넘어가게)
        }

        return false   // 나머지 키는 시스템에 그대로 통과
    }

    /** 노래방을 앞으로 가져오며 음성검색 시작(접근성 앱은 백그라운드 액티비티 시작 허용). */
    private fun startVoiceSearch(sealion: Boolean = false) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_VOICE)
                .putExtra("sealion", sealion)   // 팝업이 화면을 가리므로 소리·오버레이 안내를 켠다
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
    }

    /**
     * USB 마이크 버튼을 HID·KeyEvent·커널input 어디로도 못 잡는 유닛(씨라이언7/DiLink5) 우회:
     * 마이크 버튼 → BYD SDK → 'com.byd.minikaraoke' 패널이 뜬다. 그 패널 등장(window 상태변화)을
     * 감지해 음성검색을 실행한다. 버튼 신호 대신 버튼이 만든 결과를 잡는 방식.
     * micButtonControl(마이크 버튼 제어) 옵션이 켜져 있을 때만 동작.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        // BYD 노래방 앱 패키지는 유닛마다 다르다 — 아토3/DiLink 는 com.byd.minikaraoke, 씨라이언7 은 com.byd.sing.
        val pkg = event.packageName?.toString()
        if (pkg != "com.byd.minikaraoke" && pkg != "com.byd.sing") return
        val now = android.os.SystemClock.elapsedRealtime()
        val type = if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) "STATE" else "CONTENT"

        // 씨라이언7 모드: BYD 노래방 팝업이 떴다 = (마이크든 볼륨이든) 버튼이 눌렸다는 뜻.
        // 버튼 종류는 구분 안 되지만 아무 버튼이나 음성검색을 띄우는 게 목적이라 그대로 트리거.
        // D1 실측(2026-09-02): 이 팝업은 CONTENT_CHANGED 로만 관측됨(STATE 0건) — 타입으로 거르면 안 된다.
        if (settings.sealionMode) {
            if (now >= suppressTriggerUntil && now - lastTrigger >= SEALION_COOLDOWN_MS) {
                lastTrigger = now
                runCatching { CrashLog.event(this, "sealion 팝업감지($type) → 음성검색") }
                // back 은 팝업뿐 아니라 우리 앱 액티비티까지 종료시켜(홈으로 나감) 위험하므로 쓰지 않는다.
                // 앱을 앞으로 가져오면 팝업은 그 뒤로 가려지고, BYD 팝업은 스스로 잠시 뒤 사라진다.
                startVoiceSearch(sealion = true)
            }
            return
        }

        if (now - lastPanel < 300L) return
        lastPanel = now
        // 조사: 마이크/볼륨업/볼륨다운을 누를 때 STATE(패널 새로 뜸) vs CONTENT(내용만 변화),
        // 그리고 패널에 딸린 텍스트/설명이 버튼마다 다른지 본다. 구분 신호를 찾으면 그걸로 트리거한다.
        val txt = event.text?.joinToString("|")?.take(80) ?: ""
        val sig = "$type txt=[$txt] desc=[${event.contentDescription ?: ""}] cls=${event.className}"
        if (sig != lastWin) {
            lastWin = sig
            runCatching { CrashLog.event(this, "byd $sig") }
        }
    }

    override fun onInterrupt() {}
}

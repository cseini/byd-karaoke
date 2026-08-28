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
    }

    private var lastTrigger = 0L
    private var lastMuteDown = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 매니페스트 config 에 더해 런타임에서도 키 필터 플래그를 확실히 켠다(diplus 동일 패턴).
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS   // 키보드 창 노드 접근용
        }
        instance = this
        Log.i(TAG, "KeyCatcher 연결됨 — 키 가로채기 시작")
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
    fun clickKeyboardMic(): Boolean {
        val cands = listOf("음성", "voice", "mic", "말하기", "speak")
        val ws = windows ?: return false
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = w.root ?: continue
            val hit = findMicNode(root, cands) ?: continue
            var t: AccessibilityNodeInfo? = hit
            while (t != null && !t.isClickable) t = t.parent
            val ok = (t ?: hit).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            CrashLog.event(this, "kbdmic clicked=$ok desc='${hit.contentDescription}'")
            return ok
        }
        CrashLog.event(this, "kbdmic 못찾음 windows=${ws.size} ime=${ws.count { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }}")
        return false
    }

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
    private fun startVoiceSearch() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_VOICE)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

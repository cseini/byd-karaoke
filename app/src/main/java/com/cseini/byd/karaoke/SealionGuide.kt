package com.cseini.byd.karaoke

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

/**
 * 씨라이언7 모드 안내 — BYD 노래방 팝업이 화면을 가려도 아이가 '말할 때'를 알 수 있게
 * (1) 음성으로 "노래 제목을 말하세요" 안내(TTS)와 (2) 팝업 위에 뜨는 '🎤 말하세요' 띠
 * (시스템 오버레이)를 함께 띄운다. 오버레이 권한이 없으면 소리 안내만 한다.
 *
 * 주의: TTS 는 스피커로 나가 차량 마이크에 그대로 녹음될 수 있으므로, 호출부는 이 안내가
 * 끝난 뒤(약 2초) 실제 음성 인식을 시작한다.
 */
class SealionGuide(private val context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile var ttsReady = false
        private set
    private var overlay: View? = null
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hideOverlay() }

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) runCatching { tts?.language = Locale.KOREAN }
        }
    }

    /** 오버레이를 그릴 수 있는지(권한). 계측·표시 결정에 쓴다. */
    val canOverlay: Boolean get() = Settings.canDrawOverlays(context)

    /** 소리 안내 + 팝업 위 띠. 콜백 hide 를 못 받는 경로 대비 자동 숨김 타이머를 건다. */
    fun show(msg: String = "노래 제목을 말하세요") {
        speak(msg)
        showOverlay(msg)
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, 10_000)
    }

    fun speak(msg: String) {
        if (ttsReady) runCatching { tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "sealion") }
    }

    private fun showOverlay(text: String) {
        if (!canOverlay) return   // 권한 없으면 소리만
        hideOverlay()
        // BYD 볼륨 조절 팝업이 우리 음성 UI 를 가리므로, 화면 전체를 디밍하고 상단에 크게 안내한다.
        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(0xCC000000.toInt())        // 전체 화면 디밍(반투명 검정)
        }
        val tv = TextView(context).apply {
            this.text = "🎤 $text"
            setTextColor(Color.WHITE)
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding(48, 80, 48, 48)
        }
        root.addView(
            tv,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL },
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 디밍은 시각만 — 터치는 통과시켜 볼륨 팝업/앱 조작을 막지 않는다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(root, lp); overlay = root }
    }

    fun hide() {
        main.removeCallbacks(autoHide)
        hideOverlay()
    }

    private fun hideOverlay() {
        overlay?.let { v -> runCatching { wm.removeView(v) } }
        overlay = null
    }

    fun release() {
        hide()
        runCatching { tts?.shutdown() }
        tts = null
    }
}

package com.cseini.byd.karaoke

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.RecordingStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.youtube.YouTubeRepository
import com.cseini.byd.karaoke.update.UpdateManager
import com.cseini.byd.karaoke.voice.VoiceSearch
import kotlinx.coroutines.launch

/** 검색 홈. 타이핑/음성 검색 → 바로 부르기. */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_HINT = "검색어를 입력하거나 음성 버튼을 누르세요."
    }

    private lateinit var settings: SettingsStore
    private lateinit var recordings: RecordingStore
    private lateinit var repo: YouTubeRepository
    private lateinit var voice: VoiceSearch

    private lateinit var searchInput: EditText
    private var searchJob: kotlinx.coroutines.Job? = null
    private val searchDebounce = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private lateinit var status: TextView
    private lateinit var results: RecyclerView
    private lateinit var historySection: View
    private lateinit var historyEmpty: TextView
    private lateinit var voiceOverlay: View
    private lateinit var voiceIcon: TextView
    private lateinit var voiceText: TextView
    private lateinit var voiceSub: TextView
    private lateinit var autoplayCheck: CheckBox
    private lateinit var autoplayOverlay: View
    private lateinit var autoplayTitle: TextView
    private lateinit var autoplayCount: TextView
    private lateinit var autoplayLeft: View
    private lateinit var autoplayRight: View
    private var lastResults: List<QueueItem> = emptyList()
    private var pendingAutoPlay = false          // 음성 검색 결과가 오면 첫 곡 자동재생 대기
    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null
    private val adapter = ResultAdapter(
        onPlayNow = { playFromResults(it) },
    )
    private val historyAdapter = HistoryAdapter(
        onPlay = { playNow(QueueItem(it.videoId, it.title)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        recordings = RecordingStore(this)
        repo = YouTubeRepository()
        voice = VoiceSearch(this, settings)

        searchInput = findViewById(R.id.search_input)
        status = findViewById(R.id.status)
        historySection = findViewById(R.id.history_section)
        historyEmpty = findViewById(R.id.history_empty)
        voiceOverlay = findViewById(R.id.voice_overlay)
        voiceIcon = findViewById(R.id.voice_icon)
        voiceText = findViewById(R.id.voice_text)
        voiceSub = findViewById(R.id.voice_sub)
        autoplayCheck = findViewById(R.id.chk_autoplay)
        autoplayCheck.isChecked = settings.autoPlayVoiceFirst
        autoplayCheck.setOnCheckedChangeListener { _, checked -> settings.autoPlayVoiceFirst = checked }
        autoplayOverlay = findViewById(R.id.autoplay_overlay)
        autoplayTitle = findViewById(R.id.autoplay_title)
        autoplayCount = findViewById(R.id.autoplay_count)
        autoplayLeft = findViewById(R.id.autoplay_left)
        autoplayRight = findViewById(R.id.autoplay_right)
        // 좌측 = 취소하고 다시 음성검색 / 우측 = 취소만
        autoplayLeft.setOnClickListener { cancelAutoPlay(); startVoice() }
        autoplayRight.setOnClickListener { cancelAutoPlay(); status.text = "자동 재생을 취소했습니다." }
        voiceOverlay.setOnClickListener { hideVoiceOverlay() }

        results = findViewById(R.id.results)
        results.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<RecyclerView>(R.id.history).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = this@MainActivity.historyAdapter
        }

        findViewById<Button>(R.id.btn_search).setOnClickListener { doSearch() }
        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoice() }
        NavBar.wire(this, MainActivity::class.java)

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        // 타이핑마다 자동 검색(디바운스) — 비슷한 곡이 바로 위에 뜨도록.
        searchInput.doAfterTextChanged { text ->
            cancelAutoPlay()   // 사용자가 직접 타이핑하면 자동재생 취소
            pendingSearch?.let { searchDebounce.removeCallbacks(it) }
            val q = text?.toString()?.trim().orEmpty()
            if (q.length >= 2) {
                pendingSearch = Runnable { doSearch() }.also { searchDebounce.postDelayed(it, 450) }
            }
        }

        ensureMicPermission()
        if (!settings.keylessSearch && !settings.hasApiKey()) {
            status.text = "먼저 [설정]에서 API 키를 입력하거나 '키 없이 검색'을 켜세요."
        }
        checkOtaUpdate()
    }

    /** GitHub Releases 에 새 버전이 있으면 내려받아 설치 화면을 띄운다. */
    private fun checkOtaUpdate() {
        lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate() ?: return@launch
            toast("새 버전 v${release.version} 다운로드 중…")
            val apk = UpdateManager.download(this@MainActivity, release) { p ->
                runOnUiThread { status.text = "업데이트 다운로드 중… $p%" }
            }
            if (apk == null) {
                status.text = "업데이트 다운로드 실패 — 네트워크 확인 후 앱을 다시 시작하세요"
                return@launch
            }
            status.text = "v${release.version} 설치를 진행하세요"
            UpdateManager.install(this@MainActivity, apk)
        }
    }

    // 재생 화면에서 점수 탭 → 검색 홈으로 돌아오면 히스토리 화면을 보여준다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        cancelAutoPlay()
        searchInput.setText("")
        status.text = DEFAULT_HINT
        showHistory()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        // 히스토리(초기 화면)를 보고 있으면 이전 검색/카운트다운 안내 잔상은 지운다.
        if (results.visibility != View.VISIBLE) status.text = DEFAULT_HINT
        // 음성 버튼·즉시재생 옵션은 Gemini 키가 있을 때만 노출
        val voiceOn = settings.openaiApiKey.isNotBlank()
        findViewById<Button>(R.id.btn_voice).visibility = if (voiceOn) View.VISIBLE else View.GONE
        autoplayCheck.visibility = if (voiceOn) View.VISIBLE else View.GONE
    }

    /** 같은 곡은 가장 최근 것만, 최신순으로 히스토리 타일에 노출. */
    private fun refreshHistory() {
        recordings.reload()   // 재생 화면에서 방금 저장된 녹음까지 반영
        val recent = recordings.all().distinctBy { it.videoId }
        historyAdapter.submit(recent)
        historyEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showHistory() {
        results.visibility = View.GONE
        historySection.visibility = View.VISIBLE
    }

    private fun showResults() {
        historySection.visibility = View.GONE
        results.visibility = View.VISIBLE
    }

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) { status.text = "검색어를 입력하세요."; return }
        val autoPlay = pendingAutoPlay   // 이번 검색이 음성 트리거였는지 확정
        pendingAutoPlay = false
        cancelAutoPlay()                 // 진행 중인 카운트다운은 중단
        status.text = "검색 중…"
        searchJob?.cancel()   // 실시간 타이핑 중 이전 검색은 취소
        searchJob = lifecycleScope.launch {
            when (val r = repo.search(q, settings.youtubeApiKey, System.currentTimeMillis(), settings.keylessSearch)) {
                is YouTubeRepository.Result.Ok -> {
                    lastResults = r.items
                    adapter.submit(r.items)
                    showResults()
                    status.text = if (r.items.isEmpty()) "결과가 없습니다." else "결과 ${r.items.size}개"
                    if (autoPlay && settings.autoPlayVoiceFirst && r.items.isNotEmpty()) {
                        startAutoPlayCountdown(r.items.first())
                    }
                }
                is YouTubeRepository.Result.Error -> status.text = r.message
            }
        }
    }

    /** 음성 검색 결과 첫 곡을 3초 카운트 후 자동 재생. 화면을 탭하거나 다른 조작을 하면 취소. */
    private fun startAutoPlayCountdown(item: QueueItem) {
        cancelAutoPlay()
        autoplayTitle.text = item.title
        autoplayOverlay.visibility = View.VISIBLE
        val r = object : Runnable {
            var n = 5
            override fun run() {
                if (n <= 0) {
                    autoPlayRunnable = null
                    hideAutoplayOverlay()
                    playFromResults(item)
                    return
                }
                autoplayCount.text = "$n"
                pulseAutoplayCount()
                n--
                autoPlayHandler.postDelayed(this, 1000)
            }
        }
        autoPlayRunnable = r
        autoPlayHandler.post(r)
    }

    private fun pulseAutoplayCount() {
        autoplayCount.scaleX = 1.4f; autoplayCount.scaleY = 1.4f
        autoplayCount.animate().scaleX(1f).scaleY(1f).setDuration(400).start()
    }

    private fun hideAutoplayOverlay() {
        autoplayCount.animate().cancel()
        autoplayOverlay.visibility = View.GONE
    }

    private fun cancelAutoPlay() {
        autoPlayRunnable?.let { autoPlayHandler.removeCallbacks(it) }
        autoPlayRunnable = null
        pendingAutoPlay = false
        if (::autoplayOverlay.isInitialized) hideAutoplayOverlay()
    }

    private fun startVoice() {
        cancelAutoPlay()
        if (!voice.isAvailable()) {
            toast("이 기기는 음성 인식을 지원하지 않습니다. 타이핑으로 검색하세요.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ensureMicPermission(); toast("마이크 권한이 필요합니다"); return
        }
        showVoiceOverlay("🎙", "준비 중…", "잠깐만요")
        voice.start(
            onReady = {
                beepStart()
                showVoiceOverlay("🎙", "말씀하세요", "노래 제목이나 가수를 말하면 검색해요")
                pulseVoiceIcon()
            },
            onProcessing = {
                beepEnd()
                showVoiceOverlay("🌀", "인식 중…", "잠시만 기다려주세요")
            },
            onResult = { text ->
                hideVoiceOverlay()
                searchInput.setText(text)
                pendingSearch?.let { searchDebounce.removeCallbacks(it) }  // 타이핑 디바운스 중복 검색 방지
                pendingAutoPlay = true   // 즉시재생 옵션이 켜져 있으면 이 검색 뒤 첫 곡 자동재생
                doSearch()
            },
            onError = {
                showVoiceOverlay("⚠️", "다시 시도해주세요", it)
                voiceOverlay.postDelayed({ hideVoiceOverlay() }, 1800)
                status.text = it
            },
        )
    }

    // ── 음성 인식 플로팅 오버레이 + 효과음 ─────────────────────────────

    private fun showVoiceOverlay(icon: String, text: String, sub: String) {
        voiceIcon.text = icon
        voiceText.text = text
        voiceSub.text = sub
        voiceOverlay.visibility = View.VISIBLE
    }

    private fun hideVoiceOverlay() {
        voiceIcon.clearAnimation()
        voiceOverlay.visibility = View.GONE
    }

    private fun pulseVoiceIcon() {
        voiceIcon.animate().scaleX(1.15f).scaleY(1.15f).setDuration(500).withEndAction {
            voiceIcon.animate().scaleX(1f).scaleY(1f).setDuration(500).withEndAction {
                if (voiceOverlay.visibility == View.VISIBLE) pulseVoiceIcon()
            }.start()
        }.start()
    }

    private fun beepStart() = runCatching {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90)
            .also { it.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 160) }
            .let { tg -> voiceOverlay.postDelayed({ tg.release() }, 400) }
    }

    private fun beepEnd() = runCatching {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90)
            .also { it.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 220) }
            .let { tg -> voiceOverlay.postDelayed({ tg.release() }, 500) }
    }

    private fun playNow(item: QueueItem) {
        cancelAutoPlay()
        startActivity(PlaybackActivity.intent(this, item.videoId, item.title, fromQueue = false))
    }

    /** 검색 결과에서 부르기: 재생 불가 영상이면 뒤 후보로 자동으로 넘어가도록 목록을 함께 넘긴다. */
    private fun playFromResults(item: QueueItem) {
        cancelAutoPlay()
        val idx = lastResults.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
        startActivity(PlaybackActivity.intentWithCandidates(this, lastResults, idx))
    }

    private fun ensureMicPermission() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            // SD카드 저장(Legacy)용 쓰기 권한
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
    }

    /**
     * 마이크 버튼이 미디어키/헤드셋훅을 보내는 기기라면 그 즉시 음성검색.
     * (볼륨 버튼은 차량 시스템이 볼륨 패널로 가로채 앱까지 오지 않으므로 다루지 않는다.)
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_VOICE_ASSIST -> {
                    if (settings.openaiApiKey.isNotBlank()) {
                        cancelAutoPlay(); startVoice(); return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        cancelAutoPlay()   // 화면을 떠나면(네비바 등) 자동재생 취소
    }

    override fun onDestroy() {
        cancelAutoPlay()
        voice.stop()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

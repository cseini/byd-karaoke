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
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.data.RecordingStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.youtube.YouTubeRepository
import com.cseini.byd.karaoke.update.UpdateManager
import com.cseini.byd.karaoke.voice.VoiceSearch
import kotlinx.coroutines.launch

/** 검색 홈. 타이핑/음성 검색 → 바로 부르기. */
@UnstableApi
class MainActivity : AppCompatActivity(), ScreenHost {

    companion object {
        private const val DEFAULT_HINT = "검색어를 입력하거나 음성 버튼을 누르세요."
    }

    // 테스트(lab) 앱에서만: 재생·녹음함/랭킹/설정을 이 화면 안에서(임베드) 처리해 분할화면을 유지.
    private var embeddedPlayer: EmbeddedPlayer? = null
    // 분할화면 유지용 임베드 재생·화면은 이제 전 플래버 공통(라이브 승격).
    private val useEmbedded: Boolean get() = true
    private lateinit var embedScreen: android.widget.FrameLayout
    private var screenCleanup: (() -> Unit)? = null

    // (테스트) USB 마이크 버튼 HID 직접 읽기 — 대상 버튼 길게 누르면 음성검색.
    private var usbMic: UsbMicButtons? = null
    private var keyCatcherWarned = false   // 접근성 미활성 안내는 실행당 1회만

    // ── ScreenHost: 임베드 화면(녹음함/랭킹/설정)이 콜백하는 호스트 ──
    override val embedded: Boolean get() = useEmbedded
    override fun onScreenBack() = closeScreen()
    /** 임베드 화면의 하단바 → 다른 임베드 화면으로 전환(분할화면 유지). */
    override fun onNavigate(target: String) {
        if (target == "search") closeScreen() else showScreen(target)
    }

    /**
     * 마이크 버튼(길게 누름) → 앱 제어.
     * 마이크=음성검색 / 볼륨↑=다음 예약곡 / 볼륨↓=노래 종료(채점).
     * 재생 중이 아니면 다음곡·종료는 무시(짧게 누름의 네이티브 볼륨 동작은 그대로).
     */
    private fun onMicButton(action: UsbMicButtons.Action) {
        when (action) {
            UsbMicButtons.Action.VOICE -> { cancelAutoPlay(); startVoice() }
            UsbMicButtons.Action.NEXT -> embeddedPlayer?.remoteNext()
            UsbMicButtons.Action.STOP -> embeddedPlayer?.remoteStop()
        }
    }

    /** 마이크 버튼 진단: 옵션을 안 켠 상태여도 임시 인스턴스로 신호를 읽어 보여준다. */
    private var diagTemp = false
    override fun onMicDiagStart(onLine: (String) -> Unit) {
        if (usbMic == null) {
            usbMic = UsbMicButtons(this) { action -> onMicButton(action) }
            diagTemp = true
        }
        usbMic?.onRaw = onLine
        usbMic?.start()
    }

    override fun onMicDiagStop() {
        usbMic?.onRaw = null
        if (diagTemp) { usbMic?.stop(); usbMic = null; diagTemp = false }
    }

    /** 설정 저장 즉시 반영 — 화면을 닫지 않아도 음성 버튼·물리버튼이 바로 적용된다. */
    override fun onSettingsSaved() {
        if (::autoplayCheck.isInitialized) refreshVoiceUi()
        syncPhysicalButtons()
    }
    override fun onReplayRecording(item: com.cseini.byd.karaoke.data.RecordingItem) {
        embeddedPlayer?.let { closeScreen(); it.replayRecording(item) }
            ?: startActivity(PlaybackActivity.replayIntent(this, item))
    }

    private val isScreenShowing: Boolean
        get() = ::embedScreen.isInitialized && embedScreen.visibility == View.VISIBLE

    /** 임베드 화면 띄우기(재생 중이면 먼저 닫는다). */
    private fun showScreen(which: String) {
        cancelAutoPlay()
        if (embeddedPlayer?.isShowing == true) embeddedPlayer?.close()
        closeScreen()
        val layout = when (which) {
            "recordings" -> R.layout.activity_recordings
            "ranking" -> R.layout.activity_ranking
            else -> R.layout.activity_settings
        }
        val v = layoutInflater.inflate(layout, embedScreen, false)
        embedScreen.addView(v)
        when (which) {
            "recordings" -> RecordingsScreen(v, this).also { it.refresh(); screenCleanup = { it.destroy() } }
            "ranking" -> RankingScreen(v, this).refresh()
            "settings" -> SettingsScreen(v, this)
        }
        embedScreen.visibility = View.VISIBLE
    }

    private fun closeScreen() {
        screenCleanup?.invoke(); screenCleanup = null
        if (::embedScreen.isInitialized) {
            embedScreen.removeAllViews()
            embedScreen.visibility = View.GONE
        }
        // 설정에서 바꾼 값(물리버튼·API 키 등)을 닫는 즉시 화면에 반영한다.
        syncPhysicalButtons()
        if (::autoplayCheck.isInitialized) refreshVoiceUi()
    }

    private lateinit var settings: SettingsStore
    private lateinit var queue: QueueStore
    private lateinit var recordings: RecordingStore
    private lateinit var playHistory: com.cseini.byd.karaoke.data.PlayHistoryStore
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
    private lateinit var voiceLevel: android.widget.ProgressBar
    private lateinit var voiceLevelHint: TextView
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
        onReserve = { reserve(it) },
        onPlayNow = { playFromResults(it) },
    )
    private val historyAdapter = HistoryAdapter(
        onPlay = { playNow(QueueItem(it.videoId, it.title)) },
    )

    // 홈 화면 예약 목록(폰 리모컨으로 넣은 곡이 바로 보이게)
    private lateinit var homeQueueSection: View
    private lateinit var homeQueueTitle: TextView
    private val homeQueueAdapter = HomeQueueAdapter(
        onPlay = { queue.removeByVideoId(it.videoId); playNow(it) },
        onDelete = { queue.removeByVideoId(it.videoId); refreshHomeQueue() },
    )
    private val queuePoll = object : Runnable {
        override fun run() {
            refreshHomeQueue()
            searchDebounce.postDelayed(this, 2500)
        }
    }

    /** 예약 목록 갱신 — 서버(폰)가 SharedPreferences 에 넣으므로 reload 후 읽는다. */
    private fun refreshHomeQueue() {
        if (!::homeQueueSection.isInitialized) return
        queue.reload()
        val items = queue.all()
        homeQueueAdapter.submit(items)
        homeQueueTitle.text = "🎫 예약된 곡 ${items.size}"
        homeQueueSection.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(this)
        queue = QueueStore(this)
        recordings = RecordingStore(this)
        playHistory = com.cseini.byd.karaoke.data.PlayHistoryStore(this)
        // 기존 사용자: 재생 기록이 비어 있으면 기존 녹음 목록에서 최근 부른 노래를 1회 이관.
        playHistory.seedIfEmpty(
            recordings.all().distinctBy { it.videoId }
                .map { com.cseini.byd.karaoke.data.PlayHistoryItem(it.videoId, it.title, it.at, it.score) }
        )
        repo = YouTubeRepository()
        voice = VoiceSearch(this, settings)
        if (useEmbedded) embeddedPlayer = EmbeddedPlayer(this, settings, recordings, playHistory) { resetToSearchHome() }

        searchInput = findViewById(R.id.search_input)
        status = findViewById(R.id.status)
        historySection = findViewById(R.id.history_section)
        historyEmpty = findViewById(R.id.history_empty)
        voiceOverlay = findViewById(R.id.voice_overlay)
        voiceIcon = findViewById(R.id.voice_icon)
        voiceText = findViewById(R.id.voice_text)
        voiceSub = findViewById(R.id.voice_sub)
        voiceLevel = findViewById(R.id.voice_level)
        voiceLevelHint = findViewById(R.id.voice_level_hint)
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
        voiceOverlay.setOnClickListener { cancelVoice() }

        results = findViewById(R.id.results)
        results.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<RecyclerView>(R.id.history).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = this@MainActivity.historyAdapter
        }
        homeQueueSection = findViewById(R.id.home_queue_section)
        homeQueueTitle = findViewById(R.id.home_queue_title)
        findViewById<RecyclerView>(R.id.home_queue).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.homeQueueAdapter
        }

        findViewById<Button>(R.id.btn_search).setOnClickListener { hideKeyboard(); doSearch() }
        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoice() }
        findViewById<Button>(R.id.btn_reserve_server).setOnClickListener { showReserveServer() }
        NavBar.wire(this, MainActivity::class.java)
        // 테스트 앱: 네비바(녹음함/랭킹/설정)를 Activity 대신 화면 안 오버레이로 → 분할화면 유지.
        embedScreen = findViewById(R.id.embed_screen)
        if (useEmbedded) {
            findViewById<Button>(R.id.nav_recordings).setOnClickListener { showScreen("recordings") }
            findViewById<Button>(R.id.nav_ranking).setOnClickListener { showScreen("ranking") }
            findViewById<Button>(R.id.nav_settings).setOnClickListener { showScreen("settings") }
        }

        val btnClear = findViewById<Button>(R.id.btn_clear)
        btnClear.setOnClickListener {
            searchInput.setText("")
            status.text = DEFAULT_HINT
            showHistory()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); doSearch(); true } else false
        }
        // 타이핑마다 자동 검색(디바운스) — 비슷한 곡이 바로 위에 뜨도록.
        searchInput.doAfterTextChanged { text ->
            cancelAutoPlay()   // 사용자가 직접 타이핑하면 자동재생 취소
            val q = text?.toString()?.trim().orEmpty()
            btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            pendingSearch?.let { searchDebounce.removeCallbacks(it) }
            if (q.length >= 2) {
                pendingSearch = Runnable { doSearch() }.also { searchDebounce.postDelayed(it, 450) }
            }
        }

        ensureMicPermission()
        if (!settings.keylessSearch && !settings.hasApiKey()) {
            status.text = "먼저 [설정]에서 API 키를 입력하거나 '키 없이 검색'을 켜세요."
        }
        // 앱 시작 시 새 버전이 있으면 토스트로 알림만(자동 설치 안 함 — 설정에서 수동 설치).
        checkOtaUpdate()

        // USB 마이크·휠 버튼 제어는 설정 옵션(기본 꺼짐). 실제 시작/중지는 onWindowFocusChanged 에서
        // 설정값에 따라 처리(권한 다이얼로그가 포커스 전이로 닫히는 문제 + 옵트인 즉시 반영).

        // 접근성(마이크 버튼)에서 넘어온 음성검색 요청(콜드 스타트)
        if (intent?.action == KeyCatcherService.ACTION_VOICE) {
            searchInput.post { startVoice() }
        }
    }

    /** 접근성 서비스(휠 버튼 감지)가 실제로 켜져 있는지. */
    private fun isKeyCatcherEnabled(): Boolean {
        val comp = android.content.ComponentName(this, KeyCatcherService::class.java).flattenToString()
        val cur = runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")
        return cur.split(':').any { it.equals(comp, ignoreCase = true) }
    }

    /** 키 감지 접근성 서비스를 자동 활성화 시도. 권한·결과를 토스트로 눈에 보이게 알린다. */
    private fun enableKeyCatcher() {
        val comp = android.content.ComponentName(this, KeyCatcherService::class.java).flattenToString()
        val hasPerm = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            // 이 유닛엔 권한이 없어 자동설정 불가 — 접근성은 사용자가 켜야 한다.
            // 설정을 켰는데 서비스가 꺼져 있으면 조용히 실패하지 말고 알려준다(원인 파악 불가 방지).
            android.util.Log.i("karaoke-keys", "WRITE_SECURE_SETTINGS 없음 — 수동 활성 필요: $comp")
            if (!isKeyCatcherEnabled() && !keyCatcherWarned) {
                keyCatcherWarned = true
                toast("휠 버튼을 쓰려면 '접근성'에서 노래방 키 감지를 켜야 합니다")
            }
            return
        }
        val cur = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (comp in cur.split(':')) return   // 이미 켜짐
        runCatching {
            android.provider.Settings.Secure.putString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (cur.isBlank()) comp else "$cur:$comp"
            )
            android.provider.Settings.Secure.putInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
        }.onFailure { android.util.Log.i("karaoke-keys", "접근성 켜기 실패: ${it.message}") }
    }

    /** 앱 시작 시 새 버전이 있으면 토스트로 알려준다(다운로드·설치는 설정에서 수동). */
    private fun checkOtaUpdate() {
        lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate() ?: return@launch
            toast("🔔 새 버전 v${release.version} 있음 — 설정 › 업데이트 확인에서 받으세요")
        }
    }

    // 재생 화면에서 점수 탭 → 검색 홈으로 돌아오면 히스토리 화면을 보여준다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 마이크 버튼(접근성)이 부른 음성검색 — 이미 실행 중일 때.
        if (intent.action == KeyCatcherService.ACTION_VOICE) {
            cancelAutoPlay()
            searchInput.post { startVoice() }
            return
        }
        resetToSearchHome()
    }

    override fun onResume() {
        super.onResume()
        refreshHistory()
        searchDebounce.removeCallbacks(queuePoll)
        searchDebounce.post(queuePoll)   // 폰 예약이 바로 보이도록 주기 갱신
        // 히스토리(초기 화면)를 보고 있으면 이전 검색/카운트다운 안내 잔상은 지운다.
        if (results.visibility != View.VISIBLE) status.text = DEFAULT_HINT
        refreshVoiceUi()
    }

    /**
     * 음성 버튼·즉시재생 옵션은 Gemini 키가 있을 때만 노출.
     * 설정 화면이 같은 창의 오버레이라 닫아도 onResume 이 안 불리므로,
     * 설정을 닫을 때도 호출해야 '키를 넣고 저장했는데 버튼이 안 나오는' 문제가 없다.
     */
    private fun refreshVoiceUi() {
        val voiceOn = settings.geminiApiKeys().isNotEmpty()
        findViewById<Button>(R.id.btn_voice).visibility = if (voiceOn) View.VISIBLE else View.GONE
        autoplayCheck.visibility = if (voiceOn) View.VISIBLE else View.GONE
    }

    /** 최근 부른 노래(재생 기록 기반, 녹음과 분리). 녹음을 꺼도·지워도 남는다. */
    private fun refreshHistory() {
        playHistory.reload()
        val recent = playHistory.all()
        historyAdapter.submit(recent)
        historyEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showHistory() {
        results.visibility = View.GONE
        historySection.visibility = View.VISIBLE
    }

    /** 재생/채점 후 검색 홈으로 복귀: 검색어 초기화 + 최근 부른 노래 + 키보드 내림. */
    private fun resetToSearchHome() {
        cancelAutoPlay()
        searchInput.setText("")
        status.text = DEFAULT_HINT
        refreshHistory()
        showHistory()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
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

    /** 예약 서버를 켜고 접속 QR을 띄운다(끄기 버튼 포함). 예약 목록 관리는 재생 화면에서. */
    private fun showReserveServer() {
        val url = com.cseini.byd.karaoke.share.ReserveServer.start(this)
        if (url == null) {
            toast("네트워크에 연결돼 있지 않습니다. 폰 핫스팟에 차를 연결하세요.")
            return
        }
        val view = layoutInflater.inflate(R.layout.dialog_reserve, null)
        // 유닛마다 인터페이스가 달라 자동 선택이 틀릴 수 있어, 주소를 눌러 다른 후보로 바꿀 수 있게 한다.
        com.cseini.byd.karaoke.share.QrSwitcher.bind(
            view.findViewById(R.id.reserve_qr),
            view.findViewById(R.id.reserve_url),
            view.findViewById(R.id.reserve_hint),
            com.cseini.byd.karaoke.share.QrSwitcher.portOf(url, 8080),
        )
        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("닫기", null)
            .setNegativeButton("예약 서버 끄기") { _, _ ->
                com.cseini.byd.karaoke.share.ReserveServer.stop()
                toast("예약 서버를 껐습니다")
            }
            .show()
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
                voiceLevel.visibility = View.VISIBLE
                voiceLevelHint.visibility = View.VISIBLE
                pulseVoiceIcon()
            },
            onProcessing = {
                beepEnd()
                voiceLevel.visibility = View.GONE
                voiceLevelHint.visibility = View.GONE
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
                // 오류는 읽을 시간을 준다(예전엔 1.8초 만에 사라져 원인을 못 봤다). 탭하면 즉시 닫힘.
                showVoiceOverlay("⚠️", "음성 검색 실패", "$it\n\n(화면을 누르면 닫힙니다)")
                voiceOverlay.postDelayed({ hideVoiceOverlay() }, 6000)
                status.text = it.lineSequence().firstOrNull().orEmpty()
            },
            onLevel = { db -> updateVoiceLevel(db) },
        )
    }

    // ── 음성 인식 플로팅 오버레이 + 효과음 ─────────────────────────────

    private fun showVoiceOverlay(icon: String, text: String, sub: String) {
        voiceIcon.text = icon
        voiceText.text = text
        voiceSub.text = sub
        voiceOverlay.visibility = View.VISIBLE
    }

    /** 음성 오버레이 탭 = 취소: UI뿐 아니라 녹음·전사·자동재생 파이프라인까지 전부 중단. */
    private fun cancelVoice() {
        voice.stop()
        hideVoiceOverlay()
        cancelAutoPlay()
        status.text = DEFAULT_HINT
    }

    private fun hideVoiceOverlay() {
        voiceIcon.clearAnimation()
        voiceLevel.visibility = View.GONE
        voiceLevelHint.visibility = View.GONE
        voiceOverlay.visibility = View.GONE
    }

    /** 마이크 입력 레벨(dBFS)을 미터에 반영 — 소리가 들어오는지 눈으로 확인. */
    private fun updateVoiceLevel(db: Float) {
        if (voiceOverlay.visibility != View.VISIBLE) return
        val level = (((db + 45f) / 45f) * 100f).toInt().coerceIn(0, 100)
        voiceLevel.progress = level
        voiceLevelHint.text = if (level > 25) "🔊 잘 들려요" else "🎤 마이크에 대고 말해보세요"
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

    private fun reserve(item: QueueItem) {
        hideKeyboard()
        queue.add(item)
        toast("🎫 예약: ${item.title}")
    }

    private fun playNow(item: QueueItem) {
        hideKeyboard()
        cancelAutoPlay()
        embeddedPlayer?.let { it.play(item.videoId, item.title); return }  // 테스트 앱: 화면 안에서 재생
        startActivity(PlaybackActivity.intent(this, item.videoId, item.title, fromQueue = false))
    }

    /** 검색 결과에서 부르기: 재생 불가 영상이면 뒤 후보로 자동으로 넘어가도록 목록을 함께 넘긴다. */
    private fun playFromResults(item: QueueItem) {
        hideKeyboard()
        cancelAutoPlay()
        embeddedPlayer?.let { it.play(item.videoId, item.title); return }  // 테스트 앱: 화면 안에서 재생
        val idx = lastResults.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
        startActivity(PlaybackActivity.intentWithCandidates(this, lastResults, idx))
    }

    override fun onBackPressed() {
        when {
            isScreenShowing -> closeScreen()
            embeddedPlayer?.isShowing == true -> embeddedPlayer?.close()
            else -> super.onBackPressed()
        }
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
                    if (settings.geminiApiKeys().isNotEmpty()) {
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
        searchDebounce.removeCallbacks(queuePoll)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) syncPhysicalButtons()
    }

    /**
     * 설정(옵트인)에 맞춰 USB 마이크·휠 버튼 제어를 켜거나 끈다. 여러 번 불러도 안전(멱등).
     * 설정 화면이 같은 창의 오버레이라 닫아도 포커스 변화가 없으므로, 설정을 닫을 때도 호출해야
     * '저장했는데 아무 일도 안 일어나는' 문제가 생기지 않는다.
     */
    private fun syncPhysicalButtons() {
        if (settings.micButtonControl) {
            if (usbMic == null) usbMic = UsbMicButtons(this) { action -> onMicButton(action) }
            usbMic?.start()
        } else {
            usbMic?.stop(); usbMic = null
        }
        // 휠 버튼(접근성): 켜져 있으면 활성 시도 — 못 켜면 enableKeyCatcher 가 안내한다.
        if (settings.wheelButtonControl) enableKeyCatcher()
    }

    override fun onDestroy() {
        cancelAutoPlay()
        voice.stop()
        usbMic?.stop()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

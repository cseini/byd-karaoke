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
import com.cseini.byd.karaoke.data.youtube.YouTubeScraper
import com.cseini.byd.karaoke.update.UpdateManager
import com.cseini.byd.karaoke.voice.VoiceSearch
import kotlinx.coroutines.launch

/** 검색 홈. 타이핑/음성 검색 → 바로 부르기. */
@UnstableApi
class MainActivity : AppCompatActivity(), ScreenHost {

    companion object {
        private const val DEFAULT_HINT = ""
    }

    // 재생·녹음함/랭킹/설정을 모두 이 화면 안에서(임베드) 처리해 차량 런처의 분할화면을 유지한다.
    private var embeddedPlayer: EmbeddedPlayer? = null
    private lateinit var embedScreen: android.widget.FrameLayout
    private var screenCleanup: (() -> Unit)? = null

    // (테스트) USB 마이크 버튼 HID 직접 읽기 — 대상 버튼 길게 누르면 음성검색.
    private var usbMic: UsbMicButtons? = null
    private var keyCatcherWarned = false   // 접근성 미활성 안내는 실행당 1회만

    // ── ScreenHost: 임베드 화면(녹음함/랭킹/설정)이 콜백하는 호스트 ──
    override fun onScreenBack() = closeScreen()
    /** 임베드 화면의 하단바 → 다른 임베드 화면으로 전환(분할화면 유지). */
    override fun onNavigate(target: String) {
        if (target == "search") closeScreen() else showScreen(target)
    }

    /** 마이크 버튼 제스처 → 설정에서 매핑된 기능 실행. */
    private fun onMicButton(event: UsbMicButtons.Event) {
        val fn = when (event) {
            UsbMicButtons.Event.MIC_LONG -> settings.mapMicLong
            UsbMicButtons.Event.MIC_DOUBLE -> settings.mapMicDouble
            UsbMicButtons.Event.VOL_UP_DOUBLE -> settings.mapVolUpDouble
            UsbMicButtons.Event.VOL_DOWN_DOUBLE -> settings.mapVolDownDouble
        }
        runMappedFunction(fn)
    }

    private fun runMappedFunction(fn: String) {
        when (fn) {
            // 음성검색: 노래 재생 중만 제외하고 어디서든(검색·채점·녹음함…) 동작
            "voice" -> if (embeddedPlayer?.isPlayingSong != true) { cancelAutoPlay(); startVoice() }
            // 뒤로: 채점 화면 → 검색(재생 중엔 무시), 그 외엔 열린 화면 닫기
            "back" -> if (embeddedPlayer?.remoteBack() != true && isScreenShowing) closeScreen()
            "mute" -> embeddedPlayer?.remoteMuteToggle()
            "next" -> embeddedPlayer?.remoteNext()
            "stop" -> embeddedPlayer?.remoteStop()
            "pause" -> embeddedPlayer?.remotePauseToggle()
            "panel" -> usbMic?.sendPanelToggle()
            // "none" → 아무것도 안 함
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

    /** 마이크 버튼 학습: 진단과 같은 방식으로 리더를 빌려 쓰되 캡처 콜백만 단다. */
    override fun onMicLearnStart(onCapture: (Int, Int, String) -> Unit) {
        if (usbMic == null) {
            usbMic = UsbMicButtons(this) { action -> onMicButton(action) }
            diagTemp = true
        }
        usbMic?.onCapture = onCapture
        usbMic?.start()
    }

    override fun onMicLearnStop() {
        usbMic?.onCapture = null
        // 학습된 코드를 다시 읽도록 리더를 재시작(임시 인스턴스면 정리만)
        usbMic?.stop()
        if (diagTemp) { usbMic = null; diagTemp = false }
        syncPhysicalButtons()
    }

    /** 설정 저장 즉시 반영 — 화면을 닫지 않아도 음성 버튼·물리버튼이 바로 적용된다. */
    override fun onSettingsSaved() {
        if (::settings.isInitialized) refreshVoiceUi()
        syncPhysicalButtons()
    }
    override fun onReplayRecording(item: com.cseini.byd.karaoke.data.RecordingItem) {
        embeddedPlayer?.let { closeScreen(); it.replayRecording(item) }
    }

    private val isScreenShowing: Boolean
        get() = ::embedScreen.isInitialized && embedScreen.visibility == View.VISIBLE

    /** 임베드 화면 띄우기(재생 중이면 먼저 닫는다). */
    private fun showScreen(which: String) {
        cancelAutoPlay()
        if (embeddedPlayer?.isShowing == true) embeddedPlayer?.close()
        closeScreen()
        // 화면 생성 실패 시 앱이 죽는 대신 에러 전문을 보여준다(원인 파악·제보용).
        runCatching {
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
        }.onFailure { e ->
            closeScreen()
            val trace = e.stackTraceToString().take(4000)
            val tv = android.widget.TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(24, 16, 24, 16)
                text = trace
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚠ 화면 열기 실패 — 이 내용을 복사해 제보해주세요")
                .setView(android.widget.ScrollView(this).apply { addView(tv) })
                .setPositiveButton("복사") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
                    toast("복사되었습니다")
                }
                .setNegativeButton("닫기", null)
                .show()
        }
    }

    private fun closeScreen() {
        screenCleanup?.invoke(); screenCleanup = null
        if (::embedScreen.isInitialized) {
            embedScreen.removeAllViews()
            embedScreen.visibility = View.GONE
        }
        // 설정에서 바꾼 값(물리버튼·API 키 등)을 닫는 즉시 화면에 반영한다.
        syncPhysicalButtons()
        if (::settings.isInitialized) refreshVoiceUi()
    }

    private lateinit var settings: SettingsStore
    private lateinit var queue: QueueStore
    private lateinit var recordings: RecordingStore
    private lateinit var playHistory: com.cseini.byd.karaoke.data.PlayHistoryStore
    private lateinit var repo: YouTubeRepository
    private lateinit var voice: VoiceSearch

    private lateinit var searchInput: EditText
    private var searchJob: kotlinx.coroutines.Job? = null
    private var inFlightQuery: String? = null   // 진행 중인 검색어(같은 검색 중복 재시작 방지)
    private var autoPlayAfterSearch = false    // 이번 검색 결과 첫 곡을 자동 재생할지(음성 트리거)
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
    private lateinit var autoplayOverlay: View
    private lateinit var autoplayTitle: TextView
    private lateinit var autoplayCount: TextView
    private lateinit var autoplayLeft: View
    private lateinit var autoplayRight: View
    private var pendingAutoPlay = false          // 음성 검색 결과가 오면 첫 곡 자동재생 대기
    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoPlayRunnable: Runnable? = null
    private val adapter = ResultAdapter(
        onReserve = { reserve(it) },
        onPlayNow = { playNow(it) },
    )
    private val historyAdapter = HistoryAdapter(
        onPlay = { pickPlayOrReplay(it) },
        onScore = { showScoreReview(it) },
    )
    // 랭킹(홈 하단): 채점된 녹음을 점수순으로. 카드 탭=부르기.
    private val rankingAdapter = HistoryAdapter(
        onPlay = { pickPlayOrReplay(it) },
        onScore = { showScoreReview(it) },
    )
    private lateinit var rankingEmpty: TextView

    /** 카드 탭 — 그 곡의 내 녹음이 있으면 부르기/다시듣기 선택, 없으면 바로 부르기. */
    private fun pickPlayOrReplay(item: com.cseini.byd.karaoke.data.PlayHistoryItem) {
        val rec = recordings.all().firstOrNull { it.videoId == item.videoId && java.io.File(it.path).exists() }
        val opts = ArrayList<String>()
        opts.add("🎤 부르기")
        if (rec != null) opts.add("▶ 내 녹음 다시듣기")
        opts.add("🗑 목록에서 삭제")
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(opts.toTypedArray()) { _, which ->
                when (opts[which]) {
                    "🎤 부르기" -> playNow(QueueItem(item.videoId, item.title))
                    "▶ 내 녹음 다시듣기" -> rec?.let { onReplayRecording(it) }
                    "🗑 목록에서 삭제" -> {
                        playHistory.removeByVideoId(item.videoId)
                        val recs = recordings.all().filter { it.videoId == item.videoId }
                        if (recs.isNotEmpty()) recordings.removeItems(recs)
                        refreshHistory()
                        toast("목록에서 삭제했어요")
                    }
                }
            }
            .show()
    }

    /** 최근곡 카드의 점수 배지 탭 → 그 곡의 채점 심사평(항목별)을 보여준다. */
    private fun showScoreReview(item: com.cseini.byd.karaoke.data.PlayHistoryItem) {
        val body = item.breakdown?.takeIf { it.isNotBlank() }
            ?: "이 곡의 심사평이 없어요.\n(이 버전으로 업데이트한 뒤 새로 부른 곡부터 심사평이 남습니다.)"
        AlertDialog.Builder(this)
            .setTitle("🎯 ${item.score}점 심사평")
            .setMessage(body)
            .setPositiveButton("닫기", null)
            .show()
    }

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
            searchDebounce.postDelayed(this, 5000)
        }
    }

    /** 예약 목록 갱신 — 서버(폰)가 SharedPreferences 에 넣으므로 reload 후 읽는다. */
    private fun refreshHomeQueue() {
        if (!::homeQueueSection.isInitialized) return
        queue.reload()
        val items = queue.all()
        // 차량에선 스크롤 조작이 위험 → 플로팅엔 다음 6곡까지만, 총 개수는 제목에.
        homeQueueAdapter.submit(items.take(6))
        homeQueueTitle.text = if (items.size > 6) "🎫 예약된 곡 ${items.size} (다음 6곡)"
            else "🎫 예약된 곡 ${items.size}"
        homeQueueSection.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 튕김 추적: 진짜 크래시(crash.txt)만 팝업으로 표시. '비정상 종료(재시작)' 팝업은
        // BYD 분할화면 리사이즈가 매번 Activity 를 재생성해 오탐이 잦아 제거(이벤트 로그엔 계속 남김).
        CrashLog.install(this)
        CrashLog.takeCrash(this)?.let { showIncidentDialog("⚠ 이전 실행이 크래시로 종료됐어요", it) }
        CrashLog.event(this, "onCreate saved=${savedInstanceState != null} ${cfgSnapshot()}")

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
        embeddedPlayer = EmbeddedPlayer(
            this, settings, recordings, playHistory,
            onClose = { resetToSearchHome() },
            onRepeatedFailure = { onRepeatedPlayFailure() },
        )

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
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.historyAdapter
        }
        historyAdapter.fixedWidthDp = 190
        rankingEmpty = findViewById(R.id.ranking_empty)
        findViewById<RecyclerView>(R.id.ranking).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@MainActivity.rankingAdapter
        }
        rankingAdapter.fixedWidthDp = 190
        homeQueueSection = findViewById(R.id.home_queue_section)
        homeQueueTitle = findViewById(R.id.home_queue_title)
        findViewById<RecyclerView>(R.id.home_queue).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.homeQueueAdapter
        }

        findViewById<Button>(R.id.btn_voice).setOnClickListener { startVoice() }
        findViewById<Button>(R.id.btn_reserve_server).setOnClickListener { showReserveServer() }
        embedScreen = findViewById(R.id.embed_screen)
        // 네비바(녹음함/랭킹/설정)는 Activity 대신 화면 안 오버레이로 전환 → 분할화면 유지.
        NavBar.wireEmbedded(window.decorView, "search") { onNavigate(it) }

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
        // 한글은 조합 중에도 매 글자 변경이 오고 검색 한 번이 결과 페이지 통째(수백 KB)라,
        // 간격이 짧으면 버려질 요청이 회선을 먹어 정작 누른 검색이 밀린다.
        searchInput.doAfterTextChanged { text ->
            cancelAutoPlay()   // 사용자가 직접 타이핑하면 자동재생 취소
            val q = text?.toString()?.trim().orEmpty()
            btnClear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
            pendingSearch?.let { searchDebounce.removeCallbacks(it) }
            if (q.length >= 2) {
                pendingSearch = Runnable { doSearch() }.also { searchDebounce.postDelayed(it, 700) }
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
    /**
     * 앱 시작 시 새 버전이 있으면 설정을 거치지 않고 바로 받도록 안내한다.
     * (설정 화면이 크래시하는 버전이 배포됐을 때도 업데이트 경로가 살아있어야 한다 — v4.16 교훈)
     */
    private fun checkOtaUpdate() {
        lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate() ?: return@launch
            // 긴급(유튜브 재생 깨짐 등) 여부 확인 — 랜딩 min.json 기준
            val min = UpdateManager.fetchMinVersion()
            val urgent = UpdateManager.isBelow(min?.minVersion)
            val b = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(if (urgent) "⚠ 필수 업데이트 v${release.version}" else "🔔 새 버전 v${release.version}")
                .setMessage(
                    if (urgent) (min?.message?.takeIf { it.isNotBlank() }
                        ?: "유튜브 재생 문제로 지금 업데이트해야 정상 작동합니다.")
                    else "지금 업데이트할까요? (다운로드부터 재시작까지 자동으로 진행됩니다)",
                )
                .setPositiveButton("업데이트") { _, _ -> startOtaDownload(release) }
            if (!urgent) b.setNegativeButton("나중에", null) else b.setCancelable(false)
            b.show()
        }
    }

    /** 재생 추출이 연속 실패(유튜브 방식 변경 의심) → 즉시 업데이트 확인·안내. */
    private var ytFailNotified = false
    override fun onRepeatedPlayFailure() {
        if (ytFailNotified) return
        ytFailNotified = true
        lifecycleScope.launch {
            val release = UpdateManager.checkForUpdate()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("재생에 반복 실패했어요")
                .setMessage(
                    if (release != null)
                        "유튜브 재생 방식이 바뀐 것 같아요. 업데이트하면 해결될 수 있어요."
                    else "유튜브 재생 방식이 바뀐 것 같아요. 잠시 후 자동으로 고쳐진 버전이 올라옵니다.",
                )
                .apply {
                    if (release != null) setPositiveButton("업데이트") { _, _ -> startOtaDownload(release) }
                        .setNegativeButton("닫기", null)
                    else setPositiveButton("확인", null)
                }
                .show()
        }
    }

    private fun startOtaDownload(release: UpdateManager.Release) {
        com.cseini.byd.karaoke.update.UpdateFlow.start(this, release)
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
        // USB 마이크가 연결됨(앱 실행 중) → 권한이 부여됐으니 즉시 버튼 제어 시작.
        if (intent.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED") {
            syncPhysicalButtons()
            return
        }
        resetToSearchHome()
    }

    override fun onResume() {
        super.onResume()
        // 설치 권한을 켜고 설정에서 돌아온 경우, 미뤄둔 업데이트 설치를 자동 재개
        UpdateManager.retryPendingInstall(this)
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
    }

    /** 최근 부른 노래(재생 기록 기반, 녹음과 분리). 녹음을 꺼도·지워도 남는다. */
    private fun refreshHistory() {
        playHistory.reload()
        val recent = playHistory.all()
        historyAdapter.submit(recent)
        historyEmpty.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE

        // 랭킹: 채점된 녹음을 점수 내림차순으로(홈 하단 캐러셀). 카드 형식은 최근곡과 공용.
        val ranked = recordings.all()
            .filter { it.score >= 0 }
            .sortedWith(compareByDescending<com.cseini.byd.karaoke.data.RecordingItem> { it.score }.thenByDescending { it.at })
            .map { com.cseini.byd.karaoke.data.PlayHistoryItem(it.videoId, it.title, it.at, it.score) }
        rankingAdapter.submit(ranked)
        rankingEmpty.visibility = if (ranked.isEmpty()) View.VISIBLE else View.GONE
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
        val wantAutoPlay = pendingAutoPlay   // 이번 호출이 음성 트리거였는지 확정
        pendingAutoPlay = false
        cancelAutoPlay()                     // 진행 중인 카운트다운은 중단
        if (wantAutoPlay) autoPlayAfterSearch = true
        status.text = "검색 중…"
        // 타이핑 디바운스로 '같은 검색어'가 이미 날아가 있으면 그대로 기다린다.
        // 취소하고 새로 시작하면 거의 끝나가던 요청을 버리고 처음부터 다시 받게 되는데,
        // 검색 버튼은 대개 타이핑이 끝난 직후에 눌러서 이 낭비가 그대로 체감 지연이 된다.
        // (음성 검색이 같은 검색어로 먼저 떠 있을 수 있어 자동재생 의도는 위에서 살려 둔다)
        if (q == inFlightQuery && searchJob?.isActive == true) return
        searchJob?.cancel()   // 검색어가 바뀌었으면 이전 검색은 취소(HTTP 요청도 함께 끊긴다)
        autoPlayAfterSearch = wantAutoPlay   // 새 검색을 시작하니 직전 검색의 자동재생 의도는 버린다
        inFlightQuery = q
        val startedAt = System.currentTimeMillis()
        searchJob = lifecycleScope.launch {
            val r = repo.search(q, settings.youtubeApiKey, System.currentTimeMillis(), settings.keylessSearch)
            inFlightQuery = null
            logSearchTiming(q, System.currentTimeMillis() - startedAt, r)
            when (r) {
                is YouTubeRepository.Result.Ok -> {
                    adapter.submit(r.items)
                    showResults()
                    status.text = if (r.items.isEmpty()) "결과가 없습니다." else "결과 ${r.items.size}개"
                    if (autoPlayAfterSearch && settings.autoPlayVoiceFirst && r.items.isNotEmpty()) {
                        startAutoPlayCountdown(r.items.first())
                    }
                }
                is YouTubeRepository.Result.Error -> status.text = r.message
            }
            autoPlayAfterSearch = false
        }
    }

    /**
     * 검색이 느릴 때 원인을 실차에서 가리기 위한 기록(설정>이벤트 로그).
     * net 이 대부분이면 회선, parse 가 대부분이면 결과 페이지 파싱(헤드유닛 CPU) 문제다.
     */
    private fun logSearchTiming(q: String, totalMs: Long, r: YouTubeRepository.Result) {
        val n = (r as? YouTubeRepository.Result.Ok)?.items?.size ?: -1
        CrashLog.event(
            this,
            "검색 ${totalMs}ms (net ${YouTubeScraper.lastFetchMs} / parse ${YouTubeScraper.lastParseMs}, " +
                "${YouTubeScraper.lastKb}KB) n=$n q=${q.take(20)}",
        )
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
                    playNow(item)
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

    /** 부르기 — 재생은 항상 이 화면 안(임베드)에서 처리해 분할화면을 유지한다. */
    private fun playNow(item: QueueItem) {
        hideKeyboard()
        cancelAutoPlay()
        embeddedPlayer?.play(item.videoId, item.title)
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
        // 후진·컨시그널 카메라 화면이 덮이는 시점을 로그에 남긴다(튕김 원인 대조용).
        CrashLog.event(this, "focus=$hasFocus ${cfgSnapshot()}")
        if (hasFocus) syncPhysicalButtons()
    }

    /**
     * 구성 스냅샷. 카메라 화면 전후로 이 값이 달라졌다면 '구성 변경에 의한 화면 재생성'이 튕김의
     * 원인이고, 달라진 항목을 매니페스트 configChanges 에 추가하면 된다. 값이 같은데 죽었다면
     * 시스템이 메모리 때문에 프로세스를 죽인 것이다.
     */
    private fun cfgSnapshot(): String = resources.configuration.let {
        "cfg ui=${it.uiMode} ori=${it.orientation} dpi=${it.densityDpi} " +
            "w=${it.screenWidthDp} h=${it.screenHeightDp} layout=${it.screenLayout} " +
            "nav=${it.navigation} touch=${it.touchscreen} kbd=${it.keyboard} loc=${it.locales[0]}"
    }

    /** configChanges 로 직접 처리한 구성 변경(재생성 없이 넘어간 것) — 어떤 게 오는지 확인용. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        CrashLog.event(this, "cfgChanged ${cfgSnapshot()}")
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

    /** 튕김 증거(크래시/직전 로그) 표시 — 복사해 제보할 수 있게. */
    private fun showIncidentDialog(title: String, body: String) {
        val tv = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            text = body
        }
        AlertDialog.Builder(this)
            .setTitle("$title — 복사해 제보해주세요")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("복사") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("incident", body))
                toast("복사되었습니다")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    override fun onDestroy() {
        cancelAutoPlay()
        voice.stop()
        usbMic?.stop()
        // 액티비티가 재생성(구성 변경·메모리 회수)될 때 플레이어가 유출되면
        // 화면은 초기화됐는데 소리만 계속 나고, 재시작 시 노래가 겹친다.
        runCatching { embeddedPlayer?.close() }
        // close() 가 이벤트를 한 줄 더 남기므로, 이 줄이 마지막이 되도록 close 뒤에 기록한다.
        // (앞에 두면 takeAbnormalEnd 의 '마지막 줄' 검사가 항상 빗나가 비정상 종료가 보고되지 않는다)
        CrashLog.event(this, "onDestroy fin=$isFinishing")
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

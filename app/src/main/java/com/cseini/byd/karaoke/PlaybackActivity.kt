package com.cseini.byd.karaoke

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.audio.MixRecorder
import com.cseini.byd.karaoke.audio.WavIo
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.data.RecordingItem
import com.cseini.byd.karaoke.data.RecordingStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.Storage
import com.cseini.byd.karaoke.data.youtube.YouTubeRepository
import com.cseini.byd.karaoke.player.KaraokePlayer
import com.cseini.byd.karaoke.player.PlayerCallbacks
import com.cseini.byd.karaoke.player.StreamPlayer
import com.cseini.byd.karaoke.scoring.ScoringEngine
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 재생 + 자동 녹음 + 채점 + 다시 듣기.
 * 곡이 PLAYING 되면 자동 녹음 시작 → ENDED 또는 정지 시 즉시 채점.
 * 녹음은 마이크로 반주(차 스피커)+목소리를 한 트랙에 함께 담으므로, 다시 듣기는
 * 그 파일 하나만 재생한다(두 번째 플레이어가 없어 싱크가 어긋날 일이 없다).
 */
@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, videoId: String, title: String, fromQueue: Boolean) =
            Intent(ctx, PlaybackActivity::class.java).apply {
                putExtra("videoId", videoId)
                putExtra("title", title)
                putExtra("fromQueue", fromQueue)
            }

        /**
         * 검색 결과에서 바로 부르기. 재생 불가(임베드 차단) 영상이면 뒤 후보로 자동으로 넘어간다.
         * candidates 는 선택한 곡부터 시작하는 후보 목록.
         */
        fun intentWithCandidates(ctx: Context, candidates: List<QueueItem>, startIndex: Int) =
            Intent(ctx, PlaybackActivity::class.java).apply {
                val from = candidates.drop(startIndex.coerceIn(0, candidates.size))
                putExtra("videoId", from.firstOrNull()?.videoId.orEmpty())
                putExtra("title", from.firstOrNull()?.title ?: "재생 중")
                putExtra("fromQueue", false)
                putStringArrayListExtra("candIds", ArrayList(from.map { it.videoId }))
                putStringArrayListExtra("candTitles", ArrayList(from.map { it.title }))
            }

        /** 녹음함에서 진입: 반주 + 저장된 녹음을 함께 재생. */
        fun replayIntent(ctx: Context, item: RecordingItem) =
            Intent(ctx, PlaybackActivity::class.java).apply {
                putExtra("videoId", item.videoId)
                putExtra("title", item.title)
                putExtra("replayPath", item.path)
            }
    }

    private lateinit var settings: SettingsStore
    private lateinit var queue: QueueStore
    private lateinit var recordings: RecordingStore
    private lateinit var recorder: MixRecorder
    private lateinit var repo: YouTubeRepository

    private lateinit var reservePanel: View
    private lateinit var reserveInput: EditText
    private lateinit var reserveStatus: TextView
    private val reserveAdapter = ResultAdapter(
        onReserve = { reserveToQueue(it, front = false) },
        onPlayNow = { reserveToQueue(it, front = true) },
    )

    private lateinit var player: KaraokePlayer

    private lateinit var songTitle: TextView
    private lateinit var recStatus: TextView
    private lateinit var replayControls: View
    private lateinit var replaySeek: SeekBar
    private lateinit var replayTime: TextView
    private lateinit var replayPlay: Button
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var scoreOverlay: FrameLayout
    private lateinit var scoreTotal: TextView
    private lateinit var scoreGrade: TextView
    private lateinit var scoreDetail: TextView

    private var currentVideoId = ""
    private var candIds: List<String> = emptyList()
    private var candTitles: List<String> = emptyList()
    private var candIndex = 0
    private var recordStarted = false
    private var scored = false
    private var replayOnly = false   // 녹음함에서 진입: 채점·녹음 없이 다시듣기만
    private var replaying = false     // 다시듣기 재생 중(반주+목소리 동시)
    private var lastRecording: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var scoreAnimator: ValueAnimator? = null


    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        settings = SettingsStore(this)
        queue = QueueStore(this)
        recordings = RecordingStore(this)
        recorder = MixRecorder(this, settings)
        repo = YouTubeRepository()

        songTitle = findViewById(R.id.song_title)
        recStatus = findViewById(R.id.rec_status)
        scoreOverlay = findViewById(R.id.score_overlay)
        scoreTotal = findViewById(R.id.score_total)
        scoreGrade = findViewById(R.id.score_grade)
        scoreDetail = findViewById(R.id.score_detail)

        currentVideoId = intent.getStringExtra("videoId").orEmpty()
        songTitle.text = intent.getStringExtra("title") ?: "재생 중"
        candIds = intent.getStringArrayListExtra("candIds") ?: emptyList()
        candTitles = intent.getStringArrayListExtra("candTitles") ?: emptyList()
        candIndex = 0

        // 녹음함에서 들어온 경우: 채점·녹음 없이 저장된 믹스 파일만 재생
        intent.getStringExtra("replayPath")?.let { path ->
            lastRecording = File(path)
            scored = true
            replayOnly = true
            recStatus.text = "▶ 저장된 노래 재생"
        }

        val container = findViewById<FrameLayout>(R.id.player_container)
        val callbacks = PlayerCallbacks(
            onPlaying = { onSongPlaying() },
            onEnded = { onSongEnded() },
            onTime = { },
            onEmbedBlocked = { onEmbedBlocked() },
            onError = { msg ->
                if (recorder.isRecording) recorder.stop()
                recStatus.text = msg
            },
        )
        // replay 컨트롤은 startReplay() 안에서 쓰이므로 그 전에 초기화한다(녹음함 재생 크래시 방지).
        replayControls = findViewById(R.id.replay_controls)
        replaySeek = findViewById(R.id.replay_seek)
        replayTime = findViewById(R.id.replay_time)
        replayPlay = findViewById(R.id.replay_play)
        findViewById<Button>(R.id.replay_back10).setOnClickListener { seekReplay(-10000) }
        findViewById<Button>(R.id.replay_back5).setOnClickListener { seekReplay(-5000) }
        findViewById<Button>(R.id.replay_fwd5).setOnClickListener { seekReplay(5000) }
        findViewById<Button>(R.id.replay_fwd10).setOnClickListener { seekReplay(10000) }
        replayPlay.setOnClickListener { toggleReplayPlay() }
        replaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.let { runCatching { it.seekTo(p) } }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        player = StreamPlayer(this, container, lifecycleScope, callbacks, recorder.accompProcessor)
        if (replayOnly) startReplay() else player.load(currentVideoId)

        scoreOverlay.setOnClickListener { goToSearch() }
        findViewById<Button>(R.id.score_next).setOnClickListener { playNext() }
        findViewById<Button>(R.id.score_retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { onStopPressed() }
        findViewById<Button>(R.id.btn_retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.btn_next).setOnClickListener { playNext() }

        setupReservePanel()

        NavBar.wire(this, PlaybackActivity::class.java)
    }

    // ── 부르는 중 검색·예약 패널 ──────────────────────────────────────

    private fun setupReservePanel() {
        reservePanel = findViewById(R.id.reserve_panel)
        reserveInput = findViewById(R.id.reserve_input)
        reserveStatus = findViewById(R.id.reserve_status)
        findViewById<RecyclerView>(R.id.reserve_results).apply {
            layoutManager = LinearLayoutManager(this@PlaybackActivity)
            adapter = reserveAdapter
        }
        findViewById<Button>(R.id.btn_reserve_search).setOnClickListener { showReservePanel() }
        findViewById<Button>(R.id.btn_reserve_close).setOnClickListener { reservePanel.visibility = View.GONE }
        findViewById<Button>(R.id.reserve_search_btn).setOnClickListener { doReserveSearch() }
        reserveInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doReserveSearch(); true } else false
        }
    }

    private fun showReservePanel() {
        reservePanel.visibility = View.VISIBLE
        reserveInput.requestFocus()
    }

    private fun doReserveSearch() {
        val q = reserveInput.text.toString().trim()
        if (q.isEmpty()) { reserveStatus.text = "검색어를 입력하세요."; return }
        reserveStatus.text = "검색 중…"
        lifecycleScope.launch {
            when (val r = repo.search(q, settings.youtubeApiKey, System.currentTimeMillis(), settings.keylessSearch)) {
                is YouTubeRepository.Result.Ok -> {
                    reserveAdapter.submit(r.items)
                    reserveStatus.text = if (r.items.isEmpty()) "결과가 없습니다." else "예약할 곡을 고르세요 (${r.items.size}개)"
                }
                is YouTubeRepository.Result.Error -> reserveStatus.text = r.message
            }
        }
    }

    private fun reserveToQueue(item: QueueItem, front: Boolean) {
        if (front) { queue.addFirst(item); toast("다음 차례로 예약: ${item.title}") }
        else { queue.add(item); toast("예약: ${item.title}") }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    // ── 노래 부르기(녹음·채점) ──────────────────────────────────────

    private fun onSongPlaying() {
        if (recordStarted || scored) return
        if (!hasMic()) {
            recStatus.text = "마이크 권한이 없어 채점 없이 재생만 합니다."
            return
        }
        val dir = Storage.recordingsDir(this, settings.storageMode)
        val safeTitle = songTitle.text.toString()
            .replace(Regex("[^가-힣A-Za-z0-9]+"), "_").trim('_').take(30).ifEmpty { "노래" }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(java.util.Date())
        val file = File(dir, "${safeTitle}_${currentVideoId}_${ts}.wav")
        val startPos = player.currentPositionMs()   // 녹음 시작 시점의 재생 위치
        val err = recorder.start(file, startPos) { db ->
            runOnUiThread { if (recorder.isRecording) recStatus.text = "🔴 녹음 중… ${"%.0f".format(db)} dBFS" }
        }
        if (err != null) {
            recStatus.text = "녹음 시작 실패: $err"
        } else {
            recordStarted = true
            lastRecording = file
        }
    }

    private fun onSongEnded() {
        if (scored || !recordStarted) return
        scored = true
        val file = recorder.stop()
        if (file == null || !file.exists()) {
            recStatus.text = "녹음 파일이 없어 채점을 건너뜁니다."
            return
        }
        if (!settings.scoringEnabled) {
            // 채점 끔 — 즉시 녹음만 저장.
            recordings.add(
                RecordingItem(
                    path = file.absolutePath,
                    videoId = currentVideoId,
                    title = songTitle.text.toString(),
                    score = -1,
                    at = System.currentTimeMillis(),
                )
            )
            val pruned = Storage.pruneToLimit(file.parentFile ?: file, settings.maxStorageBytes)
            if (pruned.isNotEmpty()) recordings.removeByPaths(pruned)
            recStatus.text = "🎵 녹음 저장됨 — 녹음함에서 들을 수 있어요"
            return
        }
        recStatus.text = "채점 중…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val wav = WavIo.read(file)
                    ScoringEngine.score(wav.samples, wav.sampleRate)
                }.getOrNull()
            }
            recordings.add(
                RecordingItem(
                    path = file.absolutePath,
                    videoId = currentVideoId,
                    title = songTitle.text.toString(),
                    score = result?.total ?: -1,
                    at = System.currentTimeMillis(),
                )
            )
            // 용량 상한 초과 시 오래된 녹음부터 자동 삭제
            val pruned = Storage.pruneToLimit(file.parentFile ?: file, settings.maxStorageBytes)
            if (pruned.isNotEmpty()) recordings.removeByPaths(pruned)
            if (result == null) {
                recStatus.text = "채점 실패(오디오를 읽지 못함). 녹음은 녹음함에 저장됨."
            } else {
                recStatus.text = "🎯 채점 완료 — 녹음함에서 다시 들을 수 있어요"
                val detail = result.breakdown.lines().drop(1).joinToString("\n") + "\n\n[${recorder.debugInfo()}]"
                showScoreOverlay(result.total, detail)
            }
        }
    }

    // ── 노래방식 점수 연출 ─────────────────────────────────────────

    private fun showScoreOverlay(total: Int, detail: String) {
        scoreDetail.text = detail
        scoreTotal.text = "0"
        scoreGrade.text = when {
            total >= 95 -> "🏆 완벽한 무대!"
            total >= 88 -> "✨ 명 가수!"
            total >= 80 -> "🔥 열창!"
            else -> "👏 잘했어요!"
        }
        scoreOverlay.visibility = View.VISIBLE
        scoreAnimator?.cancel()
        scoreAnimator = ValueAnimator.ofInt(0, total).apply {
            duration = 2000
            addUpdateListener { scoreTotal.text = "${it.animatedValue}" }
            doOnEnd {
                scoreTotal.animate().scaleX(1.2f).scaleY(1.2f).setDuration(180).withEndAction {
                    scoreTotal.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }.start()
            }
            start()
        }
    }

    private fun hideScoreOverlay() {
        scoreAnimator?.cancel()
        scoreOverlay.visibility = View.GONE
    }

    /** 점수 화면을 탭하면 노래 화면이 아니라 검색 홈으로 나간다. */
    private fun goToSearch() {
        scoreAnimator?.cancel()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    // ── 다시 듣기: 저장된 믹스 파일(반주+목소리) 하나만 재생 → 싱크 어긋날 일 없음 ────

    private fun startReplay() {
        val file = lastRecording
        if (file == null || !file.exists()) { toast("들려줄 녹음이 없습니다"); return }
        hideScoreOverlay()
        stopMediaPlayer()
        replaying = true
        val sizeKb = file.length() / 1024
        mediaPlayer = MediaPlayer().apply {
            try {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnErrorListener { _, what, extra ->
                    recStatus.text = "❗재생 오류 (코드 $what/$extra, 파일 ${sizeKb}KB)"
                    replaying = false
                    true
                }
                setOnCompletionListener {
                    recStatus.text = "다시 듣기 완료"
                    replaying = false
                    replayPlay.text = "▶"
                    uiHandler.removeCallbacks(replayTicker)
                }
                setDataSource(file.absolutePath)
                prepare()
                start()
                recStatus.text = "▶ 내 노래 다시 듣는 중 (${sizeKb}KB)"
                replaySeek.max = duration
                replaySeek.progress = 0
                replayControls.visibility = View.VISIBLE
                replayPlay.text = "⏸"
                startReplayTicker()
            } catch (e: Exception) {
                recStatus.text = "❗재생 실패: ${e.message} (파일 ${sizeKb}KB)"
                replaying = false
            }
        }
    }

    private val replayTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            runCatching {
                replaySeek.progress = mp.currentPosition
                replayTime.text = "${fmtTime(mp.currentPosition)} / ${fmtTime(mp.duration)}"
                replayPlay.text = if (mp.isPlaying) "⏸" else "▶"
            }
            uiHandler.postDelayed(this, 300)
        }
    }

    private fun startReplayTicker() {
        uiHandler.removeCallbacks(replayTicker)
        uiHandler.post(replayTicker)
    }

    private fun hideReplayControls() {
        uiHandler.removeCallbacks(replayTicker)
        replayControls.visibility = View.GONE
    }

    private fun seekReplay(deltaMs: Int) {
        val mp = mediaPlayer ?: return
        runCatching {
            mp.seekTo((mp.currentPosition + deltaMs).coerceIn(0, mp.duration))
        }
    }

    private fun toggleReplayPlay() {
        val mp = mediaPlayer ?: return
        runCatching {
            if (mp.isPlaying) {
                mp.pause(); replayPlay.text = "▶"
            } else {
                mp.start(); replayPlay.text = "⏸"; replaying = true; startReplayTicker()
            }
        }
    }

    private fun fmtTime(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ── 조작 버튼 ─────────────────────────────────────────────────

    /** 정지 = 즉시 채점(일시정지 개념 없음). 다시 듣는 중이면 재생만 멈춘다. */
    private fun onStopPressed() {
        if (replaying) {
            replaying = false
            stopMediaPlayer()
            hideReplayControls()
            recStatus.text = "정지됨"
            return
        }
        player.pause()
        onSongEnded()   // PAUSED 콜백을 기다리지 않고 바로 채점
    }

    private fun retry() {
        hideScoreOverlay()
        stopMediaPlayer()
        if (recorder.isRecording) recorder.stop()
        replaying = false
        resetForNewSong()
        player.load(currentVideoId)
    }

    // 임베드 차단(embeddable=false 또는 Content ID)은 IFrame 재생 시점에 드러난다.
    // 검색 후보가 남아 있으면 다음 후보로, 없으면 대기열로 자동으로 넘어가 재생 가능한 곡을 찾는다.
    private fun onEmbedBlocked() {
        if (recorder.isRecording) recorder.stop()
        // 1) 같은 검색의 다음 후보 자동 시도
        if (candIndex + 1 < candIds.size) {
            candIndex++
            currentVideoId = candIds[candIndex]
            songTitle.text = candTitles.getOrElse(candIndex) { "재생 중" }
            recStatus.text = "재생 불가 영상 — 다음 후보 시도 중…"
            replaying = false
            resetForNewSongKeepCandidates()
            player.load(currentVideoId)
            return
        }
        // 2) 후보가 없으면 대기열
        val next = queue.pollFirst()
        if (next != null) {
            toast("다음 곡으로 넘어갑니다: ${next.title}")
            currentVideoId = next.videoId
            songTitle.text = next.title
            resetForNewSong()
            player.load(currentVideoId)
        } else {
            recStatus.text = "재생 가능한 영상을 찾지 못했습니다.\n다른 검색어로 시도해보세요."
        }
    }

    /** 후보 목록은 유지한 채(자동 넘김용) 곡 상태만 초기화. */
    private fun resetForNewSongKeepCandidates() {
        val ids = candIds; val titles = candTitles; val idx = candIndex
        resetForNewSong()
        candIds = ids; candTitles = titles; candIndex = idx
    }

    private fun playNext() {
        hideScoreOverlay()
        stopMediaPlayer()
        if (recorder.isRecording) recorder.stop()
        val next = queue.pollFirst()
        if (next == null) { toast("대기열이 비었습니다"); return }
        replaying = false
        currentVideoId = next.videoId
        songTitle.text = next.title
        resetForNewSong()
        player.load(currentVideoId)
    }

    private fun resetForNewSong() {
        recordStarted = false
        scored = false
        replayOnly = false
        replaying = false
        lastRecording = null
        // 다른 곡(대기열/다시부르기)로 넘어가면 이전 검색 후보는 폐기
        candIds = emptyList()
        candTitles = emptyList()
        candIndex = 0
        hideScoreOverlay()
        hideReplayControls()
        recStatus.text = "대기 중"
    }

    private fun stopMediaPlayer() {
        mediaPlayer?.run { runCatching { if (isPlaying) stop() }; release() }
        mediaPlayer = null
    }

    override fun onStop() {
        super.onStop()
        if (recorder.isRecording) recorder.stop()
        stopMediaPlayer()
        player.pause()   // 화면을 벗어나면 반주도 멈춤
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stop()
        stopMediaPlayer()
        uiHandler.removeCallbacks(replayTicker)
        player.release()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

package com.cseini.byd.karaoke

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    private lateinit var recordings: RecordingStore
    private lateinit var queue: QueueStore
    private lateinit var recorder: MixRecorder
    private lateinit var repo: YouTubeRepository

    private lateinit var player: KaraokePlayer

    private lateinit var songTitle: TextView
    private lateinit var recStatus: TextView
    private lateinit var songControls: View
    private lateinit var replayControls: View
    private lateinit var replaySeek: SeekBar
    private lateinit var replayTime: TextView
    private lateinit var replayPlay: Button
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var scoreOverlay: FrameLayout
    private lateinit var scoreTotal: TextView
    private lateinit var scoreGrade: TextView
    private lateinit var scoreDetail: TextView
    private lateinit var scoreNextInfo: TextView
    private lateinit var queueSide: View
    private val queueAdapter = ReserveQueueAdapter(
        onPlay = { playReserved(it) },
        onDelete = { queue.removeByVideoId(it.videoId); refreshQueueSide() },
    )
    private var scoreCountdownRunnable: Runnable? = null

    private var currentVideoId = ""
    private var candIds: List<String> = emptyList()
    private var candTitles: List<String> = emptyList()
    private var candIndex = 0
    private var recordStarted = false
    private var scored = false
    private var replayOnly = false   // 녹음함에서 진입: 채점·녹음 없이 다시듣기만
    private var replaying = false     // 다시듣기 재생 중(반주+목소리 동시)
    private var replayVideo = false   // 다시듣기 때 유튜브 영상(음소거)을 함께 재생 중
    private var replayVideoAligned = false  // 영상을 녹음 위치로 최초 1회 맞췄는지
    private var replaySeekCooldown = 0      // 재정렬 후 쿨다운(틱 단위) — 잦은 seek 재버퍼링 방지
    private var lastRecording: File? = null
    private var mediaPlayer: MediaPlayer? = null
    private var scoreAnimator: ValueAnimator? = null


    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        settings = SettingsStore(this)
        recordings = RecordingStore(this)
        queue = QueueStore(this)
        recorder = MixRecorder(this, settings)
        repo = YouTubeRepository()

        songTitle = findViewById(R.id.song_title)
        recStatus = findViewById(R.id.rec_status)
        scoreOverlay = findViewById(R.id.score_overlay)
        scoreTotal = findViewById(R.id.score_total)
        scoreGrade = findViewById(R.id.score_grade)
        scoreDetail = findViewById(R.id.score_detail)
        scoreNextInfo = findViewById(R.id.score_next_info)
        queueSide = findViewById(R.id.queue_side)
        findViewById<RecyclerView>(R.id.queue_side_list).apply {
            layoutManager = LinearLayoutManager(this@PlaybackActivity)
            adapter = queueAdapter
        }

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
                // 다시듣기 중 영상 스트림 실패 → 영상 없이 녹음 소리만 계속 재생
                if (replayOnly) {
                    replayVideo = false
                    if (replaying) recStatus.text = "▶ 내 노래 다시 듣는 중 (영상 없이)"
                } else {
                    recStatus.text = msg
                }
            },
        )
        // replay 컨트롤은 startReplay() 안에서 쓰이므로 그 전에 초기화한다(녹음함 재생 크래시 방지).
        songControls = findViewById(R.id.song_controls)
        replayControls = findViewById(R.id.replay_controls)
        replaySeek = findViewById(R.id.replay_seek)
        replayTime = findViewById(R.id.replay_time)
        replayPlay = findViewById(R.id.replay_play)
        replayPlay.setOnClickListener { toggleReplayPlay() }
        replaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 드래그하면 녹음 소리와 영상을 같은 위치로 함께 이동
                    mediaPlayer?.let { runCatching { it.seekTo(p) } }
                    if (replayVideo) player.seekTo(p.toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // 다시듣기는 가사 화면만 필요 → 저화질 영상으로 재생(구형 헤드유닛 끊김 방지).
        player = StreamPlayer(this, container, lifecycleScope, callbacks, recorder.accompProcessor, lowRes = replayOnly)
        if (replayOnly) {
            // 다시듣기: 노래 부르기용 버튼(정지·다시부르기·다음곡·예약)은 숨기고 재생/정지만 남긴다.
            songControls.visibility = View.GONE
            startReplay()
        } else {
            player.load(currentVideoId)
        }

        scoreOverlay.setOnClickListener { goToSearch() }
        findViewById<Button>(R.id.score_retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.score_next).setOnClickListener { playNextReserved() }
        findViewById<Button>(R.id.score_close).setOnClickListener { goToSearch() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { onStopPressed() }
        findViewById<Button>(R.id.btn_retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { cancelSong() }

        NavBar.wire(this, PlaybackActivity::class.java)
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
            // 채점 화면이 없으므로, 예약곡이 있으면 상태줄에 카운트하며 5초 뒤 자동 넘김
            queue.reload()
            queue.peekFirst()?.let { startAutoAdvance(it, overlay = false) }
            return
        }
        recStatus.text = "채점 중…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    // 믹스가 아니라 목소리(마이크 원음)만 채점 — 반주로 인한 고득점 방지.
                    val (samples, sr) = recorder.voiceForScoring()
                    ScoringEngine.score(samples, sr)
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
                // 진단 로그(변별력 점검용): 발성비율이 높은데 안 불렀다면 반주 누출 의심.
                android.util.Log.d(
                    "KaraokeScore",
                    "total=${result.total} voiced%=${result.voicedPct} f0=${result.medianF0.toInt()} " +
                        "acc=${result.pitchAccuracy} stab=${result.pitchStability} beat=${result.beatConsistency} " +
                        "vol=${result.volumeDynamics} vib=${result.vibratoReach}",
                )
                // 점수 항목만 표시(총점 줄·기술 설명·디버그 정보는 제외)
                val detail = result.breakdown.lines()
                    .drop(1)
                    .filterNot { it.startsWith("(") }
                    .joinToString("\n")
                showScoreOverlay(result.total, detail)
            }
        }
    }

    // ── 노래방식 점수 연출 ─────────────────────────────────────────

    private fun showScoreOverlay(total: Int, detail: String) {
        // 예약된 곡이 남아 있으면 '다음 예약곡' 버튼 + 5초 뒤 자동 재생 카운트다운
        queue.reload()
        val next = queue.peekFirst()
        findViewById<Button>(R.id.score_next).visibility =
            if (next != null) View.VISIBLE else View.GONE
        if (next != null) startAutoAdvance(next, overlay = true) else cancelScoreCountdown()
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
        cancelScoreCountdown()
        scoreOverlay.visibility = View.GONE
    }

    /** 점수 화면을 탭하면 노래 화면이 아니라 검색 홈으로 나간다. */
    private fun goToSearch() {
        scoreAnimator?.cancel()
        cancelScoreCountdown()
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    // ── 다시 듣기: 유튜브 영상(음소거·가사)을 녹음 소리에 맞춰 함께 재생 ──────────
    // 소리는 저장된 믹스 파일(MediaPlayer)이 마스터, 영상(ExoPlayer)은 음소거로 따라온다.
    // 영상 스트림을 못 불러오면(오프라인 등) 소리만 재생한다.

    private fun startReplay() {
        val file = lastRecording
        if (file == null || !file.exists()) { toast("들려줄 녹음이 없습니다"); return }
        hideScoreOverlay()
        stopMediaPlayer()
        replaying = true
        // 영상은 음소거로 함께 재생(가사 화면). 녹음 소리에 맞춰 최초 1회만 정렬한다.
        if (currentVideoId.isNotBlank()) {
            replayVideo = true
            replayVideoAligned = false
            replaySeekCooldown = 0
            player.setVolume(0f)
            player.load(currentVideoId)
        }
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
                    if (replayVideo) player.pause()
                    replayPlay.text = "▶ 재생"
                    uiHandler.removeCallbacks(replayTicker)
                }
                setDataSource(file.absolutePath)
                prepare()
                start()
                recStatus.text = if (replayVideo) "▶ 영상과 함께 내 노래 다시 듣는 중" else "▶ 내 노래 다시 듣는 중"
                replaySeek.max = duration
                replaySeek.progress = 0
                replayControls.visibility = View.VISIBLE
                replayPlay.text = "⏸ 정지"
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
                val pos = mp.currentPosition
                replaySeek.progress = pos
                replayTime.text = "${fmtTime(pos)} / ${fmtTime(mp.duration)}"
                replayPlay.text = if (mp.isPlaying) "⏸ 정지" else "▶ 재생"
                // 영상 위치 맞추기: 매 틱 seek 하면 재버퍼링으로 끊기므로,
                // 영상이 실제 재생되기 시작하면 '한 번'만 녹음 위치로 맞추고 그대로 둔다.
                // 이후엔 크게 어긋났을 때만(1.5초↑) 쿨다운을 두고 드물게 재정렬한다.
                if (replayVideo && mp.isPlaying && player.isPlaying()) {
                    if (replaySeekCooldown > 0) replaySeekCooldown--
                    val drift = kotlin.math.abs(player.currentPositionMs() - pos)
                    if (!replayVideoAligned) {
                        player.seekTo(pos.toLong())
                        replayVideoAligned = true
                        replaySeekCooldown = 20   // 약 6초
                    } else if (drift > 1500 && replaySeekCooldown == 0) {
                        player.seekTo(pos.toLong())
                        replaySeekCooldown = 20
                    }
                }
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

    private fun toggleReplayPlay() {
        val mp = mediaPlayer ?: return
        runCatching {
            if (mp.isPlaying) {
                mp.pause(); if (replayVideo) player.pause(); replayPlay.text = "▶ 재생"
            } else {
                mp.start(); if (replayVideo) player.play()
                replayPlay.text = "⏸ 정지"; replaying = true; startReplayTicker()
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
        cancelScoreCountdown()
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

    // 예약(폰 리모컨)곡이 부르는 도중에도 들어올 수 있어, 주기적으로 옆 예약 목록을 갱신.
    private val queuePoll = object : Runnable {
        override fun run() {
            if (!replayOnly) refreshQueueSide()
            uiHandler.postDelayed(this, 2500)
        }
    }

    /** 플레이어 옆 상시 예약 목록 갱신(예약 있을 때만 노출). */
    private fun refreshQueueSide() {
        if (replayOnly) { queueSide.visibility = View.GONE; return }
        queue.reload()
        val items = queue.all()
        queueAdapter.submit(items)
        queueSide.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 예약 목록에서 특정 곡을 골라 바로 부른다. */
    private fun playReserved(item: QueueItem) {
        queue.removeByVideoId(item.videoId)
        loadNewSong(item.videoId, item.title)
    }

    /** 예약 대기열의 맨 앞 곡을 꺼내 부른다. */
    private fun playNextReserved() {
        val next = queue.pollFirst()
        if (next == null) { toast("예약된 곡이 없습니다"); return }
        loadNewSong(next.videoId, next.title)
    }

    private fun loadNewSong(videoId: String, title: String) {
        cancelScoreCountdown()
        hideScoreOverlay()
        stopMediaPlayer()
        if (recorder.isRecording) recorder.stop()
        replaying = false
        currentVideoId = videoId
        songTitle.text = title
        resetForNewSong()
        player.load(currentVideoId)
    }

    // ── 곡이 끝나고 아무것도 안 누르면 5초 뒤 다음 예약곡 자동 재생 ─────────────
    // 채점 켬 → 점수화면 상단(scoreNextInfo)에, 채점 끔 → 상태줄(recStatus)에 카운트 표시.
    private fun startAutoAdvance(next: QueueItem, overlay: Boolean) {
        cancelScoreCountdown()
        if (overlay) scoreNextInfo.visibility = View.VISIBLE
        val r = object : Runnable {
            var n = 10
            override fun run() {
                if (n <= 0) { scoreCountdownRunnable = null; playNextReserved(); return }
                val msg = "${n}초 후 다음 예약곡 ▶ '${next.title}' 자동 재생"
                if (overlay) scoreNextInfo.text = msg else recStatus.text = msg
                n--
                uiHandler.postDelayed(this, 1000)
            }
        }
        scoreCountdownRunnable = r
        uiHandler.post(r)
    }

    private fun cancelScoreCountdown() {
        scoreCountdownRunnable?.let { uiHandler.removeCallbacks(it) }
        scoreCountdownRunnable = null
        if (::scoreNextInfo.isInitialized) scoreNextInfo.visibility = View.GONE
    }

    /** 취소: 채점·저장 없이 녹음 파일을 버리고 검색 홈으로. */
    private fun cancelSong() {
        cancelScoreCountdown()
        scored = true   // onStop 자동 저장 방지
        val file = if (recorder.isRecording) recorder.stop() else lastRecording
        file?.let { runCatching { it.delete() } }
        lastRecording = null
        stopMediaPlayer()
        player.pause()
        goToSearch()
    }

    // 재생 불가(임베드 차단 등) 영상이면 같은 검색의 다음 후보로 자동으로 넘어간다.
    private fun onEmbedBlocked() {
        if (recorder.isRecording) recorder.stop()
        if (candIndex + 1 < candIds.size) {
            candIndex++
            currentVideoId = candIds[candIndex]
            songTitle.text = candTitles.getOrElse(candIndex) { "재생 중" }
            recStatus.text = "재생 불가 영상 — 다음 후보 시도 중…"
            replaying = false
            resetForNewSongKeepCandidates()
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

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(queuePoll)
        uiHandler.post(queuePoll)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(queuePoll)
        cancelScoreCountdown()   // 백그라운드로 가면 자동 넘김 중단
        // 정지·완곡을 안 누르고 화면을 벗어나도, 부르던 녹음은 최근 목록에 남긴다(채점 없이 저장).
        if (recorder.isRecording && recordStarted && !scored) {
            scored = true
            val file = recorder.stop()
            if (file != null && file.exists()) {
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
            }
        } else if (recorder.isRecording) {
            recorder.stop()
        }
        stopMediaPlayer()
        player.pause()   // 화면을 벗어나면 반주도 멈춤
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stop()
        stopMediaPlayer()
        uiHandler.removeCallbacks(replayTicker)
        uiHandler.removeCallbacks(queuePoll)
        cancelScoreCountdown()
        player.release()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/** 재생 화면 예약 목록: 순서·부르기·취소. */
private class ReserveQueueAdapter(
    val onPlay: (QueueItem) -> Unit,
    val onDelete: (QueueItem) -> Unit,
) : RecyclerView.Adapter<ReserveQueueAdapter.VH>() {

    private val items = ArrayList<QueueItem>()

    fun submit(list: List<QueueItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.q_title)
        val play: Button = v.findViewById(R.id.q_play)
        val delete: Button = v.findViewById(R.id.q_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_queue_side, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.title.text = "${position + 1}. ${item.title}"
        holder.play.setOnClickListener { onPlay(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }
}

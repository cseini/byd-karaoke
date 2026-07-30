package com.cseini.byd.karaoke

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cseini.byd.karaoke.audio.MixRecorder
import com.cseini.byd.karaoke.data.PlayHistoryStore
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.data.RecordingItem
import com.cseini.byd.karaoke.data.RecordingStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.Storage
import com.cseini.byd.karaoke.player.KaraokePlayer
import com.cseini.byd.karaoke.player.PlayerCallbacks
import com.cseini.byd.karaoke.player.StreamPlayer
import com.cseini.byd.karaoke.scoring.ScoringEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * (테스트 앱 전용) 재생을 MainActivity 창 '안'에서 처리 → 차량 런처가 분할화면을 유지.
 * PlaybackActivity 의 핵심(재생·녹음·채점·재생기록·예약·seek·전체화면·다시듣기)을 임베드로 담는다.
 */
@UnstableApi
class EmbeddedPlayer(
    private val activity: AppCompatActivity,
    private val settings: SettingsStore,
    private val recordings: RecordingStore,
    private val playHistory: PlayHistoryStore,
    private val onClose: () -> Unit = {},
) {
    private val queue = QueueStore(activity)
    private val ui = Handler(Looper.getMainLooper())

    private val overlay: View = activity.findViewById(R.id.embed_overlay)
    private val container: FrameLayout = activity.findViewById(R.id.embed_container)
    private val titleView: TextView = activity.findViewById(R.id.embed_title)
    private val statusView: TextView = activity.findViewById(R.id.embed_status)
    private val bottom: View = activity.findViewById(R.id.embed_bottom)
    private val seekRow: View = activity.findViewById(R.id.embed_seek_row)
    private val seek: SeekBar = activity.findViewById(R.id.embed_seek)
    private val timeView: TextView = activity.findViewById(R.id.embed_time)
    private val replayRow: View = activity.findViewById(R.id.embed_replay_row)
    private val replaySeek: SeekBar = activity.findViewById(R.id.embed_replay_seek)
    private val replayPlay: Button = activity.findViewById(R.id.embed_replay_play)
    private val stopBtn: Button = activity.findViewById(R.id.embed_stop)
    private val retryBtn: Button = activity.findViewById(R.id.embed_retry)
    private val replayBtn: Button = activity.findViewById(R.id.embed_replay)
    private val nextBtn: Button = activity.findViewById(R.id.embed_next)
    private val fullscreenBtn: Button = activity.findViewById(R.id.embed_fullscreen)
    private val fullscreenTap: View = activity.findViewById(R.id.embed_fullscreen_tap)
    private val queueSide: View = activity.findViewById(R.id.embed_queue_side)
    private val keyVal: TextView = activity.findViewById(R.id.embed_key_val)
    private val speedVal: TextView = activity.findViewById(R.id.embed_speed_val)
    private val scoreOverlay: View = activity.findViewById(R.id.embed_score)
    private val scoreNum: TextView = activity.findViewById(R.id.embed_score_num)
    private val scoreGrade: TextView = activity.findViewById(R.id.embed_score_grade)
    private val scoreDetail: TextView = activity.findViewById(R.id.embed_score_detail)
    private val scoreNextInfo: TextView = activity.findViewById(R.id.embed_score_next_info)

    private var player: KaraokePlayer? = null
    private var recorder: MixRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentVideoId = ""
    private var recordStarted = false
    private var scored = false
    private var playLogged = false
    private var replaying = false
    private var replayVideoAligned = false   // 다시듣기: 영상을 녹음 위치로 최초 1회 맞췄는지
    private var replaySeekCooldown = 0        // 재정렬 후 쿨다운(틱) — 잦은 seek 재버퍼링 방지
    private var fullscreen = false
    private var seekDragging = false
    private var keySemitones = 0        // 키(반음) -6~+6
    private var speedRate = 1.0f        // 속도 0.7~1.3
    private var lastRecording: File? = null
    private var countdown: Runnable? = null

    private val queueAdapter = EmbedQueueAdapter(
        onPlay = { playReserved(it) },
        onDelete = { queue.removeByVideoId(it.videoId); refreshQueueSide() },
    )

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    // 영상 탭(한 번) → 전체화면 토글(진입/해제).
    private val fsGesture = android.view.GestureDetector(
        activity,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { toggleFullscreen(); return true }
        },
    )

    init {
        activity.findViewById<RecyclerView>(R.id.embed_queue_list).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = queueAdapter
        }
        stopBtn.setOnClickListener { stopSong() }
        retryBtn.setOnClickListener { currentVideoId.takeIf { it.isNotBlank() }?.let { load(it, titleView.text.toString()) } }
        replayBtn.setOnClickListener { startReplay() }
        nextBtn.setOnClickListener { playNext() }
        activity.findViewById<Button>(R.id.embed_close).setOnClickListener { close() }
        // 점수 화면 빈 곳 탭 → 검색으로(임베드 닫기). 버튼은 각자 소비.
        scoreOverlay.setOnClickListener { close() }
        activity.findViewById<Button>(R.id.embed_score_retry).setOnClickListener {
            scoreOverlay.visibility = View.GONE; load(currentVideoId, titleView.text.toString())
        }
        activity.findViewById<Button>(R.id.embed_score_replay).setOnClickListener {
            scoreOverlay.visibility = View.GONE; startReplay()
        }
        activity.findViewById<Button>(R.id.embed_score_next).setOnClickListener {
            scoreOverlay.visibility = View.GONE; playNext()
        }
        // 키·속도 조절(재생 중 즉시 반영, 곡을 바꿔도 유지)
        activity.findViewById<Button>(R.id.embed_key_down).setOnClickListener { changeKey(-1) }
        activity.findViewById<Button>(R.id.embed_key_up).setOnClickListener { changeKey(+1) }
        activity.findViewById<Button>(R.id.embed_speed_down).setOnClickListener { changeSpeed(-0.05f) }
        activity.findViewById<Button>(R.id.embed_speed_up).setOnClickListener { changeSpeed(+0.05f) }
        activity.findViewById<Button>(R.id.embed_tune_reset).setOnClickListener {
            keySemitones = 0; speedRate = 1.0f; applyTune()
        }
        fullscreenBtn.setOnClickListener { toggleFullscreen() }
        // 영상 영역 탭으로 전체화면 진입, 전체화면 중엔 탭으로 해제.
        val fsTouch = View.OnTouchListener { _, e -> fsGesture.onTouchEvent(e) }
        container.setOnTouchListener(fsTouch)
        fullscreenTap.setOnTouchListener(fsTouch)
        replayPlay.setOnClickListener { toggleReplay() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) { seekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) { seekDragging = false; player?.seekTo(sb.progress.toLong()) }
        })
        replaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) {
                if (u) { mediaPlayer?.let { runCatching { it.seekTo(p) } }; player?.seekTo(p.toLong()) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    fun play(videoId: String, title: String) {
        overlay.visibility = View.VISIBLE
        load(videoId, title)
        ui.post(songTicker)
        ui.post(queuePoll)
    }

    private fun load(videoId: String, title: String) {
        cancelCountdown()
        stopMediaPlayer()
        player?.release()
        currentVideoId = videoId
        remoteMuted = false   // 새 곡은 항상 음소거 해제 상태로
        recordStarted = false; scored = false; playLogged = false; replaying = false
        lastRecording = null
        titleView.text = title
        statusView.text = "불러오는 중…"
        scoreOverlay.visibility = View.GONE
        replayRow.visibility = View.GONE
        replayBtn.visibility = View.GONE
        nextBtn.visibility = View.GONE
        seekRow.visibility = View.VISIBLE
        val rec = MixRecorder(activity, settings)
        recorder = rec
        val cb = PlayerCallbacks(
            onPlaying = { onPlaying() }, onEnded = { onEnded() }, onTime = { },
            onEmbedBlocked = { statusView.text = "재생 불가 영상입니다. 다른 곡으로 시도하세요." },
            onError = { msg -> if (rec.isRecording) rec.stop(); statusView.text = msg },
        )
        player = StreamPlayer(activity, container, activity.lifecycleScope, cb, rec.accompProcessor)
        // 키·속도는 '지금 부르는 곡'에만 적용 — 곡이 바뀌면 원래대로 초기화한다.
        keySemitones = 0
        speedRate = 1.0f
        applyTune()
        // 옵션: 새 곡을 항상 전체화면으로 시작(화면 탭으로 해제)
        if (settings.startFullscreen && !fullscreen) toggleFullscreen()
        player?.load(videoId)
    }

    // ── 키(반음)·속도 ──
    private fun changeKey(d: Int) { keySemitones = (keySemitones + d).coerceIn(-6, 6); applyTune() }
    private fun changeSpeed(d: Float) {
        speedRate = ((Math.round((speedRate + d) * 100) / 100f)).coerceIn(0.7f, 1.3f); applyTune()
    }

    /** 플레이어에 키·속도 반영 + 표시 갱신. 곡을 새로 불러올 때도 다시 적용한다. */
    private fun applyTune() {
        player?.setKeySpeed(keySemitones, speedRate)
        keyVal.text = if (keySemitones > 0) "+$keySemitones" else "$keySemitones"
        speedVal.text = "%.2f".format(speedRate).trimEnd('0').trimEnd('.') + "x"
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun onPlaying() {
        if (recordStarted || scored) return
        if (!playLogged) {
            playLogged = true
            playHistory.add(currentVideoId, titleView.text.toString(), System.currentTimeMillis())
        }
        if (!settings.recordingEnabled) { statusView.text = "🎵 재생 중 (녹음 꺼짐)"; return }
        if (!hasMic()) { statusView.text = "마이크 권한이 없어 재생만 합니다."; return }
        val dir = Storage.recordingsDir(activity, settings.storageMode)
        val safe = titleView.text.toString().replace(Regex("[^가-힣A-Za-z0-9]+"), "_").trim('_').take(30).ifEmpty { "노래" }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.KOREA).format(java.util.Date())
        val file = File(dir, "${safe}_${currentVideoId}_${ts}.wav")
        val err = recorder?.start(file, player?.currentPositionMs() ?: 0L, speedRate) { db ->
            activity.runOnUiThread { if (recorder?.isRecording == true) statusView.text = "🔴 녹음 중… ${"%.0f".format(db)} dBFS" }
        }
        if (err != null) statusView.text = "녹음 시작 실패: $err" else { recordStarted = true; lastRecording = file }
    }

    private fun onEnded() {
        if (scored) return
        if (!recordStarted) {
            if (!settings.recordingEnabled) {
                scored = true; statusView.text = "🎵 재생 완료"
                scheduleAfterSong()
            }
            return
        }
        scored = true
        val file = recorder?.stop()
        lastRecording = file
        if (file == null || !file.exists()) { statusView.text = "녹음 파일이 없습니다."; return }
        showPostSong()
        val rec = recorder
        if (!settings.scoringEnabled) {
            saveRecording(file, -1)
            statusView.text = "🎵 녹음 저장됨 — 다시듣기로 들어보세요"
            scheduleAfterSong()
            return
        }
        statusView.text = "채점 중…"
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val (s, sr) = rec!!.voiceForScoring()
                    val accomp = rec.accompForScoring()
                    ScoringEngine.score(s, sr, accomp?.first, accomp?.second ?: 0)
                }.getOrNull()
            }
            saveRecording(file, result?.total ?: -1)
            result?.let { playHistory.setScore(currentVideoId, it.total) }
            if (result == null) {
                statusView.text = "채점 실패 — 녹음은 저장됨"
            } else {
                statusView.text = "🎯 ${result.total}점 — 다시듣기로 들어보세요"
                showScore(result)
            }
            scheduleAfterSong()
        }
    }

    private fun showScore(result: ScoringEngine.Score) {
        scoreNum.text = "${result.total}"
        scoreGrade.text = when {
            result.total >= 95 -> "🏆 완벽한 무대!"
            result.total >= 88 -> "✨ 명 가수!"
            result.total >= 80 -> "🔥 열창!"
            else -> "👏 잘했어요!"
        }
        scoreDetail.text = result.breakdown.lines().drop(1).filterNot { it.startsWith("(") }.joinToString("\n")
        queue.reload()
        activity.findViewById<Button>(R.id.embed_score_next).visibility =
            if (queue.size() > 0) View.VISIBLE else View.GONE
        scoreOverlay.visibility = View.VISIBLE
    }

    private fun saveRecording(file: File, score: Int) {
        recordings.add(RecordingItem(file.absolutePath, currentVideoId, titleView.text.toString(), score, System.currentTimeMillis()))
        Storage.pruneToLimit(file.parentFile ?: file, settings.maxStorageBytes).let { if (it.isNotEmpty()) recordings.removeByPaths(it) }
    }

    /** 곡이 끝난 뒤: 다시듣기 버튼 노출, 예약 있으면 다음곡 버튼. */
    private fun showPostSong() {
        replayBtn.visibility = if (lastRecording != null) View.VISIBLE else View.GONE
        queue.reload()
        nextBtn.visibility = if (queue.size() > 0) View.VISIBLE else View.GONE
    }

    private fun stopSong() {
        cancelCountdown()
        if (replaying) { endReplay(); return }
        player?.pause()
        if (recordStarted && !scored) onEnded() else showPostSong()
    }

    private fun playNext() {
        val next = queue.pollFirst() ?: run { statusView.text = "예약된 곡이 없습니다"; return }
        load(next.videoId, next.title)
    }

    private fun playReserved(item: QueueItem) {
        queue.removeByVideoId(item.videoId)
        load(item.videoId, item.title)
    }

    // 곡 끝난 뒤: 예약 있으면 10초 뒤 다음곡, 없으면 10초 뒤 검색으로.
    private fun scheduleAfterSong() {
        queue.reload()
        val next = queue.peekFirst()
        if (next != null) startAutoAdvance(next) else startAutoAdvanceToSearch()
    }

    // ── 곡 끝나고 아무것도 안 누르면 10초 뒤 다음 예약곡 ──
    private fun startAutoAdvance(next: QueueItem) {
        cancelCountdown()
        val r = object : Runnable {
            var n = 10
            override fun run() {
                if (n <= 0) { countdown = null; playNext(); return }
                showCountdown("${n}초 후 다음 예약곡 ▶ '${next.title}'")
                n--; ui.postDelayed(this, 1000)
            }
        }
        countdown = r; ui.post(r)
    }

    // ── 예약이 없으면 10초 뒤 검색 화면으로 ──
    private fun startAutoAdvanceToSearch() {
        cancelCountdown()
        val r = object : Runnable {
            var n = 10
            override fun run() {
                if (n <= 0) { countdown = null; close(); return }
                showCountdown("${n}초 후 검색으로 돌아갑니다")
                n--; ui.postDelayed(this, 1000)
            }
        }
        countdown = r; ui.post(r)
    }

    /** 카운트다운은 상태줄과 채점 화면 양쪽에 — 채점 화면이 상태줄을 덮기 때문. */
    private fun showCountdown(text: String) {
        statusView.text = text
        scoreNextInfo.text = text
        scoreNextInfo.visibility = View.VISIBLE
    }

    private fun cancelCountdown() {
        countdown?.let { ui.removeCallbacks(it) }
        countdown = null
        scoreNextInfo.visibility = View.GONE
    }

    // ── 재생 위치 seek 바 갱신(실제 재생 중일 때만 — 정지 후 보간으로 계속 흐르는 것 방지) ──
    private val songTicker = object : Runnable {
        override fun run() {
            if (!fullscreen && !replaying && player?.isPlaying() == true) {
                val dur = player?.durationMs() ?: 0L
                if (dur > 0) {
                    val pos = (player?.currentPositionMs() ?: 0L).coerceIn(0, dur)
                    seek.max = dur.toInt()
                    if (!seekDragging) seek.progress = pos.toInt()
                    timeView.text = "${fmt(pos.toInt())} / ${fmt(dur.toInt())}"
                }
            }
            ui.postDelayed(this, 400)
        }
    }

    // ── 예약 목록(옆) 갱신 ──
    private val queuePoll = object : Runnable {
        override fun run() { refreshQueueSide(); ui.postDelayed(this, 2500) }
    }

    private fun refreshQueueSide() {
        if (fullscreen) { queueSide.visibility = View.GONE; return }
        queue.reload()
        val items = queue.all()
        queueAdapter.submit(items)
        queueSide.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    // ── 전체화면(패널·예약목록 숨겨 영상만) ──
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        bottom.visibility = if (fullscreen) View.GONE else View.VISIBLE
        fullscreenBtn.visibility = if (fullscreen) View.GONE else View.VISIBLE
        fullscreenTap.visibility = if (fullscreen) View.VISIBLE else View.GONE
        refreshQueueSide()
    }

    // ── 다시듣기: 영상은 음소거로 처음부터(화면), 소리는 녹음 믹스(MediaPlayer) ──
    private fun startReplay() {
        val file = lastRecording ?: run { statusView.text = "들려줄 녹음이 없습니다"; return }
        if (!file.exists()) { statusView.text = "녹음 파일이 없습니다"; return }
        cancelCountdown()   // 다시듣기 시작하면 자동 넘김/검색복귀 취소
        stopMediaPlayer()
        replaying = true
        replayVideoAligned = false; replaySeekCooldown = 0
        seekRow.visibility = View.GONE          // 노래 seek 바 숨김(진행바 2개 방지)
        player?.setVolume(0f)                    // 영상 음소거(소리는 녹음 믹스로)
        player?.seekTo(0)
        player?.play()
        mediaPlayer = MediaPlayer().apply {
            runCatching {
                setDataSource(file.absolutePath)
                setOnCompletionListener { endReplay() }
                prepare(); start()
                replaySeek.max = duration; replaySeek.progress = 0
                replayRow.visibility = View.VISIBLE
                replayPlay.text = "⏸ 정지"
                statusView.text = "▶ 내 노래 다시 듣는 중"
                ui.post(replayTicker)
            }.onFailure { statusView.text = "재생 실패: ${it.message}"; endReplay() }
        }
    }

    private fun endReplay() {
        ui.removeCallbacks(replayTicker)
        stopMediaPlayer()
        player?.pause()
        replaying = false
        replayRow.visibility = View.GONE
        replayPlay.text = "▶ 재생"
        seekRow.visibility = View.VISIBLE
    }

    private val replayTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            runCatching {
                val pos = mp.currentPosition
                replaySeek.progress = pos
                replayPlay.text = if (mp.isPlaying) "⏸ 정지" else "▶ 재생"
                // 영상(음소거)을 녹음 소리 위치에 맞춤: 매 틱 seek 하면 재버퍼링으로 끊기므로
                // 실제 재생이 시작되면 '한 번'만 정렬하고, 이후엔 크게 어긋날 때만(1.5초↑) 드물게 재정렬.
                if (mp.isPlaying && player?.isPlaying() == true) {
                    if (replaySeekCooldown > 0) replaySeekCooldown--
                    val drift = kotlin.math.abs((player?.currentPositionMs() ?: 0L) - pos)
                    if (!replayVideoAligned) {
                        player?.seekTo(pos.toLong()); replayVideoAligned = true; replaySeekCooldown = 20
                    } else if (drift > 1500 && replaySeekCooldown == 0) {
                        player?.seekTo(pos.toLong()); replaySeekCooldown = 20
                    }
                }
            }
            ui.postDelayed(this, 300)
        }
    }

    private fun toggleReplay() {
        val mp = mediaPlayer ?: return
        runCatching {
            if (mp.isPlaying) { mp.pause(); player?.pause(); replayPlay.text = "▶ 재생" }
            else { mp.start(); player?.play(); replayPlay.text = "⏸ 정지"; ui.post(replayTicker) }
        }
    }

    private fun stopMediaPlayer() {
        ui.removeCallbacks(replayTicker)
        mediaPlayer?.run { runCatching { if (isPlaying) stop() }; release() }
        mediaPlayer = null
    }

    private fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    // ── 마이크 버튼(리모컨) 제어: 재생 중일 때만 동작 ──
    /** 다음 예약곡으로. 예약이 없으면 아무것도 하지 않는다. */
    fun remoteNext() {
        if (!isShowing) return
        cancelCountdown()
        queue.reload()
        if (queue.size() > 0) playNext() else statusView.text = "예약된 곡이 없습니다"
    }

    /** 노래 종료(채점). 다시듣기 중이면 재생만 멈춘다. */
    fun remoteStop() {
        if (!isShowing) return
        stopSong()
    }

    /**
     * 뒤로(마이크 버튼 더블탭): 채점 화면이면 검색으로 닫고 true.
     * 노래 재생 중이면 실수로 곡이 꺼지지 않게 무시하고 true(소비만).
     */
    fun remoteBack(): Boolean {
        if (!isShowing) return false
        if (scoreOverlay.visibility == View.VISIBLE) close() // 채점 → 검색
        return true
    }

    /** 노래(채점 화면 아님)가 진행 중인지 — 매핑된 음성검색이 곡을 방해하지 않게 하는 판별용. */
    val isPlayingSong: Boolean get() = isShowing && scoreOverlay.visibility != View.VISIBLE

    private var remoteMuted = false

    /** 반주 음소거 토글(목소리 마이크는 그대로). 재생 중일 때만. */
    fun remoteMuteToggle() {
        if (!isPlayingSong) return
        remoteMuted = !remoteMuted
        player?.setVolume(if (remoteMuted) 0f else 1f)
        statusView.text = if (remoteMuted) "🔇 반주 음소거 (한 번 더 누르면 해제)" else ""
    }

    /** 일시정지/재생 토글. 재생 중일 때만. */
    fun remotePauseToggle() {
        if (!isPlayingSong) return
        if (player?.isPlaying() == true) player?.pause() else player?.play()
    }

    /** 녹음함/랭킹에서 저장된 녹음을 임베드로 다시 듣기(채점·녹음 없이, 영상은 음소거). */
    fun replayRecording(item: RecordingItem) {
        overlay.visibility = View.VISIBLE
        cancelCountdown()
        stopMediaPlayer()
        player?.release()
        currentVideoId = item.videoId
        titleView.text = item.title
        recordStarted = false; scored = true; playLogged = true; replaying = false; fullscreen = false
        lastRecording = File(item.path)
        scoreOverlay.visibility = View.GONE
        replayBtn.visibility = View.GONE
        nextBtn.visibility = View.GONE
        retryBtn.visibility = View.GONE
        seekRow.visibility = View.GONE
        bottom.visibility = View.VISIBLE
        fullscreenBtn.visibility = View.VISIBLE
        fullscreenTap.visibility = View.GONE
        queueSide.visibility = View.GONE
        val file = lastRecording
        if (file == null || !file.exists()) { statusView.text = "녹음 파일이 없습니다"; return }
        // 영상(음소거·가사)만 화면용으로, 소리는 저장된 믹스(MediaPlayer)로 재생.
        val cb = PlayerCallbacks(
            onPlaying = {}, onEnded = {}, onTime = {},
            onEmbedBlocked = { statusView.text = "▶ 저장된 노래 재생 (영상 없이)" },
            onError = { statusView.text = "▶ 저장된 노래 재생 (영상 없이)" },
        )
        player = StreamPlayer(activity, container, activity.lifecycleScope, cb, null, lowRes = true)
        keySemitones = 0; speedRate = 1.0f; applyTune()   // 저장된 녹음 재생은 원본 속도·키로
        player?.setVolume(0f)
        if (currentVideoId.isNotBlank()) player?.load(currentVideoId)
        replaying = true
        replayVideoAligned = false; replaySeekCooldown = 0
        mediaPlayer = MediaPlayer().apply {
            runCatching {
                setDataSource(file.absolutePath)
                setOnCompletionListener { endReplay() }
                prepare(); start()
                replaySeek.max = duration; replaySeek.progress = 0
                replayRow.visibility = View.VISIBLE
                replayPlay.text = "⏸ 정지"
                statusView.text = "▶ 저장된 노래 재생 중"
                ui.post(replayTicker)
            }.onFailure { statusView.text = "재생 실패: ${it.message}"; endReplay() }
        }
        ui.post(queuePoll)
    }

    fun close() {
        cancelCountdown()
        ui.removeCallbacks(songTicker); ui.removeCallbacks(queuePoll); ui.removeCallbacks(replayTicker)
        stopMediaPlayer()
        recorder?.let { if (it.isRecording) it.stop() }
        recorder = null
        player?.release(); player = null
        container.removeAllViews()
        scoreOverlay.visibility = View.GONE
        if (fullscreen) toggleFullscreen()
        overlay.visibility = View.GONE
        onClose()
    }
}

/** 임베드 예약 목록 어댑터(item_queue_side 재사용). */
private class EmbedQueueAdapter(
    val onPlay: (QueueItem) -> Unit,
    val onDelete: (QueueItem) -> Unit,
) : RecyclerView.Adapter<EmbedQueueAdapter.VH>() {
    private val items = ArrayList<QueueItem>()
    fun submit(list: List<QueueItem>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
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

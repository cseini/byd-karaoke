package com.cseini.byd.karaoke

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.cseini.byd.karaoke.audio.MixRecorder
import com.cseini.byd.karaoke.data.PlayHistoryStore
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
 * (테스트 앱 전용) 재생을 MainActivity 창 '안'에서 처리하는 임베드 플레이어.
 * 새 액티비티를 띄우지 않으므로 차량 런처가 분할화면을 풀스크린으로 밀어내지 못한다.
 * 재생 + 마이크 믹스 녹음 + 채점(목소리)까지 PlaybackActivity 의 핵심 루프를 담는다.
 */
@UnstableApi
class EmbeddedPlayer(
    private val activity: AppCompatActivity,
    private val settings: SettingsStore,
    private val recordings: RecordingStore,
    private val playHistory: PlayHistoryStore,
) {
    private val overlay: View = activity.findViewById(R.id.embed_overlay)
    private val container: FrameLayout = activity.findViewById(R.id.embed_container)
    private val titleView: TextView = activity.findViewById(R.id.embed_title)
    private val statusView: TextView = activity.findViewById(R.id.embed_status)

    private var player: KaraokePlayer? = null
    private var recorder: MixRecorder? = null
    private var currentVideoId = ""
    private var recordStarted = false
    private var scored = false
    private var playLogged = false

    val isShowing: Boolean get() = overlay.visibility == View.VISIBLE

    init {
        activity.findViewById<Button>(R.id.embed_stop).setOnClickListener { stopSong() }
        activity.findViewById<Button>(R.id.embed_close).setOnClickListener { close() }
    }

    fun play(videoId: String, title: String) {
        close()   // 이전 재생 정리
        currentVideoId = videoId
        recordStarted = false; scored = false; playLogged = false
        titleView.text = title
        statusView.text = "불러오는 중…"
        overlay.visibility = View.VISIBLE

        val rec = MixRecorder(activity, settings)
        recorder = rec
        val cb = PlayerCallbacks(
            onPlaying = { onPlaying() },
            onEnded = { onEnded() },
            onTime = { },
            onEmbedBlocked = { statusView.text = "재생 불가 영상입니다. 다른 곡으로 시도하세요." },
            onError = { msg -> if (rec.isRecording) rec.stop(); statusView.text = msg },
        )
        player = StreamPlayer(activity, container, activity.lifecycleScope, cb, rec.accompProcessor)
        player?.load(videoId)
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
        val startPos = player?.currentPositionMs() ?: 0L
        val err = recorder?.start(file, startPos) { db ->
            activity.runOnUiThread { if (recorder?.isRecording == true) statusView.text = "🔴 녹음 중… ${"%.0f".format(db)} dBFS" }
        }
        if (err != null) statusView.text = "녹음 시작 실패: $err" else recordStarted = true
    }

    private fun onEnded() {
        if (scored) return
        if (!recordStarted) {
            if (!settings.recordingEnabled) { scored = true; statusView.text = "🎵 재생 완료" }
            return
        }
        scored = true
        val file = recorder?.stop()
        if (file == null || !file.exists()) { statusView.text = "녹음 파일이 없습니다."; return }
        val rec = recorder
        if (!settings.scoringEnabled) {
            recordings.add(RecordingItem(file.absolutePath, currentVideoId, titleView.text.toString(), -1, System.currentTimeMillis()))
            Storage.pruneToLimit(file.parentFile ?: file, settings.maxStorageBytes).let { if (it.isNotEmpty()) recordings.removeByPaths(it) }
            statusView.text = "🎵 녹음 저장됨 — 녹음함에서 들을 수 있어요"
            return
        }
        statusView.text = "채점 중…"
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { val (s, sr) = rec!!.voiceForScoring(); ScoringEngine.score(s, sr) }.getOrNull()
            }
            recordings.add(RecordingItem(file.absolutePath, currentVideoId, titleView.text.toString(), result?.total ?: -1, System.currentTimeMillis()))
            Storage.pruneToLimit(file.parentFile ?: file, settings.maxStorageBytes).let { if (it.isNotEmpty()) recordings.removeByPaths(it) }
            result?.let { playHistory.setScore(currentVideoId, it.total) }
            statusView.text = if (result == null) "채점 실패 — 녹음은 저장됨"
                else "🎯 ${result.total}점 — 녹음함에서 다시 들을 수 있어요"
        }
    }

    private fun stopSong() {
        player?.pause()
        if (recordStarted && !scored) onEnded() else close()
    }

    fun close() {
        recorder?.let { if (it.isRecording) it.stop() }
        recorder = null
        player?.release()
        player = null
        container.removeAllViews()
        overlay.visibility = View.GONE
    }
}

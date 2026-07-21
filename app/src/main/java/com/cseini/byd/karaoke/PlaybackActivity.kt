package com.cseini.byd.karaoke

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cseini.byd.karaoke.audio.AudioRecorder
import com.cseini.byd.karaoke.audio.WavIo
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.scoring.ScoringEngine
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 재생 + 자동 녹음 + 채점 + 다시 듣기.
 * 곡이 PLAYING 되면 자동으로 마이크 녹음 시작 → ENDED 되면 녹음 중지·채점·점수 표시.
 */
class PlaybackActivity : AppCompatActivity() {

    companion object {
        fun intent(ctx: Context, videoId: String, title: String, fromQueue: Boolean) =
            Intent(ctx, PlaybackActivity::class.java).apply {
                putExtra("videoId", videoId)
                putExtra("title", title)
                putExtra("fromQueue", fromQueue)
            }
    }

    private lateinit var settings: SettingsStore
    private lateinit var queue: QueueStore
    private lateinit var recorder: AudioRecorder

    private lateinit var playerView: YouTubePlayerView
    private var player: YouTubePlayer? = null

    private lateinit var songTitle: TextView
    private lateinit var recStatus: TextView
    private lateinit var scorePanel: TextView
    private lateinit var btnReplay: Button

    private var currentVideoId = ""
    private var recordStarted = false
    private var scored = false
    private var lastRecording: File? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        settings = SettingsStore(this)
        queue = QueueStore(this)
        recorder = AudioRecorder(this, settings)

        songTitle = findViewById(R.id.song_title)
        recStatus = findViewById(R.id.rec_status)
        scorePanel = findViewById(R.id.score_panel)
        btnReplay = findViewById(R.id.btn_replay)

        currentVideoId = intent.getStringExtra("videoId").orEmpty()
        songTitle.text = intent.getStringExtra("title") ?: "재생 중"

        playerView = findViewById(R.id.youtube_player_view)
        lifecycle.addObserver(playerView)
        playerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                youTubePlayer.loadVideo(currentVideoId, 0f)
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> onSongPlaying()
                    PlayerConstants.PlayerState.ENDED -> onSongEnded()
                    else -> {}
                }
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                onPlayerError(error)
            }
        })

        btnReplay.setOnClickListener { replay() }
        findViewById<Button>(R.id.btn_retry).setOnClickListener { retry() }
        findViewById<Button>(R.id.btn_next).setOnClickListener { playNext() }
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun onSongPlaying() {
        if (recordStarted || scored) return
        if (!hasMic()) {
            recStatus.text = "마이크 권한이 없어 채점 없이 재생만 합니다."
            return
        }
        val file = File(getExternalFilesDir(null), "rec_${currentVideoId}_${System.currentTimeMillis()}.wav")
        val err = recorder.start(file) { db ->
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
        if (scored) return
        scored = true
        val file = recorder.stop()
        if (file == null || !file.exists()) {
            recStatus.text = "녹음 파일이 없어 채점을 건너뜁니다."
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
            if (result == null) {
                recStatus.text = "채점 실패(오디오를 읽지 못함)."
            } else {
                recStatus.text = "🎯 채점 완료"
                scorePanel.visibility = android.view.View.VISIBLE
                scorePanel.text = result.breakdown
                btnReplay.isEnabled = true
            }
        }
    }

    private fun replay() {
        val file = lastRecording
        if (file == null || !file.exists()) { toast("들려줄 녹음이 없습니다"); return }
        stopMediaPlayer()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                setOnCompletionListener { recStatus.text = "다시 듣기 완료" }
                prepare()
                start()
                recStatus.text = "▶ 내 노래 다시 듣는 중…"
            } catch (e: Exception) {
                toast("재생 실패: ${e.message}")
            }
        }
    }

    private fun retry() {
        stopMediaPlayer()
        if (recorder.isRecording) recorder.stop()
        resetForNewSong()
        player?.loadVideo(currentVideoId, 0f)
    }

    // videoEmbeddable=true 검색 필터로는 저작권자(예: TJ의 Ziller)가 Content ID로 건
    // 임베드 차단을 거를 수 없어, 차단 영상은 재생 시점에야 이 오류로 드러난다.
    private fun onPlayerError(error: PlayerConstants.PlayerError) {
        if (recorder.isRecording) recorder.stop()
        if (error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER) {
            toast("저작권자가 앱 내 재생을 차단한 영상입니다")
            val next = queue.pollFirst()
            if (next != null) {
                toast("다음 곡으로 넘어갑니다: ${next.title}")
                currentVideoId = next.videoId
                songTitle.text = next.title
                resetForNewSong()
                player?.loadVideo(currentVideoId, 0f)
            } else {
                recStatus.text = "이 영상은 앱 안에서 재생할 수 없습니다.\n검색에서 다른 반주 영상(금영 KY Karaoke 등)을 선택하세요."
            }
        } else {
            recStatus.text = "재생 오류: $error"
        }
    }

    private fun playNext() {
        stopMediaPlayer()
        if (recorder.isRecording) recorder.stop()
        val next = queue.pollFirst()
        if (next == null) { toast("대기열이 비었습니다"); return }
        currentVideoId = next.videoId
        songTitle.text = next.title
        resetForNewSong()
        player?.loadVideo(currentVideoId, 0f)
    }

    private fun resetForNewSong() {
        recordStarted = false
        scored = false
        lastRecording = null
        scorePanel.visibility = android.view.View.GONE
        btnReplay.isEnabled = false
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
    }

    override fun onDestroy() {
        if (recorder.isRecording) recorder.stop()
        stopMediaPlayer()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

package com.cseini.byd.karaoke.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamExtractor

/**
 * 오디오 체인: [Sonic(키·속도)] → [반주 탭]. 기본 체인과 달리 탭을 Sonic 뒤에 두어
 * 녹음/채점이 실제 들린 소리와 일치한다(키·속도 0/1.0 이면 Sonic 은 비활성 = 기존과 동일).
 */
@UnstableApi
private class ShiftThenTapChain(tap: AudioProcessor) : DefaultAudioSink.AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val processors = arrayOf(sonic, tap)
    override fun getAudioProcessors(): Array<AudioProcessor> = processors
    override fun applyPlaybackParameters(p: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(p.speed)
        sonic.setPitch(p.pitch)
        return p
    }
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false
    override fun getMediaDuration(playoutDuration: Long): Long = sonic.getMediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount(): Long = 0L
}

/**
 * 재생 방식 B — NewPipe 로 유튜브 스트림을 추출해 네이티브 ExoPlayer 로 직접 재생.
 * 광고·로그인 없음, 임베드 차단과 무관하게 공개 영상이면 재생된다.
 * 유튜브가 추출 방식을 바꾸면 NewPipeExtractor 버전 업데이트가 필요할 수 있다.
 */
@UnstableApi
class StreamPlayer(
    context: Context,
    container: FrameLayout,
    private val scope: CoroutineScope,
    private val cb: PlayerCallbacks,
    accompProcessor: AudioProcessor? = null,
    // 다시듣기 전용: 가사 화면만 필요하므로 가장 가벼운 저화질 영상만 재생(구형 헤드유닛 끊김 방지).
    private val lowRes: Boolean = false,
) : KaraokePlayer {

    private val playerView = PlayerView(context)
    private val exo = buildExo(context, accompProcessor)

    private fun buildExo(context: Context, proc: AudioProcessor?): ExoPlayer {
        if (proc == null) return ExoPlayer.Builder(context).build()
        // 반주 오디오를 합성 녹음(MixRecorder)에 넘기기 위해 오디오 처리 체인에 프로세서를 끼운다.
        // 키(피치)·속도 변경은 Sonic 이 처리하며, 우리 탭을 Sonic '뒤'에 두어
        // 녹음·채점이 실제로 들린 소리(키·속도 반영)와 같아지게 한다.
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(ShiftThenTapChain(proc))
                .build()
        }
        // 재생 시작 전 버퍼를 고정(2.5초)해 디코딩 앞섬(반주 지터)을 매번 일정하게 한다.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                2500,
                2500,
            )
            .build()
        return ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).build()
    }
    private val handler = Handler(Looper.getMainLooper())
    private var loadToken = 0

    // ExoPlayer 는 메인 스레드에서만 접근 가능하므로, 재생 위치를 여기서 캐시하고
    // 녹음 스레드(MixRecorder)는 캐시값+경과시간 보간으로 읽는다.
    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedAtNanos = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (exo.isPlaying) {
                cachedPositionMs = exo.currentPosition
                cachedAtNanos = System.nanoTime()
                cb.onTime(cachedPositionMs / 1000f)
            }
            handler.postDelayed(this, 200)
        }
    }

    init {
        YouTubeDownloader.ensureInit()
        // 노래 부를 땐 앱 버튼(정지 등)만 쓰므로 ExoPlayer 기본 재생 컨트롤(앞/뒤·멈춤)은 숨긴다.
        playerView.useController = false
        container.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        playerView.player = exo
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) cb.onPlaying()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                // 실제로 곡 끝까지 재생됐을 때만 종료 처리. 어라운드뷰(후진·컨시그널)나 외부
                // 오디오 개입으로 재생이 중간에 끊겨 ENDED 로 와도, 위치가 끝이 아니면 무시한다.
                val dur = exo.duration
                val pos = exo.currentPosition
                // logcat 은 헤드유닛에서 볼 수 없으므로 파일 로그(설정>이벤트 로그)에 남긴다.
                if (dur <= 0 || pos >= dur - 3000) {
                    com.cseini.byd.karaoke.CrashLog.event(context, "ENDED 처리 pos=$pos/$dur")
                    cb.onEnded()
                } else {
                    com.cseini.byd.karaoke.CrashLog.event(context, "가짜 ENDED 무시 pos=$pos/$dur")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                com.cseini.byd.karaoke.CrashLog.event(context, "재생오류 ${error.errorCodeName} pos=${exo.currentPosition}")
                cb.onError("재생 오류: ${error.errorCodeName}")
            }
        })
        handler.postDelayed(ticker, 500)
    }

    override fun load(videoId: String) {
        val token = ++loadToken
        scope.launch {
            val source = withContext(Dispatchers.IO) {
                runCatching { buildMediaSource(videoId) }.getOrNull()
            }
            if (token != loadToken) return@launch  // 그 사이 다른 곡으로 바뀌었으면 버린다
            if (source == null) {
                cb.onError("영상 스트림을 불러오지 못했습니다.\n네트워크를 확인하거나 다른 곡으로 시도해보세요.")
                return@launch
            }
            try {
                exo.setMediaSource(source)
                exo.prepare()
                exo.playWhenReady = true
            } catch (e: Exception) {
                cb.onError("재생 준비 실패: ${e.message}")
            }
        }
    }

    private fun buildMediaSource(videoId: String): MediaSource {
        val extractor: StreamExtractor =
            ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val dsf = DefaultHttpDataSource.Factory().setUserAgent(YouTubeDownloader.USER_AGENT)

        // 다시듣기(lowRes): 소리는 녹음 파일로 나가므로 오디오 트랙 없이 가장 낮은 화질 영상만.
        // 단일 트랙 progressive 라 병합(MergingMediaSource)·고화질 디코딩 부담이 없어 훨씬 부드럽다.
        if (lowRes) {
            val muxedLow = extractor.videoStreams
                .filter { it.content.isNotEmpty() && resolutionValue(it.resolution) > 0 }
                .minByOrNull { resolutionValue(it.resolution) }
            if (muxedLow != null) {
                return ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(muxedLow.content))
            }
            val videoLow = extractor.videoOnlyStreams
                .filter { it.content.isNotEmpty() && resolutionValue(it.resolution) > 0 }
                .minByOrNull { resolutionValue(it.resolution) }
            if (videoLow != null) {
                return ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(videoLow.content))
            }
            // 영상 스트림이 없으면 아래 일반 경로로 폴백
        }

        // 1) muxed(영상+소리 한 트랙)가 있으면 가장 화질 좋은 것으로 단순 재생
        val muxed = extractor.videoStreams
            .filter { it.content.isNotEmpty() }
            .maxByOrNull { resolutionValue(it.resolution) }
        if (muxed != null) {
            return ProgressiveMediaSource.Factory(dsf)
                .createMediaSource(MediaItem.fromUri(muxed.content))
        }
        // 2) 없으면 video-only + audio 를 합쳐 재생(요즘 유튜브 고화질은 대부분 이 경로)
        val video = extractor.videoOnlyStreams
            .filter { it.content.isNotEmpty() }
            .maxByOrNull { resolutionValue(it.resolution) }
        val audio = extractor.audioStreams
            .filter { it.content.isNotEmpty() }
            .maxByOrNull { it.averageBitrate }
        if (video != null && audio != null) {
            val v = ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(video.content))
            val a = ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(audio.content))
            return MergingMediaSource(v, a)
        }
        // 3) 소리라도 재생
        if (audio != null) {
            return ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(audio.content))
        }
        throw IllegalStateException("재생 가능한 스트림 없음")
    }

    /** "720p60" 같은 문자열에서 화질 숫자만 뽑아 비교용으로. */
    private fun resolutionValue(res: String?): Int =
        res?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    override fun pause() { exo.pause() }
    override fun play() { exo.play() }
    override fun setVolume(v: Float) { exo.volume = v }
    override fun seekTo(ms: Long) { runCatching { exo.seekTo(ms.coerceAtLeast(0)) } }
    override fun isPlaying(): Boolean = exo.isPlaying

    /** 키(반음)·속도 적용. 반음 n → 주파수비 2^(n/12). 속도는 음정에 영향 없음(Sonic 타임스트레치). */
    override fun setKeySpeed(semitones: Int, speed: Float) {
        val pitch = Math.pow(2.0, semitones / 12.0).toFloat()
        exo.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 1.5f), pitch)
    }
    override fun durationMs(): Long = exo.duration.let { if (it > 0) it else 0L }
    override fun currentPositionMs(): Long {
        if (cachedAtNanos == 0L) return cachedPositionMs
        val elapsed = (System.nanoTime() - cachedAtNanos) / 1_000_000L
        return cachedPositionMs + elapsed
    }

    override fun release() {
        loadToken++
        handler.removeCallbacks(ticker)
        runCatching { exo.release() }
    }
}

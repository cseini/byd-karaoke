package com.cseini.byd.karaoke.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
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
) : KaraokePlayer {

    private val playerView = PlayerView(context)
    private val exo = buildExo(context, accompProcessor)

    private fun buildExo(context: Context, proc: AudioProcessor?): ExoPlayer {
        if (proc == null) return ExoPlayer.Builder(context).build()
        // 반주 오디오를 합성 녹음(MixRecorder)에 넘기기 위해 오디오 처리 체인에 프로세서를 끼운다.
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(proc))
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

    private val ticker = object : Runnable {
        override fun run() {
            if (exo.isPlaying) cb.onTime(exo.currentPosition / 1000f)
            handler.postDelayed(this, 500)
        }
    }

    init {
        YouTubeDownloader.ensureInit()
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
                if (state == Player.STATE_ENDED) cb.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
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

    override fun release() {
        loadToken++
        handler.removeCallbacks(ticker)
        runCatching { exo.release() }
    }
}

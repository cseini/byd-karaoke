package com.cseini.byd.karaoke.player

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * 재생 방식 A — 유튜브 공식 IFrame 플레이어.
 * 안정적이지만 임베드 차단(embeddable=false) 영상은 재생 불가 → onEmbedBlocked 로 알려 다음 후보로 넘긴다.
 */
class IframePlayer(
    context: Context,
    container: FrameLayout,
    lifecycle: Lifecycle,
    private val cb: PlayerCallbacks,
) : KaraokePlayer {

    private val playerView = YouTubePlayerView(context)
    private var player: YouTubePlayer? = null
    private var pendingVideoId: String? = null

    init {
        container.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        lifecycle.addObserver(playerView)
        playerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                player = youTubePlayer
                pendingVideoId?.let { youTubePlayer.loadVideo(it, 0f); pendingVideoId = null }
            }

            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                when (state) {
                    PlayerConstants.PlayerState.PLAYING -> cb.onPlaying()
                    PlayerConstants.PlayerState.ENDED -> cb.onEnded()
                    else -> {}
                }
            }

            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                cb.onTime(second)
            }

            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                if (error == PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER) cb.onEmbedBlocked()
                else cb.onError("재생 오류: $error")
            }
        })
    }

    override fun load(videoId: String) {
        val p = player
        if (p != null) p.loadVideo(videoId, 0f) else pendingVideoId = videoId
    }

    override fun pause() { player?.pause() }
    override fun play() { player?.play() }
    override fun release() { runCatching { playerView.release() } }
}

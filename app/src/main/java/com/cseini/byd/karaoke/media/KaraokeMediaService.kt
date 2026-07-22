package com.cseini.byd.karaoke.media

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.cseini.byd.karaoke.MainActivity
import com.cseini.byd.karaoke.data.RecordingStore
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 차량 런처(Kinex 등)의 미디어 위젯에 노래방 앱을 "미디어 앱"으로 노출하기 위한
 * MediaLibraryService. 브라우징 트리로 최근 부른 곡을 보여준다.
 * 1단계: 위젯 노출 검증. 재생 상태 완전 연동(player 서비스 이전)은 2단계에서.
 */
@UnstableApi
class KaraokeMediaService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        session.release()
        player.release()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("노래방")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val recs = RecordingStore(this@KaraokeMediaService).all()
            val items = recs.map { rec ->
                MediaItem.Builder()
                    .setMediaId(rec.videoId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(rec.title)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build(),
                    )
                    .build()
            }
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params),
            )
        }
    }

    companion object {
        private const val ROOT_ID = "root"
    }
}

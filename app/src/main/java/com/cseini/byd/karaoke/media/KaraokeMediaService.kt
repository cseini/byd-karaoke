package com.cseini.byd.karaoke.media

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.cseini.byd.karaoke.data.RecordingStore

/**
 * 차량 런처(Kinex 등)의 미디어 위젯에 노래방 앱을 "미디어 앱"으로 노출하기 위한
 * 레거시 MediaBrowserServiceCompat. 구형 런처가 스캔하는 android.media.browse.MediaBrowserService
 * 표준을 그대로 구현한다. 브라우징 트리로 최근 부른 곡을 보여준다.
 */
class KaraokeMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "Karaoke").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        sessionToken = session.sessionToken
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(parentId: String, result: Result<List<MediaBrowserCompat.MediaItem>>) {
        val items = RecordingStore(this).all().map { rec ->
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(rec.videoId)
                .setTitle(rec.title)
                .setSubtitle(if (rec.score >= 0) "${rec.score}점" else null)
                .build()
            MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }
        result.sendResult(items)
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }

    companion object {
        private const val ROOT_ID = "root"
    }
}

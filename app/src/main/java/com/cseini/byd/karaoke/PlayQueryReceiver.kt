package com.cseini.byd.karaoke

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.youtube.YouTubeRepository
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 음성 비서(릴레이)가 "○○ 노래방에서 불러 줘"를 곡명·가수로 넘기는 외부 호출 입구.
 *   am broadcast -n com.cseini.byd.karaoke/.PlayQueryReceiver -a com.cseini.byd.karaoke.action.PLAY_QUERY -e query "곡명" -e artist "가수"
 * 앱 안 노래방 검색(반주 필터 적용)의 가장 유력한 한 곡을 찾아 노래방 화면을 앞으로 띄우고 바로 시작한다(채점·마이크가 화면에서 돈다).
 * 순서 있는 방송이라 결과를 돌려준다 — 데이터 {"ok":true,"title":"…"} / {"ok":false,"error":"…"}, 결과 코드 -1(성공)·0(실패).
 * 앱 프로세스가 죽어 있으면 차가 방송을 전달하지 않으므로 먼저 `am start -W -n com.cseini.byd.karaoke/.WakeActivity` 로 깨운다.
 */
class PlayQueryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PLAY_QUERY) return
        val app = context.applicationContext
        val query = intent.getStringExtra("query")?.trim().orEmpty()
        val artist = intent.getStringExtra("artist")?.trim().orEmpty()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching { withTimeoutOrNull(TIMEOUT_MS) { handle(app, query, artist) } ?: fail("시간이 초과됐습니다") }
                .getOrElse { fail("${it::class.java.simpleName}: ${it.message?.take(80)}") }
            CrashLog.event(app, "외부 호출 PLAY_QUERY 곡='${query.take(30)}' 가수='${artist.take(20)}' → $result")
            pending.setResultCode(if (result.get("ok").asBoolean) Activity.RESULT_OK else Activity.RESULT_CANCELED)
            pending.setResultData(result.toString())
            pending.finish()
        }
    }

    private suspend fun handle(ctx: Context, query: String, artist: String): JsonObject {
        val settings = SettingsStore(ctx)
        if (!settings.externalVoice) return fail("외부 음성 호출 허용이 꺼져 있습니다(설정)")
        if (query.isEmpty()) return fail("곡명이 필요합니다")
        if (query.length > MAX_LEN || artist.length > MAX_LEN) return fail("검색어가 너무 깁니다")
        if (!settings.keylessSearch && !settings.hasApiKey()) return fail("검색 설정이 없습니다(API 키 또는 '키 없이 검색')")
        val r = YouTubeRepository().search(buildQuery(query, artist), settings.youtubeApiKey, System.currentTimeMillis(), settings.keylessSearch, general = false)
        val item = when (r) {
            is YouTubeRepository.Result.Ok -> r.items.firstOrNull() ?: return fail("반주를 찾지 못했습니다")
            is YouTubeRepository.Result.Error -> return fail(r.message)
        }
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java).setAction(ACTION_PLAY_ITEM)
                    .putExtra("videoId", item.videoId).putExtra("title", item.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (e: Exception) {
            return fail("화면을 앞으로 가져오지 못했습니다(백그라운드 제한?): ${e::class.java.simpleName}: ${e.message?.take(80)}")
        }
        return JsonObject().apply { addProperty("ok", true); addProperty("title", item.title) }
    }

    companion object {
        const val ACTION_PLAY_QUERY = "com.cseini.byd.karaoke.action.PLAY_QUERY"
        /** 수신기가 고른 곡을 MainActivity 에 넘기는 내부 액션 — extras videoId·title. */
        const val ACTION_PLAY_ITEM = "com.cseini.byd.karaoke.action.PLAY_ITEM"
        private const val TIMEOUT_MS = 15_000L
        private const val MAX_LEN = 80

        internal fun fail(msg: String) = JsonObject().apply { addProperty("ok", false); addProperty("error", msg) }

        /** 가수가 있으면 "가수 곡명" — 검색 쪽이 뒤에 '노래방'을 붙이고 반주 채널 필터를 적용한다. */
        internal fun buildQuery(query: String, artist: String?): String =
            listOfNotNull(artist?.trim()?.takeIf { it.isNotEmpty() }, query.trim()).joinToString(" ")
    }
}

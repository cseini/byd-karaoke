package com.cseini.byd.karaoke.data.youtube

import com.cseini.byd.karaoke.data.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * YouTube 검색 저장소. 동일 검색어 5분 캐시로 quota(검색 100회/일) 절약.
 * "금영 노래방 " 접두어 + 금영(KY) 결과만 노출 — TJ는 저작권자(Ziller-TJ)가
 * 임베드 재생을 차단해 앱 안에서 재생이 안 되기 때문.
 */
class YouTubeRepository(private val api: YouTubeApi = YouTubeApi.create()) {

    sealed class Result {
        data class Ok(val items: List<QueueItem>) : Result()
        data class Error(val message: String) : Result()
    }

    private fun isKumyoung(item: QueueItem): Boolean {
        val s = "${item.channel} ${item.title}".lowercase()
        return "금영" in s || "ky karaoke" in s || "(ky." in s ||
            "ky 노래방" in s || "ky노래방" in s
    }

    private data class Cached(val at: Long, val items: List<QueueItem>)
    private val cache = HashMap<String, Cached>()
    private val ttlMs = 5 * 60 * 1000L

    suspend fun search(rawQuery: String, apiKey: String, nowMs: Long): Result {
        val q = rawQuery.trim()
        if (q.isEmpty()) return Result.Error("검색어를 입력하세요")
        if (apiKey.isBlank()) return Result.Error("YouTube API 키가 설정되지 않았습니다 (설정에서 입력)")

        val effective = if (q.contains("금영") || q.contains("ky", ignoreCase = true)) q else "금영 노래방 $q"
        cache[effective]?.let { if (nowMs - it.at < ttlMs) return Result.Ok(it.items) }

        return withContext(Dispatchers.IO) {
            try {
                val resp = api.search(query = effective, apiKey = apiKey)
                val items = resp.items.mapNotNull { it ->
                    val vid = it.id?.videoId ?: return@mapNotNull null
                    QueueItem(
                        videoId = vid,
                        title = it.snippet?.title ?: "(제목 없음)",
                        channel = it.snippet?.channelTitle ?: "",
                    )
                }.filter { isKumyoung(it) }
                if (items.isEmpty()) {
                    Result.Error("금영(KY) 반주를 찾지 못했습니다 — 곡명이나 가수명을 바꿔보세요")
                } else {
                    cache[effective] = Cached(nowMs, items)
                    Result.Ok(items)
                }
            } catch (e: retrofit2.HttpException) {
                val code = e.code()
                val hint = when (code) {
                    403 -> "403 — API 키 권한 또는 할당량 초과일 수 있습니다"
                    400 -> "400 — API 키가 올바른지 확인하세요"
                    else -> "$code"
                }
                Result.Error("검색 실패: $hint")
            } catch (e: Exception) {
                Result.Error("검색 실패: ${e.message ?: "네트워크 오류"}")
            }
        }
    }
}

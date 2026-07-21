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

    /** 공백·기호 제거 후 소문자화 — 오타·띄어쓰기 차이를 흡수하고 비교하기 위함. */
    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9가-힣]"), "")

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private data class Cached(val at: Long, val items: List<QueueItem>)
    private val cache = HashMap<String, Cached>()
    private val ttlMs = 5 * 60 * 1000L

    /** 원 쿼리(오타 포함 가능)와 결과 제목의 유사도로 오타 허용 정렬. */
    private fun sortBySimilarity(items: List<QueueItem>, query: String): List<QueueItem> {
        val nq = normalize(query)
        return items.sortedBy { levenshtein(normalize(it.title), nq) }
    }

    private suspend fun rawSearch(query: String, apiKey: String, keyless: Boolean): List<QueueItem> =
        if (keyless) {
            YouTubeScraper.search(query)
        } else {
            api.search(query = query, apiKey = apiKey).items.mapNotNull {
                val vid = it.id?.videoId ?: return@mapNotNull null
                QueueItem(
                    videoId = vid,
                    title = it.snippet?.title ?: "(제목 없음)",
                    channel = it.snippet?.channelTitle ?: "",
                )
            }
        }

    /** keyless=true 면 API 키 없이 검색 결과 페이지를 파싱한다. */
    suspend fun search(rawQuery: String, apiKey: String, nowMs: Long, keyless: Boolean): Result {
        val q = rawQuery.trim()
        if (q.isEmpty()) return Result.Error("검색어를 입력하세요")
        if (!keyless && apiKey.isBlank())
            return Result.Error("API 키가 없습니다 — [설정]에서 키를 넣거나 '키 없이 검색'을 켜세요")

        val cacheKey = "${if (keyless) "k" else "a"}:${normalize(q)}"
        cache[cacheKey]?.let { if (nowMs - it.at < ttlMs) return Result.Ok(it.items) }

        val effective = if (q.contains("금영") || q.contains("ky", ignoreCase = true)) q else "금영 노래방 $q"

        return withContext(Dispatchers.IO) {
            try {
                val primary = rawSearch(effective, apiKey, keyless).filter { isKumyoung(it) }

                // 정확 검색 결과가 적으면(오타 등으로 못 찾았을 가능성) 원 검색어로 한 번 더 찾아
                // 금영 채널 결과만 골라 유사도순으로 보충한다.
                val merged = if (primary.size >= 5) {
                    primary
                } else {
                    val fallback = runCatching { rawSearch(q, apiKey, keyless) }.getOrDefault(emptyList())
                        .filter { isKumyoung(it) }
                        .filterNot { fb -> primary.any { it.videoId == fb.videoId } }
                    primary + sortBySimilarity(fallback, q)
                }

                if (merged.isEmpty()) {
                    Result.Error("금영(KY) 반주를 찾지 못했습니다 — 곡명이나 가수명을 바꿔보세요")
                } else {
                    cache[cacheKey] = Cached(nowMs, merged)
                    Result.Ok(merged)
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

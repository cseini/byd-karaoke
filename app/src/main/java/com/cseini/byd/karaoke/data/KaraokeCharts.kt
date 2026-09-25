package com.cseini.byd.karaoke.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * "최신곡"·"장르별 인기차트" 버튼 — 실제 노래방 기기처럼 목록에서 골라 부를 수 있게.
 * 곡 목록만 가져오고(제목·가수), 재생은 기존 검색("$제목 $가수 노래방")으로 반주를 찾는다
 * — 유튜브 검색 쿼터가 하루 100회뿐이라 목록 단계에서 미리 다 매칭해두지 않는다.
 */
object KaraokeCharts {
    enum class Source(val label: String, val classCd: String?) {
        POPULAR("인기차트", null),   // 우리 사용자들이 실제로 많이 부른 곡(서버 집계, videoId 확정)
        NEW("최신곡", null),        // 멜론 발매 신곡
        BALLAD("발라드", "GN0100"),
        DANCE("댄스", "GN0200"),
        TROT("트로트", "GN0700"),
        HIPHOP("힙합", "GN0300"),
        POP("팝송", "GN0900"),
        OST("OST", "GN1500"),
    }

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    suspend fun list(src: Source): List<QueueItem> = withContext(Dispatchers.IO) {
        runCatching {
            when (src) {
                Source.POPULAR -> popular()
                Source.NEW -> melonChart("https://www.melon.com/new/index.htm")
                else -> melonChart("https://www.melon.com/chart/day/index.htm?classCd=${src.classCd}")
            }
        }.getOrDefault(emptyList())
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 6000; c.readTimeout = 9000
        c.instanceFollowRedirects = true
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** 멜론 차트/신곡 페이지 공용 파싱 — TOP100·장르차트·신곡 전부 같은 DOM 구조. */
    private fun melonChart(url: String): List<QueueItem> {
        val h = get(url)
        val songs = Regex("title=\"(.+?) 재생\"").findAll(h).map { it.groupValues[1] }.toList()
        val artists = Regex("class=\"ellipsis rank02\">.*?<a[^>]*>([^<]+)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(h).map { it.groupValues[1].trim() }.toList()
        // videoId 는 비워둔다 — 목록에서 고르면 그때 검색으로 반주를 찾는다(MainActivity 에서 처리).
        return songs.mapIndexed { i, s -> QueueItem("", clean(s), clean(artists.getOrElse(i) { "" })) }
    }

    /** 실사용 인기차트 — karaoke.usenu.kr 서버 집계(누가 실제로 불렀는지). videoId 가 이미 확정돼 바로 재생 가능. */
    private fun popular(): List<QueueItem> {
        val body = get("https://karaoke.usenu.kr/api/song-ranking?limit=50")
        val arr = JsonParser.parseString(body).asJsonArray
        return arr.mapNotNull { e ->
            val o = e.asJsonObject
            val vid = o.get("video_id")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
            QueueItem(vid, title, "")
        }
    }

    private fun clean(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(' ', ' ')
        .replace(Regex("\\s+"), " ").trim()
}

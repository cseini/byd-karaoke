package com.cseini.byd.karaoke.data.youtube

import com.cseini.byd.karaoke.data.QueueItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * API 키 없이 유튜브 검색.
 * 검색 결과 페이지 HTML 에 박혀 있는 `ytInitialData` JSON 을 파싱해 videoRenderer 를 읽는다
 * (yt-dlp·NewPipe 와 같은 방식). 키 발급이 필요 없지만 유튜브가 페이지 구조를 바꾸면
 * 깨질 수 있어, 실패하면 빈 목록을 돌려준다.
 */
object YouTubeScraper {

    private val client = OkHttpClient()
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun search(query: String): List<QueueItem> {
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(query, "UTF-8") + "&hl=ko&gl=KR"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ko-KR,ko;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return emptyList()
            val json = extractInitialData(body) ?: return emptyList()
            return parseVideos(json)
        }
    }

    private fun extractInitialData(html: String): JSONObject? {
        val markers = listOf("var ytInitialData =", "window[\"ytInitialData\"] =", "ytInitialData =")
        for (m in markers) {
            val at = html.indexOf(m)
            if (at < 0) continue
            val start = html.indexOf('{', at)
            if (start < 0) continue
            val end = matchBrace(html, start) ?: continue
            val obj = runCatching { JSONObject(html.substring(start, end + 1)) }.getOrNull()
            if (obj != null) return obj
        }
        return null
    }

    /** open 위치의 '{' 와 짝이 맞는 '}' 인덱스를 문자열 리터럴을 건너뛰며 찾는다. */
    private fun matchBrace(s: String, open: Int): Int? {
        var depth = 0
        var inStr = false
        var esc = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    private fun parseVideos(root: JSONObject): List<QueueItem> {
        val out = ArrayList<QueueItem>()
        val sections = root
            .optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return out
        for (i in 0 until sections.length()) {
            val items = sections.optJSONObject(i)
                ?.optJSONObject("itemSectionRenderer")
                ?.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val vr = items.optJSONObject(j)?.optJSONObject("videoRenderer") ?: continue
                val vid = vr.optString("videoId", "")
                if (vid.isEmpty()) continue
                val title = firstRunText(vr.optJSONObject("title"))
                val channel = firstRunText(vr.optJSONObject("ownerText"))
                    .ifEmpty { firstRunText(vr.optJSONObject("longBylineText")) }
                out.add(QueueItem(vid, title.ifEmpty { "(제목 없음)" }, channel))
            }
        }
        return out
    }

    private fun firstRunText(o: JSONObject?): String {
        if (o == null) return ""
        val runs = o.optJSONArray("runs")
        if (runs != null && runs.length() > 0) return runs.optJSONObject(0)?.optString("text", "") ?: ""
        return o.optString("simpleText", "")
    }
}

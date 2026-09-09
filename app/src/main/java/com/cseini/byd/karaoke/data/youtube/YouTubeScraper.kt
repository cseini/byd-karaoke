package com.cseini.byd.karaoke.data.youtube

import com.cseini.byd.karaoke.data.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * API 키 없이 유튜브 검색.
 * 검색 결과 페이지 HTML 에 박혀 있는 `ytInitialData` JSON 을 파싱해 videoRenderer 를 읽는다
 * (yt-dlp·NewPipe 와 같은 방식). 키 발급이 필요 없지만 유튜브가 페이지 구조를 바꾸면
 * 깨질 수 있어, 실패하면 빈 목록을 돌려준다.
 */
object YouTubeScraper {

    // 차량은 폰 핫스팟을 타는 일이 많아 무한정 기다리면 화면이 멎은 것처럼 보인다.
    // 기본 OkHttpClient 는 전체 호출 상한이 없어 여기서 못 박는다.
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // 마지막 검색의 소요·크기 — '네트워크가 느린 건지 파싱이 느린 건지'를 실차에서 가리기 위한 계측.
    @Volatile
    var lastFetchMs = 0L
        private set

    @Volatile
    var lastParseMs = 0L
        private set

    @Volatile
    var lastKb = 0
        private set

    /**
     * 결과 페이지를 받아 파싱한다.
     * 코루틴이 취소되면(타이핑 디바운스로 다음 검색이 뜨는 등) 진행 중인 HTTP 요청도 바로 끊는다.
     * 블로킹 execute() 는 취소로 중단되지 않아, 버려진 요청이 계속 회선을 먹으면
     * 정작 사용자가 누른 검색이 그 뒤로 밀린다.
     */
    suspend fun search(query: String): List<QueueItem> = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(query, "UTF-8") + "&hl=ko&gl=KR"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "ko-KR,ko;q=0.9")
            .build()
        val call = client.newCall(req)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            val t0 = System.nanoTime()
            call.execute().use { resp ->
                val body = resp.body?.string()
                lastFetchMs = (System.nanoTime() - t0) / 1_000_000
                lastKb = (body?.length ?: 0) / 1024
                if (body == null) return@withContext emptyList()
                val t1 = System.nanoTime()
                val json = extractInitialData(body)
                val items = if (json == null) emptyList() else parseVideos(json)
                lastParseMs = (System.nanoTime() - t1) / 1_000_000
                items
            }
        } finally {
            cancelHandle?.dispose()
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
        val seen = HashSet<String>()
        // 1) 정규 경로(관련도순 상위)
        val sections = root
            .optJSONObject("contents")
            ?.optJSONObject("twoColumnSearchResultsRenderer")
            ?.optJSONObject("primaryContents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val items = sections.optJSONObject(i)
                    ?.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: continue
                for (j in 0 until items.length()) {
                    addVideoRenderer(items.optJSONObject(j)?.optJSONObject("videoRenderer"), out, seen)
                }
            }
        }
        // 2) 쇼츠 shelf·richItem 등 중첩에 묻힌 영상도 재귀로 보충(일반 검색은 상당수가 여기 있음)
        collectDeep(root, out, seen)
        return out
    }

    private fun addVideoRenderer(vr: JSONObject?, out: MutableList<QueueItem>, seen: MutableSet<String>) {
        if (vr == null) return
        val vid = vr.optString("videoId", "")
        if (vid.isEmpty() || !seen.add(vid)) return
        val title = firstRunText(vr.optJSONObject("title"))
        val channel = firstRunText(vr.optJSONObject("ownerText"))
            .ifEmpty { firstRunText(vr.optJSONObject("longBylineText")) }
        out.add(QueueItem(vid, title.ifEmpty { "(제목 없음)" }, channel))
    }

    /** ytInitialData 전체를 훑어 아직 안 담은 videoRenderer 를 순서대로 추가. */
    private fun collectDeep(node: Any?, out: MutableList<QueueItem>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                addVideoRenderer(node.optJSONObject("videoRenderer"), out, seen)
                val keys = node.keys()
                while (keys.hasNext()) collectDeep(node.opt(keys.next()), out, seen)
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) collectDeep(node.opt(i), out, seen)
        }
    }

    private fun firstRunText(o: JSONObject?): String {
        if (o == null) return ""
        val runs = o.optJSONArray("runs")
        if (runs != null && runs.length() > 0) return runs.optJSONObject(0)?.optString("text", "") ?: ""
        return o.optString("simpleText", "")
    }
}

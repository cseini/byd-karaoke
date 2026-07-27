package com.cseini.byd.karaoke.share

import android.content.Context
import com.cseini.byd.karaoke.data.QueueItem
import com.cseini.byd.karaoke.data.QueueStore
import com.cseini.byd.karaoke.data.SettingsStore
import com.cseini.byd.karaoke.data.youtube.YouTubeRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 폰(리모컨)용 예약 서버. 승객 휴대폰이 같은 네트워크(핫스팟)에서 브라우저로 접속해
 * 노래를 검색하고 예약하면 차량 대기열(QueueStore)에 쌓인다. 앱이 켜져 있는 동안 상시 동작.
 */
object ReserveServer {

    /**
     * 폰/태블릿이 프록시를 쓰면 브라우저가 절대 URL("http://ip:port/search")로 요청 줄을 보낸다.
     * 그대로 두면 "/search" 매칭이 실패해 HTML 페이지가 반환되고, 폰에서 JSON 파싱 오류
     * (Unexpected token '<')가 난다. 경로만 남기도록 정규화한다.
     */
    internal fun normalizePath(uri: String): String {
        val u = uri.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return u
        val afterScheme = u.indexOf("//").let { if (it >= 0) it + 2 else 0 }
        val slash = u.indexOf('/', afterScheme)
        return if (slash >= 0) u.substring(slash) else "/"
    }

    private var server: Http? = null
    var url: String? = null
        private set

    fun isRunning(): Boolean = server != null

    /** 서버 시작. 성공하면 접속 URL, 네트워크가 없으면 null. */
    fun start(context: Context): String? {
        if (server != null) return url
        val ip = localIpAddress() ?: return null
        val app = context.applicationContext
        for (port in intArrayOf(8080, 8081, 8090)) {
            val s = Http(app, port)
            // 검색은 차가 대신 수행하므로 기본 5초(SOCKET_READ_TIMEOUT)로는 부족하다.
            // 차 네트워크가 느리면 연결이 끊겨 폰에 "검색 실패"만 뜨므로 넉넉히 잡는다.
            if (runCatching { s.start(60_000, false) }.isSuccess) {
                server = s
                url = "http://$ip:$port/"
                return url
            }
        }
        return null
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        url = null
    }

    private class Http(private val ctx: Context, port: Int) : NanoHTTPD(port) {
        private val queue = QueueStore(ctx)
        private val repo = YouTubeRepository()
        private val settings = SettingsStore(ctx)

        override fun serve(session: IHTTPSession): Response {
            val uri = normalizePath(session.uri)
            val p = session.parameters
            fun q(k: String): String = p[k]?.firstOrNull()?.trim().orEmpty()
            return when {
                uri.startsWith("/search") -> handleSearch(q("q"))
                uri.startsWith("/reserve") -> handleReserve(q("videoId"), q("title"), q("channel"))
                uri.startsWith("/cancel") -> handleCancel(q("videoId"))
                uri.startsWith("/queue") -> handleQueue()
                else -> json(Response.Status.OK, "text/html; charset=utf-8", PAGE)
            }
        }

        private fun handleSearch(query: String): Response {
            if (query.isBlank()) return jsonBody("{\"items\":[]}")
            // 실패해도 반드시 JSON 으로 이유를 돌려준다(폰에 "검색 실패"만 뜨지 않도록).
            val result = runCatching {
                runBlocking {
                    repo.search(query, settings.youtubeApiKey, System.currentTimeMillis(), settings.keylessSearch)
                }
            }.getOrElse { e ->
                return jsonBody(
                    JSONObject().put("items", JSONArray())
                        .put("error", "차에서 검색 실패: ${e.message ?: e::class.java.simpleName}").toString()
                )
            }
            val arr = JSONArray()
            val obj = JSONObject()
            when (result) {
                is YouTubeRepository.Result.Ok -> result.items.take(20).forEach {
                    arr.put(JSONObject().put("videoId", it.videoId).put("title", it.title).put("channel", it.channel))
                }
                is YouTubeRepository.Result.Error -> obj.put("error", "차에서 검색 실패: ${result.message}")
            }
            return jsonBody(obj.put("items", arr).toString())
        }

        private fun handleReserve(videoId: String, title: String, channel: String): Response {
            if (videoId.isBlank()) return jsonBody("{\"ok\":false}")
            queue.add(QueueItem(videoId, title.ifBlank { "예약곡" }, channel))
            return jsonBody("{\"ok\":true}")
        }

        private fun handleCancel(videoId: String): Response {
            if (videoId.isNotBlank()) queue.removeByVideoId(videoId)
            return jsonBody("{\"ok\":true}")
        }

        private fun handleQueue(): Response {
            queue.reload()
            val arr = JSONArray()
            queue.all().forEach { arr.put(JSONObject().put("videoId", it.videoId).put("title", it.title)) }
            return jsonBody(JSONObject().put("items", arr).toString())
        }

        private fun jsonBody(s: String) = json(Response.Status.OK, "application/json; charset=utf-8", s)
        private fun json(status: Response.Status, mime: String, body: String): Response =
            newFixedLengthResponse(status, mime, body).apply {
                addHeader("Access-Control-Allow-Origin", "*")
            }
    }

    // 폰 브라우저용 예약 페이지 (자체 완결 HTML/CSS/JS)
    private val PAGE = """
<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BYD YT노래방 예약</title>
<style>
 body{font-family:-apple-system,sans-serif;margin:0;background:#0b0b16;color:#eee}
 header{padding:16px;background:#12122a;font-size:20px;font-weight:bold;color:#41e0ff;position:sticky;top:0}
 .wrap{padding:14px}
 .row{display:flex;gap:8px;margin-bottom:12px}
 input{flex:1;padding:14px;border-radius:10px;border:none;font-size:16px}
 button{padding:14px 16px;border:none;border-radius:10px;font-size:16px;font-weight:bold;background:#2b6cff;color:#fff}
 .item{background:#1a1a2e;border-radius:10px;padding:12px;margin-bottom:8px;display:flex;align-items:center;gap:10px}
 .item .t{flex:1;font-size:15px;line-height:1.3}
 .item .c{font-size:12px;color:#8ab}
 .res{background:#ff3b8b}
 h3{color:#ffcf3f;margin:18px 0 8px}
 .q{background:#141428;border-left:4px solid #41e0ff;border-radius:8px;padding:8px 12px;margin-bottom:6px;font-size:15px;display:flex;align-items:center;gap:8px}
 .q .qt{flex:1}
 .cx{background:#33334a;color:#ffb3b3;padding:8px 12px;font-size:13px}
 .empty{color:#889;font-size:14px;padding:8px 0}
 .num{display:inline-block;min-width:22px;color:#41e0ff;font-weight:bold}
</style></head><body>
<header>🎤 노래 예약</header>
<div class="wrap">
 <div class="row">
   <input id="q" placeholder="노래 제목·가수 검색" enterkeyhint="search">
   <button onclick="doSearch()">검색</button>
 </div>
 <div id="results"></div>
 <h3>🎫 예약된 곡</h3>
 <div id="queue"><div class="empty">아직 예약된 곡이 없어요.</div></div>
</div>
<script>
 function esc(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')}
 async function doSearch(){
   var q=document.getElementById('q').value.trim(); if(!q)return;
   var r=document.getElementById('results'); r.innerHTML='<div class="empty">검색 중…</div>';
   try{
     var res=await fetch('/search?q='+encodeURIComponent(q));
     if(!res.ok){r.innerHTML='<div class="empty">차와 통신 실패 (HTTP '+res.status+')</div>';return}
     var d=await res.json();
     if(d.error){r.innerHTML='<div class="empty">'+esc(d.error)+'<br><small>차의 인터넷 연결을 확인하세요.</small></div>';return}
     if(!d.items.length){r.innerHTML='<div class="empty">결과가 없어요.</div>';return}
     r.innerHTML=d.items.map(function(it){
       return '<div class="item"><div class="t">'+esc(it.title)+'<div class="c">'+esc(it.channel)+'</div></div>'+
         '<button class="res" onclick="reserve(this,\''+it.videoId+'\',\''+esc(it.title).replace(/\'/g,"&#39;")+'\')">예약</button></div>';
     }).join('');
   }catch(e){r.innerHTML='<div class="empty">차에 연결하지 못했습니다 ('+e+')<br><small>차 화면에서 예약 서버가 켜져 있는지, 같은 WiFi인지 확인하세요.</small></div>'}
 }
 async function reserve(btn,vid,title){
   btn.disabled=true; btn.textContent='예약됨';
   try{ await fetch('/reserve?videoId='+encodeURIComponent(vid)+'&title='+encodeURIComponent(title)); loadQueue(); }
   catch(e){ btn.disabled=false; btn.textContent='예약'; }
 }
 async function loadQueue(){
   try{
     var res=await fetch('/queue'); var d=await res.json();
     var q=document.getElementById('queue');
     if(!d.items.length){q.innerHTML='<div class="empty">아직 예약된 곡이 없어요.</div>';return}
     q.innerHTML=d.items.map(function(it,i){return '<div class="q"><span class="num">'+(i+1)+'</span><span class="qt">'+esc(it.title)+'</span><button class="cx" onclick="cancelRes(\''+it.videoId+'\')">취소</button></div>'}).join('');
   }catch(e){}
 }
 async function cancelRes(vid){
   try{ await fetch('/cancel?videoId='+encodeURIComponent(vid)); loadQueue(); }catch(e){}
 }
 document.getElementById('q').addEventListener('keydown',function(e){if(e.key==='Enter')doSearch()});
 loadQueue(); setInterval(loadQueue,3000);
</script></body></html>
""".trimIndent()
}

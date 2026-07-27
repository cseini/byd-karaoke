package com.cseini.byd.karaoke.share

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cseini.byd.karaoke.data.SettingsStore
import fi.iki.elonen.NanoHTTPD

/**
 * 폰으로 Gemini API 키 입력. 헤드유닛에서 긴 키를 손으로 치기 어려워, 앱이 로컬 서버+QR을 띄우고
 * 폰 브라우저에서 키를 붙여넣어 전송하면(최대 3개, 1~2개만도 가능) 앱 설정에 저장한다.
 * 예약/공유 서버와 같은 로컬 HTTP 방식. 앱이 켜져 있고 다이얼로그가 떠 있는 동안만 동작.
 */
object KeyEntryServer {

    private var server: Http? = null
    var url: String? = null
        private set

    /** 폰이 키를 저장하면 호출(메인 스레드). 차 화면 갱신용. */
    @Volatile
    var onSaved: (() -> Unit)? = null

    fun start(context: Context): String? {
        if (server != null) return url
        val ip = localIpAddress() ?: return null
        val app = context.applicationContext
        for (port in intArrayOf(8095, 8096, 8097)) {
            val s = Http(app, port)
            if (runCatching { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }.isSuccess) {
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
        onSaved = null
    }

    private class Http(ctx: Context, port: Int) : NanoHTTPD(port) {
        private val settings = SettingsStore(ctx)

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.POST) runCatching { session.parseBody(HashMap()) }
            val p = session.parameters
            fun q(k: String): String = p[k]?.firstOrNull()?.trim().orEmpty()
            return if (session.uri.startsWith("/save")) {
                settings.openaiApiKey = q("k1")
                settings.openaiApiKey2 = q("k2")
                settings.openaiApiKey3 = q("k3")
                onSaved?.let { cb -> Handler(Looper.getMainLooper()).post { cb() } }
                json("{\"ok\":true}")
            } else {
                html(page())
            }
        }

        private fun page(): String {
            fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
            return PAGE
                .replace("{{K1}}", esc(settings.openaiApiKey))
                .replace("{{K2}}", esc(settings.openaiApiKey2))
                .replace("{{K3}}", esc(settings.openaiApiKey3))
        }

        private fun html(body: String) =
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
                .apply { addHeader("Access-Control-Allow-Origin", "*") }

        private fun json(body: String) =
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body)
                .apply { addHeader("Access-Control-Allow-Origin", "*") }
    }

    private val PAGE = """
<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>노래방 · 키 입력</title>
<style>
 body{font-family:-apple-system,sans-serif;margin:0;background:#0b0b16;color:#eee}
 header{padding:16px;background:#12122a;font-size:19px;font-weight:bold;color:#41e0ff;position:sticky;top:0}
 .wrap{padding:16px;max-width:560px;margin:0 auto}
 p.d{color:#9aa0c0;font-size:14px;line-height:1.5}
 label{display:block;margin:14px 0 6px;font-size:14px;color:#ffcf3f}
 input{width:100%;padding:14px;border-radius:10px;border:none;font-size:16px;box-sizing:border-box}
 button{width:100%;margin-top:20px;padding:16px;border:none;border-radius:12px;font-size:17px;font-weight:bold;background:#2b6cff;color:#fff}
 .msg{margin-top:16px;padding:14px;border-radius:10px;font-size:15px;text-align:center;display:none}
 .ok{background:#123d24;color:#7cf0a0;display:block}
 .err{background:#3d1220;color:#ff9db3;display:block}
 a{color:#41e0ff}
</style></head><body>
<header>🎤 노래방 · Gemini 키 입력</header>
<div class="wrap">
 <p class="d">음성 검색용 Gemini 키를 붙여넣고 전송하세요. 1~3개까지 넣을 수 있고,
   여러 개면 한도 초과 시 자동으로 다음 키로 넘어갑니다. 키는 <a href="https://aistudio.google.com" target="_blank">aistudio.google.com</a>에서 무료 발급.</p>
 <label>키 1</label><input id="k1" value="{{K1}}" placeholder="AIza…" autocomplete="off" autocapitalize="off" spellcheck="false">
 <label>키 2 (선택)</label><input id="k2" value="{{K2}}" placeholder="예비 키" autocomplete="off" autocapitalize="off" spellcheck="false">
 <label>키 3 (선택)</label><input id="k3" value="{{K3}}" placeholder="예비 키" autocomplete="off" autocapitalize="off" spellcheck="false">
 <button onclick="save()">차로 전송</button>
 <div id="msg" class="msg"></div>
</div>
<script>
 async function save(){
   var m=document.getElementById('msg');
   var k1=document.getElementById('k1').value.trim();
   var k2=document.getElementById('k2').value.trim();
   var k3=document.getElementById('k3').value.trim();
   if(!k1&&!k2&&!k3){ m.className='msg err'; m.textContent='키를 하나 이상 입력하세요.'; return; }
   try{
     var body='k1='+encodeURIComponent(k1)+'&k2='+encodeURIComponent(k2)+'&k3='+encodeURIComponent(k3);
     var res=await fetch('/save',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body});
     var d=await res.json();
     if(d.ok){ m.className='msg ok'; m.textContent='✅ 차에 저장됐어요. 이제 앱에서 음성 검색을 쓸 수 있습니다.'; }
     else{ m.className='msg err'; m.textContent='저장 실패. 다시 시도하세요.'; }
   }catch(e){ m.className='msg err'; m.textContent='전송 실패 — 차와 같은 네트워크인지 확인하세요.'; }
 }
</script></body></html>
""".trimIndent()
}

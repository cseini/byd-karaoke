package com.cseini.byd.karaoke.player

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/** NewPipeExtractor 가 요구하는 HTTP 다운로더(OkHttp 기반). 앱에서 한 번만 init. */
class YouTubeDownloader private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 유튜브가 IPv6 대역을 통째로 봇차단(로그인 요구)하는 경우 대응 — IPv4 로만 붙는 클라이언트.
    // 평소엔 시스템 기본 DNS(client)라 정상 회선에 영향 없고, 차단 예외가 떴을 때만 이걸로 재시도한다.
    private val clientV4 = client.newBuilder()
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                return all.filterIsInstance<java.net.Inet4Address>().ifEmpty { all }
            }
        })
        .build()

    override fun execute(request: Request): Response {
        val dataToSend = request.dataToSend()
        val body = dataToSend?.toRequestBody(null, 0, dataToSend.size)

        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)

        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }

        val response = (if (forceV4.get() == true) clientV4 else client).newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            response.body?.string(),
            response.request.url.toString(),
        )
    }

    companion object {
        // 유튜브 IPv6 봇차단 대응: fetchPage 를 이 안에서 감싸면 그 스레드의 요청만 IPv4 로 나간다.
        private val forceV4 = ThreadLocal.withInitial { false }

        fun <T> withIpv4(block: () -> T): T {
            forceV4.set(true)
            try { return block() } finally { forceV4.set(false) }
        }

        /** 유튜브가 "익명 접근 차단(로그인해서 봇 아님을 증명)" 이라고 답했는지 — IPv6 대역 차단 신호. */
        fun isAnonBlocked(t: Throwable): Boolean {
            val m = "${t::class.java.simpleName} ${t.message}"
            return m.contains("not a bot", true) || m.contains("LOGIN_REQUIRED", true) ||
                m.contains("SignInConfirmNotBot", true)
        }

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        @Volatile private var inited = false

        @Synchronized
        fun ensureInit() {
            if (inited) return
            NewPipe.init(YouTubeDownloader())
            inited = true
        }
    }
}

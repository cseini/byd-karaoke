package com.cseini.byd.karaoke.share

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * 녹음 하나를 내려받게 해주는 초경량 HTTP 서버.
 * 차량 헤드유닛엔 공유 대상 앱이 없어 ACTION_SEND 가 통하지 않으므로,
 * 같은 네트워크(차 핫스팟/WiFi)에 붙은 휴대폰이 브라우저로 직접 받게 한다.
 *
 * 서버 시작 시 일회용 토큰을 생성하며, 파일 다운로드는 이 토큰으로만 가능하다.
 * (같은 WiFi의 다른 기기에서 무단 다운로드하는 것을 방지)
 */
class FileShareServer(private val file: File) : NanoHTTPD(0) {

    // 서버 시작 시 생성되는 일회용 토큰 (Base36 16자)
    private val accessToken: String = SecureRandom().let {
        val bytes = ByteArray(12)
        it.nextBytes(bytes)
        bytes.joinToString("") { b -> "%02x".format(b) }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val tokenParam = session.parameters["t"]?.firstOrNull() ?: ""

        // 토큰 검증: "/file?t=TOKEN" 또는 "/?t=TOKEN" 형태로만 접근 가능
        if (!tokenParam.equals(accessToken)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        return if (uri.startsWith("/file")) {
            // 고정 길이 응답 → 휴대폰 브라우저가 전체 용량·진행률을 표시
            val res = newFixedLengthResponse(
                Response.Status.OK, "audio/x-wav", FileInputStream(file), file.length()
            )
            // 한글 파일명은 RFC 5987(filename*) 로 인코딩
            val enc = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            res.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$enc")
            res
        } else {
            val kb = file.length() / 1024
            val html = """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>오너스랩노래방 녹음 받기</title></head>
                <body style="font-family:sans-serif;text-align:center;padding:40px;background:#111;color:#eee">
                <h2>🎤 내 노래 받기</h2>
                <p style="color:#9ad">${htmlEscape(file.name)}<br>(${kb} KB)</p>
                <p><a href="/file?t=$accessToken" download
                   style="display:inline-block;padding:16px 28px;background:#2b6cff;color:#fff;
                   border-radius:10px;text-decoration:none;font-size:18px">⬇ 다운로드</a></p>
                </body></html>
            """.trimIndent()
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }

    /** 서버가 노출하는 공개 URL (QR 코드에 포함). 포트는 listeningPort로 자동 할당됨. */
    fun getPublicUrl(baseUrl: String): String = "$baseUrl?t=$accessToken"

    private fun htmlEscape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * 휴대폰이 접근할 수 있는 WiFi/핫스팟 IPv4 를 찾는다.
 * 차량은 셀룰러(데이터망) 인터페이스도 있고 그 IP 도 10.x 사설망이라, 단순히 사설망을
 * 먼저 고르면 폰이 닿지 못하는 셀룰러 IP(예: 10.229.x)가 잡힌다. 그래서 인터페이스
 * 이름으로 WiFi(wlan/ap)를 우선하고 셀룰러(rmnet 등)는 배제한다.
 */
fun localIpAddress(context: Context): String? = localIpCandidates(context).firstOrNull()?.ip

/** URL 호스트부 — IPv6 는 대괄호로 싸고 zone-id(%wlan0)를 떼낸다. 예: http://[2001:db8::1]:8080/ */
fun hostForUrl(ip: String): String = if (':' in ip) "[${ip.substringBefore('%')}]" else ip

/** 접속 후보 주소 하나(인터페이스명 포함 — 유닛마다 이름이 달라 진단·수동 선택에 쓴다). */
data class LocalIp(val ip: String, val iface: String)

/**
 * 폰에서 접속 가능한 IPv4 후보를 가능성 높은 순으로. 서버는 모든 인터페이스에 바인딩되므로
 * 어느 주소로든 접속되며, 유닛(DiLink 3/5 등)마다 인터페이스 이름이 달라 자동 선택이 틀릴 수 있어
 * 사용자가 다른 후보로 바꿔볼 수 있게 목록으로 돌려준다.
 */
fun localIpCandidates(context: Context): List<LocalIp> {
    val out = ArrayList<LocalIp>()
    val cm = runCatching { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }.getOrNull()
    // 1) Wi-Fi 네트워크의 IPv4 (릴레이 webIps 와 동일 — TRANSPORT_WIFI LinkProperties)
    cm?.let { c ->
        runCatching {
            for (nw in c.allNetworks) {
                val nc = c.getNetworkCapabilities(nw) ?: continue
                val lp = c.getLinkProperties(nw) ?: continue
                if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val iface = lp.interfaceName ?: "wifi"
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val ip = a.hostAddress ?: continue
                        if (out.none { it.ip == ip }) out.add(LocalIp(ip, iface))
                    }
                }
            }
        }
    }
    // 2) 인터페이스 열거 IPv4 보강 (Wi-Fi 목록이 간헐적으로 빌 때). 셀룰러(rmnet 등)는 폰이 못 붙어 배제.
    runCatching {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name.lowercase()
            if (name.startsWith("rmnet") || name.startsWith("rev_rmnet") ||
                name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("clat")) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (out.none { it.ip == ip }) out.add(LocalIp(ip, ni.name))
                }
            }
        }
    }
    // 3) Wi-Fi 네트워크의 IPv6 (릴레이 webIpv6) — IPv4 뒤(fallback), link-local·loopback·multicast 제외, %zone 제거, 최대 2개
    cm?.let { c ->
        runCatching {
            var v6 = 0
            for (nw in c.allNetworks) {
                val nc = c.getNetworkCapabilities(nw) ?: continue
                val lp = c.getLinkProperties(nw) ?: continue
                if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val iface = lp.interfaceName ?: "wifi"
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isMulticastAddress) {
                        var ip = a.hostAddress ?: continue
                        val z = ip.indexOf('%'); if (z >= 0) ip = ip.substring(0, z)
                        if (v6 < 2 && out.none { it.ip == ip }) { out.add(LocalIp(ip, iface)); v6++ }
                    }
                }
            }
        }
    }
    return out
}

/** 문자열을 QR 비트맵으로. */
fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}

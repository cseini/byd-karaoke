package com.cseini.byd.karaoke.share

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

/**
 * 녹음 하나를 내려받게 해주는 초경량 HTTP 서버.
 * 차량 헤드유닛엔 공유 대상 앱이 없어 ACTION_SEND 가 통하지 않으므로,
 * 같은 네트워크(차 핫스팟/WiFi)에 붙은 휴대폰이 브라우저로 직접 받게 한다.
 */
class FileShareServer(private val file: File) : NanoHTTPD(0) {

    override fun serve(session: IHTTPSession): Response {
        return if (session.uri.endsWith("/file")) {
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
                <title>노래방 녹음 받기</title></head>
                <body style="font-family:sans-serif;text-align:center;padding:40px;background:#111;color:#eee">
                <h2>🎤 내 노래 받기</h2>
                <p style="color:#9ad">${htmlEscape(file.name)}<br>(${kb} KB)</p>
                <p><a href="/file" download
                   style="display:inline-block;padding:16px 28px;background:#2b6cff;color:#fff;
                   border-radius:10px;text-decoration:none;font-size:18px">⬇ 다운로드</a></p>
                </body></html>
            """.trimIndent()
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
    }

    private fun htmlEscape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * 휴대폰이 접근할 수 있는 WiFi/핫스팟 IPv4 를 찾는다.
 * 차량은 셀룰러(데이터망) 인터페이스도 있고 그 IP 도 10.x 사설망이라, 단순히 사설망을
 * 먼저 고르면 폰이 닿지 못하는 셀룰러 IP(예: 10.229.x)가 잡힌다. 그래서 인터페이스
 * 이름으로 WiFi(wlan/ap)를 우선하고 셀룰러(rmnet 등)는 배제한다.
 */
fun localIpAddress(): String? = localIpCandidates().firstOrNull()?.ip

/** 접속 후보 주소 하나(인터페이스명 포함 — 유닛마다 이름이 달라 진단·수동 선택에 쓴다). */
data class LocalIp(val ip: String, val iface: String)

/**
 * 폰에서 접속 가능한 IPv4 후보를 가능성 높은 순으로. 서버는 모든 인터페이스에 바인딩되므로
 * 어느 주소로든 접속되며, 유닛(DiLink 3/5 등)마다 인터페이스 이름이 달라 자동 선택이 틀릴 수 있어
 * 사용자가 다른 후보로 바꿔볼 수 있게 목록으로 돌려준다.
 */
fun localIpCandidates(): List<LocalIp> {
    val out = ArrayList<Pair<Int, LocalIp>>()
    runCatching {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name.lowercase()
            val isWifi = name.startsWith("wlan") || name.startsWith("ap") ||
                name.startsWith("swlan") || name.contains("wifi") || name.startsWith("p2p")
            // 유선/테더링(일부 유닛은 eth0·usb0 로 핫스팟에 붙는다)
            val isWired = name.startsWith("eth") || name.startsWith("usb") || name.startsWith("rndis")
            val isCellular = name.startsWith("rmnet") || name.startsWith("rev_rmnet") ||
                name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("clat")
            for (addr in ni.inetAddresses) {
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                val ip = addr.hostAddress ?: continue
                val siteLocal = addr.isSiteLocalAddress   // 192.168.x / 10.x / 172.16~31.x
                val score = when {
                    isWifi && siteLocal -> 6      // 폰 핫스팟/차 AP — 가장 유력
                    isWifi -> 5
                    isWired && siteLocal -> 4
                    isCellular -> 0               // 셀룰러는 폰에서 접근 불가
                    siteLocal -> 3
                    else -> 1
                }
                out.add(score to LocalIp(ip, ni.name))
            }
        }
    }
    return out.sortedByDescending { it.first }.map { it.second }
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

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
fun localIpAddress(): String? {
    var best: String? = null
    var bestScore = -1
    runCatching {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback) continue
            val name = ni.name.lowercase()
            val isWifi = name.startsWith("wlan") || name.startsWith("ap") ||
                name.startsWith("swlan") || name.contains("wifi") || name.startsWith("p2p")
            val isCellular = name.startsWith("rmnet") || name.startsWith("rev_rmnet") ||
                name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("clat")
            for (addr in ni.inetAddresses) {
                if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                val ip = addr.hostAddress ?: continue
                val score = when {
                    isWifi -> 4
                    isCellular -> 0
                    addr.isSiteLocalAddress -> 2
                    else -> 1
                }
                if (score > bestScore) { bestScore = score; best = ip }
            }
        }
    }
    return best
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

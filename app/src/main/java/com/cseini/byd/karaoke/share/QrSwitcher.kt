package com.cseini.byd.karaoke.share

import android.widget.ImageView
import android.widget.TextView

/**
 * QR + 접속 주소 표시. 서버는 모든 인터페이스에 바인딩되므로 차의 어떤 IP로도 접속되는데,
 * 유닛(DiLink 3/5 등)마다 인터페이스 구성이 달라 자동 선택이 틀릴 수 있다.
 * → 주소나 QR을 탭하면 다음 후보 주소로 바꿔가며 시도할 수 있게 한다.
 */
object QrSwitcher {

    /** @return 후보 개수(0이면 네트워크 없음).
     * @param tokenParam 쿼리 문자열(예: "?t=abc123"). 없으면 빈 문자열. */
    fun bind(qr: ImageView, urlView: TextView, hint: TextView?, port: Int, tokenParam: String = ""): Int {
        val cands = localIpCandidates()
        if (cands.isEmpty()) return 0
        var idx = 0

        fun render() {
            val c = cands[idx]
            val url = "http://${c.ip}:$port/$tokenParam"
            urlView.text = url
            qr.setImageBitmap(qrBitmap(url, 480))
            hint?.text = if (cands.size > 1) {
                "주소 ${idx + 1}/${cands.size} (${c.iface}) · 연결이 안 되면 주소를 눌러 다른 주소로 바꿔보세요"
            } else {
                "접속이 안 되면 차와 폰이 같은 WiFi(핫스팟)에 있는지 확인하세요"
            }
        }

        val next = {
            if (cands.size > 1) { idx = (idx + 1) % cands.size; render() }
        }
        urlView.setOnClickListener { next() }
        qr.setOnClickListener { next() }
        render()
        return cands.size
    }

    /** "http://1.2.3.4:8080/" 에서 포트만 추출(실패 시 기본값). */
    fun portOf(url: String?, def: Int): Int =
        // 뒤에 경로·쿼리(?t=토큰)가 붙어도 포트를 찾는다 — 공유 서버는 랜덤 포트라 기본값으로 떨어지면 QR 이 틀린다.
        Regex(":(\\d+)(?:/.*)?$").find(url.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: def
}

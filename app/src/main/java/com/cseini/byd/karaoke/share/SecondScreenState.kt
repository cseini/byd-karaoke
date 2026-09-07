package com.cseini.byd.karaoke.share

import kotlin.math.abs

/**
 * 뒷좌석 태블릿(세컨드스크린)에 노출할 헤드유닛의 "지금 재생 상태" 스냅샷.
 * 재생 스냅샷은 EmbeddedPlayer 티커(메인스레드)가, 음성검색 상태는 MainActivity 가 각각 publish 하고,
 * HTTP 서버(ReserveServer)의 워커 스레드가 @Volatile 로 읽는다(플레이어를 직접 만지지 않음).
 *
 * 싱크는 기기 간 시계를 비교하지 않고 "서버-상대 시간"으로만 계산한다:
 * atNanos 는 헤드유닛의 System.nanoTime() 이고 서버도 같은 헤드유닛에서 도므로,
 * 서버가 요청 시점에 (nanoNow - atNanos) 로 위치를 보간해 내려주면 된다.
 */
object SecondScreenState {

    @Volatile var enabled: Boolean = false

    data class PlaySnap(
        val videoId: String = "",
        val title: String = "",
        val streamUrl: String? = null,
        val positionMs: Long = 0L,
        val atNanos: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        val speed: Float = 1f,
        val phase: String = "idle",   // idle | playing | scoring | replay
        val score: Int = -1,
        val breakdown: String = "",
    )

    @Volatile var play: PlaySnap = PlaySnap()
    @Volatile var voice: String = "idle"   // idle | listening | processing | <결과/에러 텍스트>

    fun publishPlay(s: PlaySnap) { play = s }
    fun publishVoice(s: String) { voice = s }
    fun reset() { play = PlaySnap(); voice = "idle" }

    /** 서버-상대 라이브 위치(ms): 스냅샷 위치 + (재생 중이면 경과×속도). 일시정지/미재생이면 그대로. */
    fun livePositionMs(s: PlaySnap, nowNanos: Long): Long {
        if (!s.isPlaying || s.atNanos == 0L) return s.positionMs
        val elapsed = (nowNanos - s.atNanos) / 1_000_000.0
        return s.positionMs + (elapsed * s.speed).toLong()
    }

    data class SyncDecision(val seek: Boolean, val playbackRate: Double)

    /**
     * 태블릿 영상 보정 결정(순수함수 — 태블릿 JS 가 동일 로직을 미러링한다).
     * targetMs = 맞춰야 할 위치, videoMs = 현재 태블릿 영상 위치.
     * 큰 오차(>1s)나 곡 변경이면 hard seek, 아니면 재생속도 ±3% 미세조정으로 스르륵 수렴.
     */
    fun syncDecision(targetMs: Long, videoMs: Long, speed: Float, videoChanged: Boolean): SyncDecision {
        if (videoChanged || abs(targetMs - videoMs) > 1000) return SyncDecision(true, speed.toDouble())
        val adj = ((targetMs - videoMs).toDouble() / 2000.0).coerceIn(-0.03, 0.03)
        return SyncDecision(false, speed * (1.0 + adj))
    }
}

package com.cseini.byd.karaoke.player

/**
 * 재생 엔진 공통 인터페이스. 구현체는 IframePlayer(유튜브 IFrame)와 StreamPlayer(NewPipe+ExoPlayer).
 * PlaybackActivity 는 어느 엔진이든 같은 방식으로 부른다.
 */
interface KaraokePlayer {
    fun load(videoId: String)
    fun pause()
    fun play()
    fun release()
    /** 현재 재생 위치(ms). 반주-목소리 정렬용. */
    fun currentPositionMs(): Long
    /** 0f=음소거 ~ 1f. 다시듣기 때 영상은 음소거하고 녹음 소리만 들려주기 위해. */
    fun setVolume(v: Float)
    /** 지정 위치(ms)로 탐색. 다시듣기 시크바 드래그 시 영상을 녹음 위치에 맞춘다. */
    fun seekTo(ms: Long)
}

/**
 * 재생 이벤트 콜백. 모두 메인 스레드에서 호출된다.
 * - onEmbedBlocked: IFrame 엔진에서 임베드 차단 영상일 때. 다음 후보로 넘길 신호.
 * - onError: 그 외 재생 불가(추출 실패·네트워크 등)를 화면에 표시할 때.
 */
class PlayerCallbacks(
    val onPlaying: () -> Unit,
    val onEnded: () -> Unit,
    val onTime: (Float) -> Unit,
    val onEmbedBlocked: () -> Unit,
    val onError: (String) -> Unit,
)

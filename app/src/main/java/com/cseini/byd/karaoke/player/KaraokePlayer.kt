package com.cseini.byd.karaoke.player

/**
 * 재생 엔진 공통 인터페이스. 현재 구현체는 StreamPlayer(NewPipe 추출 + ExoPlayer) 하나다.
 * 호출부(EmbeddedPlayer)는 엔진 종류를 몰라도 되게 이 인터페이스로만 다룬다.
 */
interface KaraokePlayer {
    fun load(videoId: String)
    fun pause()
    fun play()
    fun release()
    /** 현재 재생 위치(ms). 반주-목소리 정렬용. */
    fun currentPositionMs(): Long
    /** 전체 길이(ms). 아직 모르면 0. 재생 화면 seek 바 범위용. */
    fun durationMs(): Long
    /** 0f=음소거 ~ 1f. 다시듣기 때 영상은 음소거하고 녹음 소리만 들려주기 위해. */
    fun setVolume(v: Float)
    /** 지정 위치(ms)로 탐색. 다시듣기 시크바 드래그 시 영상을 녹음 위치에 맞춘다. */
    fun seekTo(ms: Long)
    /** 실제 재생 중인지. 다시듣기 영상 정렬 시점 판단용. */
    fun isPlaying(): Boolean
    /** 키(반음, -6~+6)와 속도(0.5~1.5) 조절. 템포·음정이 서로 영향 없이 각각 바뀐다. */
    fun setKeySpeed(semitones: Int, speed: Float) {}
}

/**
 * 재생 이벤트 콜백. 모두 메인 스레드에서 호출된다.
 * - onError: 재생 불가(스트림 추출 실패·네트워크 등)를 화면에 표시할 때.
 *   (임베드 차단 신호는 IFrame 엔진 시절 것으로, 스트림 재생에는 해당 개념이 없다)
 */
class PlayerCallbacks(
    val onPlaying: () -> Unit,
    val onEnded: () -> Unit,
    val onTime: (Float) -> Unit,
    val onError: (String) -> Unit,
)

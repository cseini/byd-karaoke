package com.cseini.byd.karaoke.audio

/**
 * 믹스가 반주를 읽는 위치. 반주 버퍼는 '들린 순서'대로 쌓이고, 읽기는 재생이 실제로 흐를 때만 전진한다 —
 * 일시정지·버퍼링 중에도 전진하면 반주 쓰기 위치를 앞질러, 그 곡 끝까지 반주 없이 목소리만 녹음된다.
 */
internal class AccompReader(var pos: Int) {
    var held = 0L
        private set
    var missed = 0L
        private set

    fun next(buf: ShortArray, written: Int, playing: Boolean): Int {
        if (!playing) {
            held++
            return 0
        }
        val i = pos++
        if (i in 0 until written) return buf[i].toInt()
        missed++
        return 0
    }
}

/**
 * 재생 장치가 비워지면(위치 이동 등) 미리 써 둔 반주 중 아직 안 들린 부분은 끝내 안 들린다.
 * 들린 곳까지로 되돌려 새 소리를 이어 쓴다. 반환값은 새 쓰기 위치.
 */
internal fun flushAccompWrite(written: Int, heard: Int): Int = heard.coerceIn(0, written)

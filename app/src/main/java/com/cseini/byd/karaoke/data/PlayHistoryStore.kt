package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 재생 기록 한 건. 녹음과 무관하게 '부른 노래'를 기록한다(녹음 꺼도, 녹음 지워도 남음). */
data class PlayHistoryItem(
    val videoId: String,
    val title: String,
    val at: Long = 0L,
    val score: Int = -1, // -1 = 채점 없음
    val breakdown: String? = null, // 채점 심사평(항목별) — 점수 탭 시 표시
)

/**
 * '최근 부른 노래' 기록. RecordingStore(녹음 파일)와 분리 보관 →
 * 녹음을 꺼도, 녹음을 지워도 재생 기록은 유지된다. 같은 곡은 가장 최근 것만, 최대 MAX 개.
 */
class PlayHistoryStore(context: Context) {

    companion object { private const val MAX = 40 }

    private val prefs = context.getSharedPreferences("karaoke_playhistory", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val items = ArrayList<PlayHistoryItem>()

    init { reload() }

    fun reload() {
        items.clear()
        prefs.getString("history", null)?.let { json ->
            val type = object : TypeToken<List<PlayHistoryItem>>() {}.type
            runCatching { gson.fromJson<List<PlayHistoryItem>>(json, type) }
                .getOrNull()?.let { items.addAll(it) }
        }
    }

    /** 부른 노래 기록(같은 곡은 맨 앞으로 갱신). 새 시도라 점수는 초기화. */
    fun add(videoId: String, title: String, at: Long) {
        reload()
        items.removeAll { it.videoId == videoId }
        items.add(0, PlayHistoryItem(videoId, title, at, -1))
        while (items.size > MAX) items.removeAt(items.size - 1)
        persist()
    }

    /** 채점 완료 시 해당 곡의 최근 기록에 점수·심사평 반영. */
    fun setScore(videoId: String, score: Int, breakdown: String? = null) {
        reload()
        val idx = items.indexOfFirst { it.videoId == videoId }
        if (idx >= 0) {
            items[idx] = items[idx].copy(score = score, breakdown = breakdown)
            persist()
        }
    }

    /** 최근 부른 노래 목록에서 해당 곡 제거(사용자 삭제). */
    fun removeByVideoId(videoId: String) {
        reload()
        if (items.removeAll { it.videoId == videoId }) persist()
    }

    fun all(): List<PlayHistoryItem> = items.toList()

    /** 재생 기록이 아직 비어 있으면 기존 녹음 목록 등으로 1회 채운다(마이그레이션). */
    fun seedIfEmpty(seed: List<PlayHistoryItem>) {
        reload()
        if (items.isNotEmpty() || seed.isEmpty()) return
        items.addAll(seed.take(MAX))
        persist()
    }

    private fun persist() {
        prefs.edit().putString("history", gson.toJson(items)).apply()
    }
}

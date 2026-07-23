package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 예약 대기열. 폰(리모컨)에서 넣은 곡이 여기 쌓이고 차가 순서대로 부른다.
 * 서버(ReserveServer)와 화면들이 SharedPreferences 를 공유하므로, 읽기 전 reload() 로 최신화한다.
 */
class QueueStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke_queue", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val items = ArrayList<QueueItem>()

    init {
        reload()
    }

    fun reload() {
        items.clear()
        prefs.getString("queue", null)?.let { json ->
            val type = object : TypeToken<List<QueueItem>>() {}.type
            runCatching { gson.fromJson<List<QueueItem>>(json, type) }
                .getOrNull()?.let { items.addAll(it) }
        }
    }

    fun all(): List<QueueItem> = items.toList()

    fun size(): Int = items.size

    fun add(item: QueueItem) {
        reload()
        // 같은 영상이 이미 대기 중이면 중복 예약 방지
        if (items.none { it.videoId == item.videoId }) {
            items.add(item)
            persist()
        }
    }

    /** 맨 앞 곡을 꺼내 제거(재생용). */
    fun pollFirst(): QueueItem? {
        reload()
        if (items.isEmpty()) return null
        val first = items.removeAt(0)
        persist()
        return first
    }

    fun peekFirst(): QueueItem? { reload(); return items.firstOrNull() }

    fun removeByVideoId(videoId: String) {
        reload()
        if (items.removeAll { it.videoId == videoId }) persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        prefs.edit().putString("queue", gson.toJson(items)).apply()
    }
}

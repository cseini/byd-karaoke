package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 예약 곡. */
data class QueueItem(
    val videoId: String,
    val title: String,
    val channel: String = "",
)

/**
 * 예약 대기열. SharedPreferences 에 JSON 으로 영속(Room 대신 단순화).
 * 곡 하나 규모라 이 방식으로 충분.
 */
class QueueStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke_queue", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val items = ArrayList<QueueItem>()

    init {
        val json = prefs.getString("queue", null)
        if (json != null) {
            val type = object : TypeToken<List<QueueItem>>() {}.type
            runCatching { gson.fromJson<List<QueueItem>>(json, type) }
                .getOrNull()?.let { items.addAll(it) }
        }
    }

    fun all(): List<QueueItem> = items.toList()

    fun size(): Int = items.size

    fun add(item: QueueItem) {
        items.add(item)
        persist()
    }

    fun removeAt(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            persist()
        }
    }

    /** 맨 앞 곡을 꺼내 제거(재생용). */
    fun pollFirst(): QueueItem? {
        if (items.isEmpty()) return null
        val first = items.removeAt(0)
        persist()
        return first
    }

    fun peekFirst(): QueueItem? = items.firstOrNull()

    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        prefs.edit().putString("queue", gson.toJson(items)).apply()
    }
}

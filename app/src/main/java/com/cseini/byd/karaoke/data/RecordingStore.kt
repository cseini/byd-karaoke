package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** 녹음 하나(내가 부른 노래). 파일은 앱 외부저장소 files/ 아래 WAV. */
data class RecordingItem(
    val path: String,
    val videoId: String,
    val title: String,
    val score: Int = -1, // -1 = 채점 없음
    val at: Long = 0L,
)

/** 녹음 목록. SharedPreferences 에 JSON 영속(QueueStore 와 같은 방식). */
class RecordingStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke_recordings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val items = ArrayList<RecordingItem>()

    init {
        prefs.getString("recordings", null)?.let { json ->
            val type = object : TypeToken<List<RecordingItem>>() {}.type
            runCatching { gson.fromJson<List<RecordingItem>>(json, type) }
                .getOrNull()
                // 파일이 지워진 항목은 목록에서도 정리
                ?.let { list -> items.addAll(list.filter { File(it.path).exists() }) }
        }
        persist()
    }

    fun all(): List<RecordingItem> = items.sortedByDescending { it.at }

    fun add(item: RecordingItem) {
        items.add(item)
        persist()
    }

    fun remove(item: RecordingItem) {
        items.removeAll { it.path == item.path }
        File(item.path).delete()
        persist()
    }

    /** 자동 정리로 파일이 이미 삭제된 항목들을 목록에서 제거. */
    fun removeByPaths(paths: List<String>) {
        val set = paths.toHashSet()
        if (items.removeAll { it.path in set }) persist()
    }

    private fun persist() {
        prefs.edit().putString("recordings", gson.toJson(items)).apply()
    }
}

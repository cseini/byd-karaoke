package com.cseini.byd.karaoke.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 예약 대기열. 폰(리모컨)에서 넣은 곡이 여기 쌓이고 차가 순서대로 부른다.
 * 서버(ReserveServer)와 화면들이 SharedPreferences 를 공유하므로, 읽기 전 reload() 로 최신화한다.
 */
class QueueStore(context: Context) {

    private companion object {
        // 매 reload 마다 익명 TypeToken 을 새로 만들면 리플렉션 비용이 그대로 든다(주기 갱신 경로).
        private val LIST_TYPE = object : TypeToken<List<QueueItem>>() {}.type
    }

    private val prefs = context.getSharedPreferences("karaoke_queue", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val items = ArrayList<QueueItem>()
    private var lastJson: String? = null

    init {
        reload()
    }

    /**
     * 저장된 예약 목록을 다시 읽는다.
     * @return 지난번 읽은 내용과 달라졌으면 true. 주기 갱신 화면이 '바뀐 게 없으면 다시 그리지 않기'
     *         판단에 쓴다(노래 부르는 중 메인 스레드 부하를 줄인다).
     */
    fun reload(): Boolean {
        val json = prefs.getString("queue", null)
        if (json == lastJson) return false
        lastJson = json
        items.clear()
        json?.let {
            runCatching { gson.fromJson<List<QueueItem>>(it, LIST_TYPE) }
                .getOrNull()?.let { parsed -> items.addAll(parsed) }
        }
        return true
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
        // lastJson 은 일부러 건드리지 않는다 — 여기서 갱신하면 '내가 방금 지운 곡'을 화면이
        // 변경 없음으로 보고 지워진 줄을 계속 그린다(예약 목록 삭제 직후).
        prefs.edit().putString("queue", gson.toJson(items)).apply()
    }
}

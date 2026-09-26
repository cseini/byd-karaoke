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

/** 녹음 목록. SharedPreferences 에 JSON 으로 영속. */
class RecordingStore(context: Context) {

    private val prefs = context.getSharedPreferences("karaoke_recordings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val items = ArrayList<RecordingItem>()

    init {
        loadRaw()
    }

    /**
     * prefs 에서 그대로 불러온다(파일 존재 확인 없음) — 생성자가 이걸 쓴다.
     * 예전엔 생성자가 바로 reload() 를 불러서, RecordingStore 를 새로 만들 때마다
     * (녹음함·랭킹 화면 열 때, 심지어 앱 시작 시 홈 화면용 인스턴스까지) 저장된 녹음 개수만큼
     * File.exists() 를 메인 스레드에서 하나씩 확인했다 — SD카드가 블랙박스에 점유돼 있으면 특히 느려서
     * 앱 시작·화면 전환이 눈에 띄게 버벅이는 원인이었다.
     */
    private fun loadRaw() {
        items.clear()
        prefs.getString("recordings", null)?.let { json ->
            val type = object : TypeToken<List<RecordingItem>>() {}.type
            runCatching { gson.fromJson<List<RecordingItem>>(json, type) }.getOrNull()?.let { items.addAll(it) }
        }
    }

    /**
     * 지워진 파일 항목을 정리한다(File.exists() 로 하나씩 확인 — 느릴 수 있으니 백그라운드에서 부를 것).
     * 부모 폴더 자체가 안 보이면(SD 카드가 아직/잠시 안 잡힘) 지우지 않는다 — 예전엔 그 순간 SD 녹음
     * 인덱스가 통째로 사라진 채 저장됐다.
     */
    fun reload() {
        loadRaw()
        val list = items.toList()
        val kept = list.filter { val f = File(it.path); f.exists() || f.parentFile?.exists() != true }
        if (kept.size != list.size) {
            items.clear(); items.addAll(kept); persist()
        }
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

    /** 여러 녹음을 한꺼번에 삭제. */
    fun removeItems(list: List<RecordingItem>) {
        val paths = list.map { it.path }.toHashSet()
        if (paths.isEmpty()) return
        list.forEach { runCatching { File(it.path).delete() } }
        items.removeAll { it.path in paths }
        persist()
    }

    /** 자동 정리로 파일이 이미 삭제된 항목들을 목록에서 제거. */
    fun removeByPaths(paths: List<String>) {
        val set = paths.toHashSet()
        if (items.removeAll { it.path in set }) persist()
    }

    private fun persist() {
        // 차량은 전원이 갑자기 끊긴다 — apply() 의 비동기 쓰기가 유실되지 않게 동기 commit.
        prefs.edit().putString("recordings", gson.toJson(items)).commit()
    }
}

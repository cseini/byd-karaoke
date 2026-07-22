package com.cseini.byd.karaoke.data

import android.content.Context
import android.os.Environment
import java.io.File

/** 녹음 저장 위치·용량 관리. 헤드유닛 내부와 SD카드 중 선택. 폴더명은 고정. */
object Storage {

    private const val FOLDER = "노래방녹음"

    /** 차 내부(기본 외부저장소, 앱 전용). */
    fun internalBase(ctx: Context): File =
        ctx.getExternalFilesDirs(null).filterNotNull().firstOrNull() ?: ctx.filesDir

    /** SD카드(이동식 두 번째 볼륨, 앱 전용). 없으면 null. */
    fun sdBase(ctx: Context): File? {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        return dirs.firstOrNull { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
            ?: dirs.getOrNull(1)
    }

    /** mode: "internal" | "sd"(없으면 내부로 폴백). */
    fun recordingsDir(ctx: Context, mode: String): File {
        val base = if (mode == "sd") (sdBase(ctx) ?: internalBase(ctx)) else internalBase(ctx)
        return File(base, FOLDER).apply { mkdirs() }
    }

    fun hasSd(ctx: Context): Boolean = sdBase(ctx) != null

    /** 해당 볼륨의 남은 여유 공간(bytes). */
    fun freeBytes(base: File): Long = runCatching { base.usableSpace }.getOrDefault(0L)

    /** 폴더 안 녹음이 차지하는 용량(bytes). */
    fun usedBytes(dir: File): Long =
        dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    /**
     * 폴더 용량이 상한(maxBytes)을 넘으면 오래된 파일부터 지운다.
     * @return 삭제한 파일 경로 목록(메타데이터 정리에 사용).
     */
    fun pruneToLimit(dir: File, maxBytes: Long): List<String> {
        val deleted = ArrayList<String>()
        val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return deleted
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > maxBytes && i < files.size) {
            val f = files[i]
            val len = f.length()
            if (f.delete()) { total -= len; deleted.add(f.absolutePath) }
            i++
        }
        return deleted
    }
}

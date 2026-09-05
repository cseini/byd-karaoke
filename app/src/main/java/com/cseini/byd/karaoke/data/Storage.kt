package com.cseini.byd.karaoke.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/** 녹음 저장 위치·용량 관리. 헤드유닛 내부와 SD카드 중 선택. 폴더명은 고정. */
object Storage {

    private const val FOLDER = "노래방녹음"

    /** 차 내부(기본 외부저장소, 앱 전용). */
    fun internalBase(ctx: Context): File =
        ctx.getExternalFilesDirs(null).filterNotNull().firstOrNull() ?: ctx.filesDir

    /** SD카드. 앱 전용 볼륨이 안 잡히면(블랙박스 독점) Legacy 경로를 직접 스캔한다. 없으면 null. */
    fun sdBase(ctx: Context): File? {
        val dirs = ctx.getExternalFilesDirs(null).filterNotNull()
        dirs.firstOrNull { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
            ?.let { return it }
        if (dirs.size >= 2) return dirs[1]
        // 표준 API 로 SD 가 안 보이면(블랙박스가 독점 마운트) Legacy 경로를 직접 찾는다.
        return findLegacySdVolume()
    }

    /**
     * targetSdk 28 + Legacy Storage 에서 /storage 아래 물리 SD 볼륨을 직접 찾는다.
     * 일렉트로 앱과 같은 전략 — getExternalFilesDirs 가 못 잡는 볼륨에 접근.
     */
    fun findLegacySdVolume(): File? {
        val candidates = ArrayList<File>()
        File("/storage").listFiles()?.forEach { v ->
            if (v.name != "emulated" && v.name != "self") candidates.add(v)
        }
        listOf("/storage/sdcard1", "/mnt/external_sd", "/external_sd", "/storage/extSdCard")
            .forEach { candidates.add(File(it)) }
        return candidates.firstOrNull {
            runCatching { it.isDirectory && it.canWrite() }.getOrDefault(false)
        }
    }

    /** mode: "internal" | "sd"(없으면 내부로 폴백). */
    fun recordingsDir(ctx: Context, mode: String): File {
        val base = if (mode == "sd") (sdBase(ctx) ?: internalBase(ctx)) else internalBase(ctx)
        return File(base, FOLDER).apply { mkdirs() }
    }

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
            if (f.delete()) {
                total -= len
                deleted.add(f.absolutePath)
            } else {
                Log.w("Storage", "파일 삭제 실패: ${f.absolutePath}")
            }
            i++
        }
        return deleted
    }
}

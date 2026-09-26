package com.cseini.byd.karaoke

import android.content.Context
import java.io.File

/**
 * 비정상 종료 추적 — "노래 중 갑자기 검색화면으로 튕김" 류의 재현 어려운 문제를
 * 다음 실행에서 증거(스택트레이스/직전 이벤트 로그)로 확인하기 위한 장치.
 * · 크래시: 미처리 예외를 crash.txt 로 저장 → 다음 실행에서 다이얼로그로 표시(복사 가능)
 * · 재생성: 주요 수명주기·close 경로를 events.log 에 남김 → 비정상 종료 후 표시
 */
object CrashLog {

    private const val MAX_EVENTS = 24 * 1024
    // event() 의 실제 파일 쓰기를 순서대로 처리하는 단일 백그라운드 스레드 — 메인 스레드 블로킹 방지.
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                File(app.filesDir, "crash.txt")
                    .writeText("${now()} thread=${t.name}\n${e.stackTraceToString().take(6000)}")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /**
     * 한 줄 이벤트 기록(수명주기·close 경로 등). 크기 상한으로 잘라 유지.
     * 호출부는 메인 스레드가 대부분(화면 포커스 변경 등 잦은 지점 포함)이라, 실제 파일 I/O는
     * 단일 백그라운드 스레드에 순서대로 맡긴다 — 예전엔 여기서 매번 동기로 파일을 열고 썼는데,
     * 기능이 늘며 호출 지점이 55곳까지 늘어나 화면 전환마다 디스크 I/O로 버벅이는 원인이었다.
     */
    fun event(ctx: Context, msg: String) {
        val app = ctx.applicationContext
        val line = "${now()} $msg\n"   // 시각은 호출 시점 기준으로 미리 찍어둔다.
        ioExecutor.execute {
            runCatching {
                val f = File(app.filesDir, "events.log")
                f.appendText(line)
                // 상한을 넘었을 때만 뒤쪽 절반만 남기고 잘라낸다(가끔).
                if (f.length() > MAX_EVENTS) f.writeText(f.readText().takeLast(MAX_EVENTS / 2))
            }
        }
    }

    /** 저장된 크래시가 있으면 내용 반환(그리고 삭제). */
    fun takeCrash(ctx: Context): String? {
        val f = File(ctx.applicationContext.filesDir, "crash.txt")
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull()
        f.delete()
        return s
    }

    /** 직전 실행이 '사용자 종료가 아닌 파괴'로 끝났으면 이벤트 꼬리를 반환. */
    fun takeAbnormalEnd(ctx: Context): String? {
        val f = File(ctx.applicationContext.filesDir, "events.log")
        if (!f.exists()) return null
        val s = runCatching { f.readText() }.getOrNull() ?: return null
        val lastMeaningful = s.trim().lines().lastOrNull() ?: return null
        return if (lastMeaningful.contains("onDestroy fin=false")) s.takeLast(3000) else null
    }

    /** 설정>이벤트 로그 뷰어용 — 지우지 않고 최근 기록만 돌려준다. */
    fun recent(ctx: Context): String? {
        val f = File(ctx.applicationContext.filesDir, "events.log")
        if (!f.exists()) return null
        return runCatching { f.readText().takeLast(6000) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun now(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA)
            .format(java.util.Date())
}

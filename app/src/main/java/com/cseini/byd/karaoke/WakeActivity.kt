package com.cseini.byd.karaoke

import android.app.Activity
import android.os.Bundle

/**
 * 외부 호출(PLAY_QUERY)용 깨우기 입구 — 화면 없이 앱 프로세스만 띄우고 바로 끝난다.
 * 차가 프로세스가 죽은 앱에는 쉘 방송을 전달하지 않아서(오랩플 실차 확인 2026-11-03), 방송 전에 먼저 시작해 둔다:
 *   am start -W -n com.cseini.byd.karaoke/.WakeActivity
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.event(this, "외부 호출 앱 깨우기 pid=${android.os.Process.myPid()}")
        finish()
    }
}

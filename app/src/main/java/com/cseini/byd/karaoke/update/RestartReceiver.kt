package com.cseini.byd.karaoke.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cseini.byd.karaoke.MainActivity

/**
 * OTA 설치 완료(MY_PACKAGE_REPLACED) 시 앱을 자동 재시작.
 * 업데이트가 설치되면 프로세스가 죽는데, 사용자가 다시 아이콘을 누를 필요 없이 바로 복귀시킨다.
 * (Android 10 백그라운드 실행 제한에 막히는 기기면 조용히 무시됨 — 그 경우 수동 실행)
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
    }
}

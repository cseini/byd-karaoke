package com.cseini.byd.karaoke.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cseini.byd.karaoke.MainActivity
import com.cseini.byd.karaoke.R

/**
 * OTA 설치 완료(MY_PACKAGE_REPLACED) 알림.
 * v4.21~4.37 은 여기서 액티비티를 백그라운드 실행했는데, 이 차량 런처에선 그 태스크가
 * 보이지 않는 컨테이너(위젯 스택)에 앉아 이후 아이콘을 눌러도 아무것도 안 뜨는 문제가 생겼다.
 * → 자동 실행 대신 '업데이트 완료' 알림을 띄우고, 사용자가 탭하면 정상 위치에서 실행.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("update", "업데이트", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val tap = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n: Notification = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, "update")
                else @Suppress("DEPRECATION") Notification.Builder(context)
                )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("노래방 업데이트 완료 🎉")
                .setContentText("탭하면 새 버전으로 시작합니다")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            nm.notify(2002, n)
        }
    }
}

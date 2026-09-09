package com.cseini.byd.karaoke.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.cseini.byd.karaoke.MainActivity
import com.cseini.byd.karaoke.R

/**
 * 재생 중 앱을 포그라운드로 유지하는 서비스.
 * 다른 앱(네비 등)으로 전환해도 시스템이 프로세스를 죽이지 않아 반주·녹음이 계속된다.
 * 곡 시작(load) 때 start, 플레이어 닫힐 때 stop.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) = runCatching {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) = runCatching {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "재생", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("노래방 재생 중")
            .setContentText("탭하면 노래방으로 돌아갑니다")
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
        // 메모리 압박으로 시스템이 이 FGS 를 죽여도 다시 살려 프로세스를 유지한다(세컨드스크린 유지·백그라운드 복귀 시 종료 완화).
        return START_STICKY
    }
}

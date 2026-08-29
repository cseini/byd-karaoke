package com.cseini.byd.karaoke.update

import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cseini.byd.karaoke.CrashLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 업데이트 진행 UI — 다운로드(진행바) → 설치 → 자동 재시작까지 한 다이얼로그로 보여준다.
 *
 * 기존에는 토스트 두 번이 전부라, 다운로드가 느리거나 무음 설치의 pm install 이 실패하면
 * 사용자에겐 "눌렀는데 아무 일도 안 일어남"으로 보였다. 여기서는 단계마다 화면에 남고,
 * 설치 결과 파일을 폴링해 실패 원인을 보여준 뒤 시스템 설치창으로 폴백한다.
 * (설치가 성공하면 이 앱 프로세스가 죽고 새 버전이 자동 재시작되므로 완료 UI 는 필요 없다.)
 */
object UpdateFlow {

    // 무음 설치 결과 대기 상한. 스크립트 sleep 4초 + 헤드유닛의 pm install(APK 10MB대) 시간.
    private const val INSTALL_WAIT_SEC = 45
    private const val POLL_INTERVAL_SEC = 5

    fun start(activity: AppCompatActivity, release: UpdateManager.Release) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val status = TextView(activity).apply { textSize = 16f; text = "다운로드 준비 중…" }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), 0)
            addView(status)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) },
            )
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("업데이트 v${release.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("취소", null)
            .create()
        dlg.show()

        val job = activity.lifecycleScope.launch {
            // 1) 다운로드 — 진행률 표시
            val apk = UpdateManager.download(activity, release) { pct ->
                activity.runOnUiThread {
                    bar.progress = pct
                    status.text = "다운로드 중… $pct%"
                }
            }
            if (apk == null) {
                bar.visibility = View.GONE
                status.text = "다운로드에 실패했어요. 네트워크 확인 후 다시 시도해주세요."
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "닫기"
                return@launch
            }

            // 2) 무음 설치 — 성공하면 프로세스가 죽고 새 버전이 자동 재시작되므로 아래로 안 내려온다.
            bar.isIndeterminate = true
            status.text = "설치 중입니다… 끝나면 앱이 자동으로 다시 시작돼요."
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
            val dispatched = UpdateManager.startSilentInstall(activity, apk)
            if (!dispatched) {
                // ADB 미개방/미인증 유닛 → 시스템 설치창(진행 화면은 시스템이 띄운다)
                dlg.dismiss()
                UpdateManager.installViaSystem(activity, apk)
                return@launch
            }

            // 3) 결과 폴링 — 앱이 아직 살아 있는데 결과 파일에 실패가 찍혔거나 시간이 다 가면 실패.
            //    pm install 이 성공하면 결과 파일에 "Success" 가 찍힌 뒤 곧 이 프로세스가 죽는다.
            var reason: String? = null
            var succeeded = false
            var waited = 0
            while (waited < INSTALL_WAIT_SEC) {
                delay(POLL_INTERVAL_SEC * 1000L)
                waited += POLL_INTERVAL_SEC
                val r = UpdateManager.readSilentInstallResult(activity)
                if (r != null && !r.contains("Success")) { reason = r; break }
                if (r != null) {
                    // 설치는 끝났고 프로세스가 죽기를 기다리는 중 — 실패로 오판하지 않는다.
                    succeeded = true
                    status.text = "설치 완료 — 곧 앱이 다시 시작돼요."
                } else if (waited >= 20) {
                    status.text = "설치 중입니다… 평소보다 오래 걸리고 있어요."
                }
            }

            dlg.dismiss()
            if (succeeded && reason == null) {
                // 설치는 됐는데 자동 재시작이 안 온 드문 경우 — 재설치를 시키면 안 된다.
                AlertDialog.Builder(activity)
                    .setTitle("설치는 완료됐어요")
                    .setMessage("앱을 껐다 켜면 새 버전(v${release.version})으로 실행됩니다.")
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }

            // 여기 도달 = 무음 설치 실패(성공했다면 이미 프로세스가 죽었다).
            val why = reason ?: "설치 응답 없음 (${INSTALL_WAIT_SEC}초 초과)"
            CrashLog.event(activity, "무음설치 실패 v${release.version}: ${why.take(300)}")
            AlertDialog.Builder(activity)
                .setTitle("자동 설치가 안 됐어요")
                .setMessage(
                    "USB 디버깅이 꺼져 있으면 자동 설치가 안 됩니다(씨라이언 등).\n\n" +
                        "① 설정 → 개발자 옵션 → 'USB 디버깅' 켜기 → 다시 업데이트하면 자동 설치됩니다.\n" +
                        "   (개발자 옵션이 없으면: 설정 → 기기정보 → '빌드번호'를 7번 연속 탭)\n" +
                        "② 또는 아래 '설치창 열기'로 수동 설치하세요.\n\n" +
                        "원인: ${why.take(150)}",
                )
                .setPositiveButton("설치창 열기") { _, _ -> UpdateManager.installViaSystem(activity, apk) }
                .setNeutralButton("개발자 설정 열기") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        runCatching {
                            activity.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
                .setNegativeButton("나중에", null)
                .show()
        }
        // 다운로드 단계에서만 취소 가능(설치 단계에선 버튼을 비활성화한다).
        // show() 뒤라 버튼은 반드시 존재한다 — 널이면 기본 리스너(즉시 dismiss)만 남아
        // 다운로드가 백그라운드에서 계속 돌게 되므로 확실히 잡는다.
        checkNotNull(dlg.getButton(AlertDialog.BUTTON_NEGATIVE)).setOnClickListener {
            job.cancel()
            dlg.dismiss()
        }
    }
}

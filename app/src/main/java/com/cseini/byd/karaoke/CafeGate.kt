package com.cseini.byd.karaoke

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cseini.byd.karaoke.share.qrBitmap
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 카페 정회원 권한 체크 — 앱은 BYD 차량 전용이라 시작할 때 정회원인지 서버에 묻는다.
 * 통과 못 했고 제한 시작 시각 전이면 안내만(유예), 지났으면 사용을 막고 인증 화면으로 바로 가는 길을 둔다.
 * 서버로 보내는 건 인증에 쓰던 해시(th)뿐이다.
 */
object CafeGate {
    private const val RECHECK_MS = 6L * 60 * 60 * 1000

    private val enabled: Boolean get() = BuildConfig.FLAVOR != "lab"

    private var lastCheckAt = 0L
    private var graceShown = false
    private var blockDlg: AlertDialog? = null
    private var checking = false

    /** 앱을 열 때 한 번. */
    fun run(act: AppCompatActivity) {
        if (!enabled) return
        check(act)
    }

    /** 앱이 오래 켜져 있는 차량 특성상, 돌아올 때 마지막 확인이 오래됐으면 다시 확인한다. */
    fun onResume(act: AppCompatActivity) {
        if (!enabled) return
        if (System.currentTimeMillis() - lastCheckAt < RECHECK_MS && blockDlg == null) return
        check(act)
    }

    private fun check(act: AppCompatActivity) {
        if (checking) return
        checking = true
        Thread({
            val st = CafeAuthSync.checkGate(act)
            act.runOnUiThread {
                checking = false
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                lastCheckAt = System.currentTimeMillis()
                val now = System.currentTimeMillis()
                when {
                    st.pass -> { blockDlg?.dismiss(); blockDlg = null }
                    st.enforceAt != 0L && now >= st.enforceAt -> showBlock(act)
                    else -> if (!graceShown) { graceShown = true; showGrace(act, st.enforceAt) }
                }
            }
        }, "cafegate-check").start()
    }

    private fun dateText(ms: Long): String {
        val f = SimpleDateFormat("M월 d일", Locale.KOREA)
        f.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        return f.format(ms)
    }

    private val HOW_TO = "인증 방법\n" +
        "1. '지금 인증하기'를 누르면 인증 코드가 나와요\n" +
        "2. '인증 화면 열기'를 눌러 안내대로 카페 인증 게시판에 그 코드를 댓글로 남겨요\n" +
        "3. 앱에서 확인될 때까지 기다려요(보통 15~30초, 길면 5분)"

    private fun showGrace(act: AppCompatActivity, enforceAt: Long) {
        val when_ = if (enforceAt != 0L) "${dateText(enforceAt)}부터는" else "곧"
        AlertDialog.Builder(act)
            .setTitle("정회원 인증이 필요해요")
            .setMessage("이 앱은 BYD 차량 전용이라 카페 정회원 인증을 마친 분만 쓸 수 있어요. ${when_} 인증 없이는 쓸 수 없어요. 지금은 그대로 쓰실 수 있어요.\n\n$HOW_TO\n\n이전에 인증하셨어도 한 번만 다시 인증해 주세요.")
            .setPositiveButton("지금 인증하기") { _, _ -> showAuth(act) { recheckAfterAuth(act) } }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun showBlock(act: AppCompatActivity) {
        if (blockDlg?.isShowing == true) return
        val dlg = AlertDialog.Builder(act)
            .setTitle("정회원 인증이 필요해요")
            .setMessage("이 앱은 BYD 차량 전용이라 카페 정회원 인증을 마친 분만 쓸 수 있어요.\n\n$HOW_TO\n\n이전에 인증하셨어도 한 번만 다시 인증해 주세요. 정회원 등급이 아니거나 활동이 정지된 계정은 쓸 수 없어요.")
            .setCancelable(false)
            .setPositiveButton("지금 인증하기", null)
            .setNegativeButton("앱 닫기") { _, _ -> act.finishAffinity() }
            .create()
        dlg.show()
        // 인증 화면이 뜨는 동안에도 이 안내가 뒤에 남아 있어야 하므로 누르면 닫히지 않게 한다.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { showAuth(act) { recheckAfterAuth(act) } }
        blockDlg = dlg
    }

    private fun recheckAfterAuth(act: AppCompatActivity) {
        Thread({
            val st = CafeAuthSync.checkGate(act)
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                lastCheckAt = System.currentTimeMillis()
                if (st.pass) {
                    blockDlg?.dismiss(); blockDlg = null
                    Toast.makeText(act, "정회원 확인이 끝났어요", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(act, "아직 정회원으로 확인되지 않아요(등급이나 활동정지를 확인해 주세요)", Toast.LENGTH_LONG).show()
                }
            }
        }, "cafegate-recheck").start()
    }

    /** 인증 코드를 받아 보여주고, 인증 화면(웹)을 열고, 끝날 때까지 기다린다. 확인되면 onConfirmed. */
    fun showAuth(act: AppCompatActivity, onConfirmed: () -> Unit) {
        if (CafeAuthSync.isPending(act)) { showAuthDialog(act, CafeAuthSync.pendingCode(act), onConfirmed); return }
        Toast.makeText(act, "인증 코드 생성 중…", Toast.LENGTH_SHORT).show()
        Thread({
            val code = CafeAuthSync.startAuth(act)
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (code == null) Toast.makeText(act, "코드 생성 실패 — 네트워크를 확인하세요", Toast.LENGTH_LONG).show()
                else showAuthDialog(act, code, onConfirmed)
            }
        }, "cafegate-start").start()
    }

    private fun showAuthDialog(act: AppCompatActivity, code: String, onConfirmed: () -> Unit) {
        val d = act.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        box.addView(TextView(act).apply { text = "아래 코드를 카페 인증 게시판에 댓글로 남겨 주세요"; textSize = 15f })
        box.addView(TextView(act).apply {
            text = code
            textSize = 32f
            setTextColor(0xFF35D6FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
        })
        box.addView(TextView(act).apply {
            text = "폰으로 하려면 카메라로 아래 QR을 찍어 주세요. 이 화면에서 하려면 '인증 화면 열기'를 누르세요. 댓글을 남기면 이 창에서 자동으로 확인돼요."
            textSize = 13f
        })
        CafeAuthSync.webUrl(act)?.let { u ->
            val qrPx = dp(180)
            box.addView(ImageView(act).apply {
                setImageBitmap(qrBitmap(u, qrPx))
                layoutParams = LinearLayout.LayoutParams(qrPx, qrPx).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) }
            })
        }
        val stat = TextView(act).apply { text = "확인 중… (보통 15~30초, 길면 5분)"; textSize = 13f; setPadding(0, dp(10), 0, 0) }
        box.addView(stat)
        val dlg = AlertDialog.Builder(act)
            .setTitle("카페 정회원 인증")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("인증 화면 열기", null)
            .setNegativeButton("닫기", null)
            .create()
        val handler = Handler(Looper.getMainLooper())
        var task: Runnable? = null
        dlg.setOnDismissListener { task?.let { handler.removeCallbacks(it) }; task = null }
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = CafeAuthSync.webUrl(act)
            if (url == null) Toast.makeText(act, "코드가 만료됐어요 — 닫고 다시 시도하세요", Toast.LENGTH_SHORT).show()
            else runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { Toast.makeText(act, "이 기기에서 브라우저를 열 수 없어요. 위 QR을 폰 카메라로 찍어 진행해 주세요", Toast.LENGTH_LONG).show() }
        }
        task = object : Runnable {
            override fun run() {
                Thread({
                    val status = CafeAuthSync.pollResult(act)
                    act.runOnUiThread {
                        if (!dlg.isShowing) return@runOnUiThread
                        when (status) {
                            "confirmed" -> {
                                stat.text = "✓ 확인됐어요!"
                                stat.setTextColor(0xFF35FF7A.toInt())
                                handler.postDelayed({ if (dlg.isShowing) dlg.dismiss(); onConfirmed() }, 1200)
                            }
                            "expired" -> { stat.text = "코드가 만료됐어요 — 닫고 다시 시도하세요"; stat.setTextColor(0xFFFF6B6B.toInt()) }
                            "rejected" -> { stat.text = "정회원으로 확인되지 않았어요(등급이나 활동정지를 확인해 주세요)"; stat.setTextColor(0xFFFF6B6B.toInt()) }
                            else -> task?.let { handler.postDelayed(it, 5000) }
                        }
                    }
                }, "cafegate-poll").start()
            }
        }
        handler.postDelayed(task!!, 5000)
    }
}

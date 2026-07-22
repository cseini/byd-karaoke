package com.cseini.byd.karaoke

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * 유튜브(구글) 로그인용 WebView. 로그인하면 앱 전체가 공유하는 쿠키에 세션이 저장되어,
 * 재생 WebView 에서도 로그인 상태가 유지된다(프리미엄 구독자는 광고가 나오지 않음).
 * 데스크톱 UA 로 띄워 구글의 "이 브라우저는 안전하지 않을 수 있음" 차단을 최대한 피한다.
 */
class LoginActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val web = findViewById<WebView>(R.id.login_web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        web.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://www.youtube.com/")

        findViewById<Button>(R.id.btn_login_done).setOnClickListener {
            CookieManager.getInstance().flush()   // 세션 쿠키 디스크에 저장
            finish()
        }
    }
}

package com.cseini.byd.karaoke.player

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 유튜브 모바일 워치 페이지를 WebView 로 그대로 띄우는 재생기.
 * IFrame 임베드(embeddable=false 로 차단될 수 있음) 대신, 영상이 원래 공개돼 있는
 * youtube.com 워치 페이지를 유튜브 자체 플레이어·광고 그대로 표시한다.
 * (스트림 추출·다운로드·광고 제거는 하지 않는다.)
 *
 * 페이지의 <video> 요소 이벤트를 네이티브로 브리지해 재생/종료/진행시각 콜백을 준다
 * → 자동 녹음 시작·채점·다시듣기 싱크에 그대로 쓴다.
 */
class WatchPlayer(
    private val webView: WebView,
    private val cbPlaying: () -> Unit,
    private val cbEnded: () -> Unit,
    private val cbTime: (Float) -> Unit,
) {
    @SuppressLint("SetJavaScriptEnabled")
    fun init() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        webView.addJavascriptInterface(Bridge(), "AndroidPlayer")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                webView.evaluateJavascript(HOOK_JS, null)
            }
        }
    }

    fun load(videoId: String) {
        webView.loadUrl("https://m.youtube.com/watch?v=$videoId")
    }

    fun pause() {
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v)v.pause();})();", null
        )
    }

    fun play() {
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v)v.play();})();", null
        )
    }

    private inner class Bridge {
        @JavascriptInterface fun onPlaying() = webView.post { cbPlaying() }
        @JavascriptInterface fun onEnded() = webView.post { cbEnded() }
        @JavascriptInterface fun onTime(sec: Float) = webView.post { cbTime(sec) }
    }

    companion object {
        // <video> 가 나타나면 이벤트를 AndroidPlayer 로 넘기고 자동 재생을 시도한다.
        private const val HOOK_JS = """
        (function(){
          function hook(){
            var v=document.querySelector('video');
            if(!v){ setTimeout(hook,500); return; }
            if(v.__k){ return; } v.__k=true;
            v.addEventListener('playing',function(){ try{AndroidPlayer.onPlaying();}catch(e){} });
            v.addEventListener('ended',function(){ try{AndroidPlayer.onEnded();}catch(e){} });
            setInterval(function(){ try{ if(!v.paused) AndroidPlayer.onTime(v.currentTime);}catch(e){} },500);
            var p=v.play(); if(p&&p.catch){ p.catch(function(){}); }
          }
          hook();
        })();
        """
    }
}

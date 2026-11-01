package com.cseini.byd.karaoke.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.ByteArrayOutputStream

/**
 * 홈 화면에 추가했을 때 이름·아이콘이 앱답게 나오게 하는 PWA 매니페스트와 앱 아이콘.
 * 서버가 /manifest.webmanifest 와 /appicon.png 로 내주고, 페이지 head 가 apple-touch-icon·manifest 로 가리킨다.
 */
object WebApp {
    private var iconCache: ByteArray? = null

    /** 앱 런처 아이콘을 512px 정사각으로 렌더링. 적응형 아이콘은 바깥 여백(108dp 중 36dp)을 잘라 그림이 꽉 차게 한다. */
    fun iconPng(ctx: Context): ByteArray? {
        iconCache?.let { return it }
        return runCatching {
            val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
            val s = 512
            val pad = s / 8
            val bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bm)
            c.drawColor(0xFF7C3AED.toInt())   // 아이폰은 투명을 검정으로 채우므로 앱 배경색을 미리 깐다
            d.setBounds(-pad, -pad, s + pad, s + pad)
            d.draw(c)
            val bo = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.PNG, 100, bo)
            bm.recycle()
            bo.toByteArray().also { iconCache = it }
        }.getOrNull()
    }

    fun manifestJson(name: String, shortName: String, startUrl: String): String =
        "{\"name\":\"$name\",\"short_name\":\"$shortName\",\"start_url\":\"$startUrl\",\"scope\":\"/\",\"display\":\"standalone\"," +
            "\"orientation\":\"any\",\"background_color\":\"#7c3aed\",\"theme_color\":\"#7c3aed\"," +
            "\"icons\":[{\"src\":\"/appicon.png\",\"sizes\":\"512x512\",\"type\":\"image/png\"}]}"
}

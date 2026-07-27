package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.share.ReserveServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 프록시 사용 시 절대 URL 로 오는 요청 경로 정규화 검증. */
class NormalizePathTest {

    @Test
    fun keepsRelativePath() {
        assertEquals("/search", ReserveServer.normalizePath("/search"))
        assertEquals("/", ReserveServer.normalizePath("/"))
        assertEquals("/queue", ReserveServer.normalizePath("/queue"))
    }

    @Test
    fun stripsAbsoluteUrlFromProxy() {
        assertEquals("/search", ReserveServer.normalizePath("http://10.245.123.81:8080/search"))
        assertEquals("/save", ReserveServer.normalizePath("http://192.168.0.5:8095/save"))
        assertEquals("/", ReserveServer.normalizePath("http://10.245.123.81:8080/"))
        assertEquals("/reserve", ReserveServer.normalizePath("HTTP://10.0.0.1:8080/reserve"))
    }

    @Test
    fun handlesHostWithoutPathAndHttps() {
        assertEquals("/", ReserveServer.normalizePath("http://10.245.123.81:8080"))
        assertEquals("/cancel", ReserveServer.normalizePath("https://10.245.123.81:8080/cancel"))
    }
}

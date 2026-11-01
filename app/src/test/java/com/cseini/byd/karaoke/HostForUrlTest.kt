package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.share.hostForUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** URL 호스트부 — IPv6 대괄호·zone-id 제거(2nd스크린·공유서버 IPv6 접속). */
class HostForUrlTest {
    @Test fun ipv4PassesThrough() = assertEquals("192.168.0.5", hostForUrl("192.168.0.5"))
    @Test fun ipv6GetsBrackets() = assertEquals("[2001:db8::1]", hostForUrl("2001:db8::1"))
    @Test fun ipv6ZoneStripped() = assertEquals("[fe80::1]", hostForUrl("fe80::1%wlan0"))
}

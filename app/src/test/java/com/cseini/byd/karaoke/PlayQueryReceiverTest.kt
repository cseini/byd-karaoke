package com.cseini.byd.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 음성 비서 외부 호출 — 곡명·가수로 검색어를 만드는 규칙과 실패 회신 형식. */
class PlayQueryReceiverTest {

    @Test fun `곡명만 오면 그대로`() {
        assertEquals("좋은 날", PlayQueryReceiver.buildQuery("좋은 날", null))
        assertEquals("좋은 날", PlayQueryReceiver.buildQuery("  좋은 날 ", ""))
    }

    @Test fun `가수가 있으면 가수 곡명 순서`() {
        assertEquals("아이유 좋은 날", PlayQueryReceiver.buildQuery("좋은 날", "아이유"))
        assertEquals("아이유 좋은 날", PlayQueryReceiver.buildQuery(" 좋은 날", " 아이유 "))
    }

    @Test fun `실패 회신은 ok 가 false 이고 error 문장을 담는다`() {
        val r = PlayQueryReceiver.fail("곡명이 필요합니다")
        assertFalse(r.get("ok").asBoolean)
        assertEquals("곡명이 필요합니다", r.get("error").asString)
    }
}

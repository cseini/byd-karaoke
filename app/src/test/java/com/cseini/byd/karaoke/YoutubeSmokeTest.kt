package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.player.YouTubeDownloader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList

/**
 * 유튜브 스트림 추출 스모크 테스트 = 자동배포 안전 게이트 & 유튜브 깨짐 감지기.
 * 네트워크가 필요하므로 평소엔 스킵하고, 맥미니 자동배포 스크립트가 RUN_YT_SMOKE=1 로
 * 켜서 실행한다. 실패 = (NewPipe 새 버전이 안 맞거나) 유튜브가 방식을 바꿈 → 배포 금지.
 */
class YoutubeSmokeTest {

    @Test
    fun canExtractStream() {
        assumeTrue(System.getenv("RUN_YT_SMOKE") == "1")
        YouTubeDownloader.ensureInit()
        // "Me at the zoo" — 유튜브 최초 영상, 삭제 위험이 사실상 없는 안정 기준점
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        ex.fetchPage()
        val audio = ex.audioStreams.size
        val video = ex.videoStreams.size + ex.videoOnlyStreams.size
        println("SMOKE OK: audio=$audio video=$video title=${ex.name}")
        assertTrue("스트림 추출 실패 — 유튜브 방식 변경 또는 NewPipe 호환 문제", audio > 0 || video > 0)
    }
}

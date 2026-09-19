package com.cseini.byd.karaoke

import com.cseini.byd.karaoke.player.YouTubeDownloader
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.File

/**
 * 간주점프 데이터화 PoC 용: 주어진 videoId 의 progressive 스트림 URL 을 /tmp/intro_url.txt 로 덤프.
 * 맥미니에서 NewPipe 추출이 되므로(yt-dlp 는 봇차단) 이 경로로 프레임을 확보한다.
 * RUN_YT_SMOKE=1 SMOKE_VIDEO=<id> 로 실행.
 */
class IntroFrameDumpTest {
    @Test
    fun dumpStreamUrl() {
        assumeTrue(System.getenv("RUN_YT_SMOKE") == "1")
        val vid = System.getenv("SMOKE_VIDEO") ?: "HtzFBF_mWCI"
        YouTubeDownloader.ensureInit()
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$vid")
        ex.fetchPage()
        val sb = StringBuilder()
        sb.append("TITLE\t").append(ex.name).append('\n')
        ex.videoStreams
            .filter { it.content.isNotEmpty() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .forEach { sb.append("MUXED\t").append(it.resolution).append('\t').append(it.content).append('\n') }
        ex.videoOnlyStreams
            .filter { it.content.isNotEmpty() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .forEach { sb.append("VIDEOONLY\t").append(it.resolution).append('\t').append(it.content).append('\n') }
        File("/tmp/intro_url.txt").writeText(sb.toString())
    }
}

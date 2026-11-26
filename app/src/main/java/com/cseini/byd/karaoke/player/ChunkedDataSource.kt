package com.cseini.byd.karaoke.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * 유튜브 throttle 우회 — 거대한 단일 GET 대신 스트림을 CHUNK 바이트 단위로 잘라
 * 청크마다 새 Range 연결을 연다. googlevideo 는 연결당 초기 버스트 후 긴 단일 전송을
 * 조여(hang) 버리므로, 짧은 range 로 자주 재연결하면 매번 버스트 속도를 받아 끊김이 사라진다.
 * yt-dlp http_chunk_size / 유튜브 웹플레이어와 동일한 전략.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
) : DataSource {

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val chunkSize: Long = 2L * 1024 * 1024,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstreamFactory.createDataSource(), chunkSize)
    }

    private var spec: DataSpec? = null
    private var absPos: Long = 0                                 // 다음에 읽을 절대 위치
    private var remainingTotal: Long = C.LENGTH_UNSET.toLong()   // 요청 범위 기준 전체 남은 길이(미상 가능)
    private var chunkRemaining: Long = 0                         // 현재 청크에서 남은 바이트
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        absPos = dataSpec.position
        remainingTotal = C.LENGTH_UNSET.toLong()
        openChunk()
        remainingTotal = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            // 첫 청크 응답의 Content-Range 로 전체 길이 파악(있으면).
            val total = parseTotalLength(upstream.responseHeaders)
            if (total != C.LENGTH_UNSET.toLong()) total - dataSpec.position else C.LENGTH_UNSET.toLong()
        }
        opened = true
        return remainingTotal
    }

    /** [absPos, absPos+want) 범위로 새 연결을 연다. */
    private fun openChunk() {
        val s = spec!!
        val want = if (remainingTotal != C.LENGTH_UNSET.toLong() && remainingTotal in 1 until chunkSize)
            remainingTotal else chunkSize
        val chunkSpec = s.buildUpon().setPosition(absPos).setLength(want).build()
        val got = upstream.open(chunkSpec)
        chunkRemaining = if (got != C.LENGTH_UNSET.toLong()) got else want
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remainingTotal == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            upstream.close()
            openChunk()
        }
        val toRead = minOf(length.toLong(), chunkRemaining).toInt()
        val n = upstream.read(buffer, offset, toRead)
        if (n == C.RESULT_END_OF_INPUT) {
            // 전체 길이 미상이면 짧은 청크 = 진짜 끝. 알고 있으면 경계에서 다음 청크로.
            if (remainingTotal == C.LENGTH_UNSET.toLong()) return C.RESULT_END_OF_INPUT
            chunkRemaining = 0L
            upstream.close()
            openChunk()
            return read(buffer, offset, length)
        }
        absPos += n
        chunkRemaining -= n
        if (remainingTotal != C.LENGTH_UNSET.toLong()) remainingTotal -= n
        return n
    }

    /** "Content-Range: bytes 0-2097151/12345678" 에서 마지막 숫자(전체 길이)를 뽑는다. */
    private fun parseTotalLength(headers: Map<String, List<String>>): Long {
        val cr = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }
            ?.value?.firstOrNull() ?: return C.LENGTH_UNSET.toLong()
        val slash = cr.lastIndexOf('/')
        if (slash < 0) return C.LENGTH_UNSET.toLong()
        return cr.substring(slash + 1).trim().toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    override fun getUri(): Uri? = if (opened) upstream.uri ?: spec?.uri else spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) { runCatching { upstream.close() }; opened = false }
    }
}

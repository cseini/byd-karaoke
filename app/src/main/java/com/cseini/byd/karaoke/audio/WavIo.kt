package com.cseini.byd.karaoke.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM mono WAV 읽기/쓰기. 순수 java.io → 녹음(안드로이드)·분석(테스트) 공용. */
object WavIo {

    data class Wav(val samples: FloatArray, val sampleRate: Int)

    /** 16-bit PCM WAV 로드. 스테레오면 다운믹스. */
    fun read(file: File): Wav {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "WAV 가 너무 짧음: ${file.name}" }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes[0].toInt() == 'R'.code) { "RIFF 아님" }
        val channels = bb.getShort(22).toInt()
        val sampleRate = bb.getInt(24)
        val bits = bb.getShort(34).toInt()
        require(bits == 16) { "16-bit 만 지원 (bits=$bits)" }

        // data 청크 탐색
        var pos = 12
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val sz = bb.getInt(pos + 4)
            if (id == "data") { dataOffset = pos + 8; dataLen = sz; break }
            pos += 8 + sz + (sz and 1)
        }
        require(dataOffset >= 0) { "data 청크 없음" }
        val end = minOf(dataOffset + dataLen, bytes.size)
        val sampleCount = (end - dataOffset) / 2
        val frames = sampleCount / channels
        val out = FloatArray(frames)
        var idx = dataOffset
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                acc += bb.getShort(idx)
                idx += 2
            }
            out[f] = (acc.toFloat() / channels) / 32768f
        }
        return Wav(out, sampleRate)
    }

    /** 스트리밍 라이터(녹음용). close() 에서 헤더 크기 패치. */
    class Writer(private val file: File, private val sampleRate: Int) {
        private val raf = RandomAccessFile(file, "rw")
        private var dataBytes = 0

        init {
            raf.setLength(0)
            raf.write(ByteArray(44))
        }

        fun write(samples: ShortArray, count: Int) {
            val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) buf.putShort(samples[i])
            raf.write(buf.array(), 0, count * 2)
            dataBytes += count * 2
        }

        fun close() {
            val byteRate = sampleRate * 2
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.write(le32(36 + dataBytes))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(le32(16))
            raf.write(le16(1))
            raf.write(le16(1))
            raf.write(le32(sampleRate))
            raf.write(le32(byteRate))
            raf.write(le16(2))
            raf.write(le16(16))
            raf.write("data".toByteArray())
            raf.write(le32(dataBytes))
            raf.close()
        }

        private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    }
}

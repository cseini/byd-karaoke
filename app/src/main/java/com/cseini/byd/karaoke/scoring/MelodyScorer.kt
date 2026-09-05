package com.cseini.byd.karaoke.scoring

import com.cseini.byd.karaoke.audio.SignalAnalysis
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 멜로디 대조 채점 v3 — 실차 덤프(2026-08-08)로 캘리브레이션.
 *
 * 정답: 반주의 배음 합산(harmonic salience, Melodia-lite) 멜로디 — 단일 F0 추적(YIN)은
 *   베이스 배음에 속아 "정답=베이스라인"이 되는 것을 실차 데이터로 확인, FFT 기반으로 교체.
 * 가창: 마이크 YIN + 성량 게이트(기저 유출 레벨 +6dB 이상만 '진짜 가창') —
 *   차량 마이크에 상시 섞이는 반주 유출을 걸러내며, 유출만 있는 치팅도 자동 차단.
 * 점수: 절대 일치율이 아니라 "우연 대비 배율"(이 곡·이 가창에서 실측한 chance 대비 몇 배나
 *   잘 맞았나) — 반주 유출·추출 한계로 절대치 상한이 낮은 실차 환경에서 유일하게 안정적인 지표.
 *   (실차 실측: 제대로 가창 ≈ 2.0배, 우연 ≈ 1.0배)
 *
 * 배점: 멜로디 40 / 박자 25 / 완창률 15 / 음량 10 / 고음·비브라토 10. 총점 = 35 + raw×0.7.
 */
object MelodyScorer {

    private const val MATCH_CENTS = 60.0
    private const val LAG_SEARCH_MS = 3000
    private const val LAG_STEP_MS = 46
    private const val GATE_DB = 6.0            // 기저(유출) 레벨 대비 이만큼 커야 가창으로 인정
    private const val RATIO_FLOOR = 1.05       // 우연 대비 이 이하 = 0점
    private const val RATIO_CEIL = 2.8         // 우연 대비 이 이상 = 만점(비터비 추출 기준 실차 재보정)

    fun score(
        voice: FloatArray, voiceRate: Int, accomp: FloatArray, accompRate: Int,
        debugDir: java.io.File? = null,
    ): ScoringEngine.Score {
        val (vRaw, vRate) = decimate(voice, voiceRate)
        val (aDs, aRate) = decimate(accomp, accompRate)
        val rawLoudDb = activeLevelDb(vRaw)
        val vDs = normalize(vRaw, rawLoudDb)

        // 병렬: 목소리 YIN 추적 / 반주 배음합산 멜로디
        var micResult: List<SignalAnalysis.Frame>? = null
        // 분석 예외는 이 스레드 안에서 잡아야 한다 — 호출자의 runCatching 은 다른 스레드라 못 잡고 프로세스가 죽는다.
        val t = kotlin.concurrent.thread { micResult = runCatching { SignalAnalysis.analyze(vDs, vRate) }.getOrNull() }
        val mel = salienceMelody(aDs, aRate)
        t.join()
        val mic = micResult ?: return noSing("채점 분석에 실패했습니다.")

        if (mel.count { it > 0f } < 40) {
            // 멜로디를 못 뽑는 반주 → 기존 자체 채점 폴백
            return ScoringEngine.score(mic, BeatTracker.detectBeatPeriod(aDs, aRate))
        }

        // 성량 게이트: 하위 25% 레벨(=상시 유출·환경음) +6dB 이상 프레임만 가창으로.
        // 유출만 녹음된 경우(안 부름) 게이트를 넘는 프레임이 거의 없어 자연히 낮은 점수.
        val rms = mic.map { it.rmsDb.toDouble() }
        val base = percentile(rms, 0.25)
        var gate = maxOf(base + GATE_DB, -50.0)
        var sung = BooleanArray(mic.size) { mic[it].voiced && mic[it].f0 > 0f && mic[it].rmsDb > gate }
        // 게이트 위로 올라오는 프레임이 거의 없다 = 마이크에 '기저(유출)보다 큰 소리'가 없다
        // = 안 부른 것. (v4.55 까지는 여기서 게이트를 완화했는데, 유출만 있는 녹음도 레벨이
        // 균일해 예외에 걸려 유출이 정답과 매칭 → 안 불러도 68점이 나오는 구멍이었다.)
        val voicedTotal = mic.count { it.voiced && it.f0 > 0f }
        if (voicedTotal >= 8 && sung.count { it } < voicedTotal / 5) {
            return noSing("노래가 감지되지 않았어요. 마이크에 대고 불러주세요!")
        }
        if (sung.count { it } < 8) return noSing("노래가 감지되지 않았어요. 마이크에 대고 크게 불러주세요!")

        // 전역 오프셋: 게이트 통과 프레임 vs 멜로디의 일치가 최대가 되는 시차(±3s)
        fun matchAt(shift: Int): Pair<Int, Int> {
            var m = 0; var tot = 0
            for (i in mic.indices) {
                val j = i + shift
                if (j < 0 || j >= mel.size) continue
                if (!sung[i] || mel[j] <= 0f) continue
                tot++
                if (centsDev(mic[i].f0, mel[j]) <= MATCH_CENTS) m++
            }
            return m to tot
        }
        val steps = (LAG_SEARCH_MS / SignalAnalysis.HOP_MS).roundToInt()
        val stride = (LAG_STEP_MS / SignalAnalysis.HOP_MS).roundToInt().coerceAtLeast(1)
        var bestLag = 0; var bestM = -1
        var s = -steps
        while (s <= steps) {
            val (m, _) = matchAt(s)
            if (m > bestM) { bestM = m; bestLag = s }
            s += stride
        }
        val (matched, overlap) = matchAt(bestLag)
        if (overlap < 8) return noSing("멜로디 구간에서 목소리가 잡히지 않았어요.")
        val q = matched.toDouble() / overlap

        // 우연 기준: 멀리 어긋난 정렬 4곳의 평균 일치율(이 곡·이 가창의 chance 실측)
        val farShifts = listOf(steps * 8, -steps * 8, steps * 12, -steps * 12)
        val rands = farShifts.mapNotNull { sh ->
            val (m, tot) = matchAt(sh)
            if (tot >= 30) m.toDouble() / tot else null
        }
        val qRand = (if (rands.isNotEmpty()) rands.average() else 0.15).coerceIn(0.05, 0.5)

        // ── 멜로디 45: 우연 대비 배율 (비브라토 항목 제거분 +5 흡수) ──
        val ratio = q / qRand
        val skill = ((ratio - RATIO_FLOOR) / (RATIO_CEIL - RATIO_FLOOR)).coerceIn(0.0, 1.0)
        val pitch = (45 * skill).roundToInt()

        // ── 박자 25: 8구간 국소 최적 시차의 흔들림(전역 지연은 무죄) ──
        val segLags = ArrayList<Int>()
        val segN = 8
        val segLen = mic.size / segN
        val local = (300 / SignalAnalysis.HOP_MS).roundToInt()
        if (segLen >= 40) {
            for (seg in 0 until segN) {
                val from = seg * segLen
                var b = 0; var bm = -1
                for (dl in -local..local) {
                    var m = 0
                    for (i in from until from + segLen) {
                        val j = i + bestLag + dl
                        if (j < 0 || j >= mel.size || !sung[i] || mel[j] <= 0f) continue
                        if (centsDev(mic[i].f0, mel[j]) <= MATCH_CENTS) m++
                    }
                    if (m > bm) { bm = m; b = dl }
                }
                if (bm >= 6) segLags.add(b)
            }
        }
        val timingShort = segLags.size < 2
        val timingFraction = if (!timingShort) {
            val med = percentile(segLags.map { it.toDouble() }, 0.5)
            val devMs = percentile(segLags.map { abs(it - med) }, 0.5) * SignalAnalysis.HOP_MS
            (1.0 - devMs / 350.0).coerceIn(0.0, 1.0)
        } else 0.7
        val timing = (25 * timingFraction).roundToInt()

        // ── 완창률 15: 멜로디 프레임 중 가창(게이트 통과)과 겹친 비율 ──
        val melodyFrames = mel.count { it > 0f }
        val coverage = overlap.toDouble() / melodyFrames
        // 참여 점수지만, 음정이 우연 수준이면(멜로디와 무관한 소리) 절반만 인정
        val cover = (15 * ((coverage - 0.03) / 0.45).coerceIn(0.0, 1.0) * (0.5 + 0.5 * skill)).roundToInt()

        // ── 음량 15: 원음 성량(크게 부르면 보상) + 강약 (비브라토 제거분 +5 흡수) ──
        val loudness = ((rawLoudDb + 42.0) / 24.0).coerceIn(0.0, 1.0)
        val volume = (15 * (0.6 * loudness + 0.4 * ScoringEngine.volumeDynamicsFraction(mic)))
            .roundToInt().coerceIn(0, 15)

        // 배점: 멜로디 45 / 박자 25 / 완창 15 / 음량 15 (비브라토 항목 제외)
        val raw = pitch + timing + cover + volume
        // 실차 캘리브레이션: 웬만큼(ratio 2.0) ≈ 90대 초반, 아주 잘(ratio 2.2+) → 100, 엉망 ≈ 60대 초반
        val total = (35 + raw * 0.7).roundToInt().coerceIn(35, 100)

        val bd = buildString {
            append("총점 ${total}점 — 🎼 멜로디 대조 채점\n")
            append("· 멜로디 일치 $pitch/45 — 우연 대비 ${"%.1f".format(ratio)}배 정확\n")
            if (timingShort) append("· 박자 타이밍 $timing/25 — 가창이 짧아 대략 추정\n")
            else append("· 박자 타이밍 $timing/25\n")
            append("· 완창률 $cover/15 — 멜로디 구간의 ${(coverage * 100).roundToInt()}% 가창\n")
            append("· 음량 표현 $volume/15\n")
            append("(반주 멜로디와 직접 비교 · 옥타브 차이 허용)")
        }

        debugDir?.let { dir ->
            // 덤프(zip 압축·수 MB 쓰기)는 결과 표시를 막지 않게 백그라운드로
            kotlin.concurrent.thread(name = "score-dump") {
                runCatching {
                    dumpDebug(dir, vDs, vRate, aDs, aRate,
                        "lag=$bestLag q=$q qRand=$qRand ratio=$ratio overlap=$overlap gate=$gate base=$base rawLoud=$rawLoudDb\n$bd")
                }
            }
        }
        return ScoringEngine.Score(
            total = total,
            pitchAccuracy = pitch, pitchStability = cover,
            beatConsistency = timing, volumeDynamics = volume, vibratoReach = 0,
            voicedPct = (coverage * 100).roundToInt(), medianF0 = 0f, breakdown = bd,
        )
    }

    private fun noSing(msg: String) = ScoringEngine.Score(0, 0, 0, 0, 0, 0, 0, 0f, "총점 0점\n$msg")

    // ── 배음 합산 멜로디(Melodia-lite): 프레임별 f0 후보(180~1000Hz, 1/2반음 격자)의
    //    하모닉 스펙트럼 합이 최대인 후보. 중앙값 대비 3배 이상 두드러질 때만 채택. ──
    private fun salienceMelody(x: FloatArray, rate: Int, threshRatio: Double = 2.2): FloatArray {
        val n = 1024
        val nFft = 2048              // 제로패딩 — 저음역 반음 분해능 확보
        val hop = (rate * SignalAnalysis.HOP_MS / 1000).toInt().coerceAtLeast(1)
        val nFrames = ((x.size - n) / hop).coerceAtLeast(0)
        if (nFrames == 0) return FloatArray(0)
        val fMin = 180.0
        val fMax = 1000.0
        val nCand = (12 * ln(fMax / fMin) / ln(2.0) * 2).toInt()
        val cand = DoubleArray(nCand) { fMin * Math.pow(2.0, it / 24.0) }
        val weights = doubleArrayOf(1.0, 0.8, 0.6, 0.45, 0.35, 0.25)
        // 보컬 가이드 음역 프라이어(330Hz 중심, σ=0.8 옥타브) — 고음 리프·저음 코드로 새는 것 억제
        val prior = DoubleArray(nCand) { c ->
            val oct = Math.log(cand[c] / 330.0) / Math.log(2.0)
            Math.exp(-oct * oct / (2 * 0.8 * 0.8))
        }
        val binOf = Array(6) { h ->
            IntArray(nCand) { c ->
                (cand[c] * (h + 1) * nFft / rate).roundToInt().coerceIn(0, nFft / 2)
            }
        }
        val win = DoubleArray(n) { 0.5 - 0.5 * Math.cos(2 * Math.PI * it / (n - 1)) }
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val mag = DoubleArray(nFft / 2 + 1)
        // 1) 프레임별 salience 행렬 + 신뢰 마스크
        val salMat = Array(nFrames) { DoubleArray(nCand) }
        val confident = BooleanArray(nFrames)
        for (fIdx in 0 until nFrames) {
            val st = fIdx * hop
            for (i in 0 until n) { re[i] = x[st + i] * win[i]; im[i] = 0.0 }
            for (i in n until nFft) { re[i] = 0.0; im[i] = 0.0 }
            fft(re, im)
            // 0.6제곱 압축 — 큰 베이스·타악기가 salience 를 독식하는 것 억제(Melodia 방식)
            for (k in 0..nFft / 2) mag[k] = Math.pow(Math.hypot(re[k], im[k]), 0.6)
            var bestS = 0.0
            val row = salMat[fIdx]
            for (c in 0 until nCand) {
                var v = 0.0
                for (h in 0 until 6) v += weights[h] * mag[binOf[h][c]]
                v *= prior[c]
                row[c] = v
                if (v > bestS) bestS = v
            }
            val med = medianOf(row)
            confident[fIdx] = bestS > threshRatio * med
            // 정규화(프레임간 음량 차이가 경로 선택을 좌우하지 않게)
            if (bestS > 0) for (c in 0 until nCand) row[c] /= bestS
        }
        // 2) 비터비: 멜로디답게 '이어지는' 경로 선택 — 프레임 최대값만 잡으면 반주 악기로 튄다.
        //    전이 벌점 = 반음(2칸)당 0.55, 상한 4.0 (점프 억제하되 진짜 노트 전환은 허용)
        val jumpPen = 0.5
        val dp = Array(2) { DoubleArray(nCand) }
        val bp = Array(nFrames) { ByteArray(nCand) }   // 이전 프레임 최적 후보와의 차(칸): -8..8 저장
        val span = 8                                    // 프레임당 최대 이동 ±8칸(=±4반음)
        for (c in 0 until nCand) dp[0][c] = Math.log(salMat[0][c] + 1e-6)
        for (fIdx in 1 until nFrames) {
            val prev = dp[(fIdx + 1) % 2]
            val cur = dp[fIdx % 2]
            val row = salMat[fIdx]
            for (c in 0 until nCand) {
                var best = -1e18; var bestD = 0
                val lo = (c - span).coerceAtLeast(0)
                val hi = (c + span).coerceAtMost(nCand - 1)
                for (p in lo..hi) {
                    val v = prev[p] - Math.abs(c - p) * jumpPen
                    if (v > best) { best = v; bestD = p - c }
                }
                cur[c] = best + Math.log(row[c] + 1e-6)
                bp[fIdx][c] = bestD.toByte()
            }
        }
        // 역추적
        val path = IntArray(nFrames)
        var c = 0
        val last = dp[(nFrames - 1) % 2]
        for (k in 1 until nCand) if (last[k] > last[c]) c = k
        path[nFrames - 1] = c
        for (fIdx in nFrames - 1 downTo 1) {
            c += bp[fIdx][c].toInt()
            c = c.coerceIn(0, nCand - 1)
            path[fIdx - 1] = c
        }
        // 3) 신뢰 프레임만 출력(경로는 전체로 이어 추적하되, 흐릿한 구간은 표시/채점서 제외)
        return FloatArray(nFrames) { fIdx ->
            if (confident[fIdx]) cand[path[fIdx]].toFloat() else 0f
        }
    }

    /** 반복(비재귀) radix-2 FFT, n=2^k. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang)
            val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0; var cwi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi
                    val vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun medianOf(a: DoubleArray): Double {
        val c = a.copyOf(); c.sort(); return c[c.size / 2]
    }

    private fun percentile(xs: List<Double>, p: Double): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[((s.size - 1) * p).roundToInt().coerceIn(0, s.size - 1)]
    }

    /** 옥타브 무시 음정 편차(0..600 cents). */
    private fun centsDev(f: Float, refF: Float): Double {
        val d = 1200.0 * ln(f.toDouble() / refF) / ln(2.0)
        val folded = d - 1200.0 * Math.round(d / 1200.0)
        return abs(folded)
    }

    /** 46ms 프레임 RMS(dB)의 상위 30% 지점 = 실제 발성 구간의 대표 레벨. */
    private fun activeLevelDb(x: FloatArray): Double {
        val frame = 1024
        val dbs = ArrayList<Double>()
        var i = 0
        while (i + frame <= x.size) {
            var sum = 0.0
            for (k in i until i + frame) sum += x[k] * x[k]
            val rms = Math.sqrt(sum / frame)
            if (rms > 1e-6) dbs.add(20 * Math.log10(rms))
            i += frame
        }
        if (dbs.isEmpty()) return -60.0
        val sorted = dbs.sorted()
        return sorted[(sorted.size * 7 / 10).coerceAtMost(sorted.size - 1)]
    }

    /** 발성 레벨을 -14dB 로 끌어올림(최대 32배) — 작은 차량 마이크 대응. */
    private fun normalize(x: FloatArray, activeDb: Double): FloatArray {
        val gain = Math.pow(10.0, (-14.0 - activeDb) / 20.0).coerceIn(1.0, 32.0).toFloat()
        if (gain <= 1.01f) return x
        return FloatArray(x.size) { (x[it] * gain).coerceIn(-1f, 1f) }
    }

    /** 평균 데시메이션(박스 필터) — 결과 레이트 ≤12kHz 가 되게 올림 배수 선택.
     *  (기존 정수 내림은 16kHz 녹음을 그대로 분석해 채점이 수 배 느렸다) */
    private fun decimate(x: FloatArray, rate: Int): Pair<FloatArray, Int> {
        val factor = Math.ceil(rate / 12000.0).toInt().coerceAtLeast(1)
        if (factor == 1) return x to rate
        val n = x.size / factor
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s2 = 0f
            val base = i * factor
            for (k in 0 until factor) s2 += x[base + k]
            out[i] = s2 / factor
        }
        return out to rate / factor
    }

    /** 마지막 채점의 목소리/반주와 지표를 zip 으로 — 실차 문제를 개발 PC 에서 재현하기 위한 덤프. */
    private fun dumpDebug(
        dir: java.io.File, voice: FloatArray, vRate: Int, accomp: FloatArray, aRate: Int, info: String,
    ) {
        dir.mkdirs()
        val zip = java.io.File(dir, "score-debug.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun putWav(name: String, x: FloatArray, rate: Int) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(wavBytes(x, rate))
                z.closeEntry()
            }
            putWav("voice-11k.wav", voice, vRate)
            putWav("accomp-11k.wav", accomp, aRate)
            z.putNextEntry(java.util.zip.ZipEntry("debug.txt"))
            z.write(info.toByteArray())
            z.closeEntry()
        }
    }

    private fun wavBytes(x: FloatArray, rate: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(44 + x.size * 2)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16((v ushr 16) and 0xFFFF) }
        out.write("RIFF".toByteArray()); le32(36 + x.size * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(x.size * 2)
        for (v in x) le16(((v.coerceIn(-1f, 1f)) * 32767).toInt() and 0xFFFF)
        return out.toByteArray()
    }
}

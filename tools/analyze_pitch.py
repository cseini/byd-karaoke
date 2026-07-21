#!/usr/bin/env python3
"""
Phase 0-B 분석기 — 실차에서 뜬 스파이크 WAV 들을 오프라인에서 비교한다.

목적: "반주가 스피커로 나가는 중에 마이크에서 채점 가능한 보컬 F0 를 얻을 수 있는가"를
소스(UNPROCESSED / VOICE_RECOGNITION / VOICE_COMMUNICATION)·AEC on/off 별로 정량 비교.

사용:
    # 무음(반주만, 노래 안 부름) 기준선 1개 + 노래 부른 것들 여러 개를 함께
    python3 analyze_pitch.py baseline_반주만.wav spike_UNPROCESSED_*.wav spike_VOICE_COMMUNICATION_*.wav

의존성: numpy 만 (librosa 불필요). stdlib wave 로 16-bit mono WAV 를 읽는다.
"""
import sys, wave, struct, math
try:
    import numpy as np
except ImportError:
    sys.exit("numpy 가 필요합니다:  pip3 install numpy")

FMIN, FMAX = 80.0, 1000.0      # 보컬 F0 탐색 범위(Hz)
FRAME_MS, HOP_MS = 46.0, 23.0  # 프레임/홉
CLARITY_TH = 0.30              # 자기상관 명료도 임계 (이 이상이면 '주기적=유성'으로 간주)
RMS_GATE_DB = -45.0            # 이보다 조용하면 무음 처리


def read_wav_mono16(path):
    with wave.open(path, "rb") as w:
        ch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        sys.exit(f"{path}: 16-bit PCM 이 아님 (sampwidth={sw})")
    data = np.frombuffer(raw, dtype="<i2").astype(np.float64)
    if ch > 1:
        data = data.reshape(-1, ch).mean(axis=1)
    return data / 32768.0, sr


def autocorr_f0(frame, sr):
    """자기상관 기반 F0 + 명료도(clarity). 없으면 (0,0)."""
    frame = frame - frame.mean()
    if np.sqrt(np.mean(frame ** 2)) < 1e-4:
        return 0.0, 0.0
    corr = np.correlate(frame, frame, mode="full")[len(frame) - 1:]
    lag_min = int(sr / FMAX)
    lag_max = min(int(sr / FMIN), len(corr) - 1)
    if lag_max <= lag_min:
        return 0.0, 0.0
    seg = corr[lag_min:lag_max]
    k = int(np.argmax(seg)) + lag_min
    clarity = corr[k] / (corr[0] + 1e-12)
    # 포물선 보간
    if 1 <= k < len(corr) - 1:
        a, b, c = corr[k - 1], corr[k], corr[k + 1]
        denom = (a - 2 * b + c)
        if denom != 0:
            k = k + 0.5 * (a - c) / denom
    return (sr / k if k > 0 else 0.0), float(clarity)


def analyze(path):
    x, sr = read_wav_mono16(path)
    fl, hop = int(sr * FRAME_MS / 1000), int(sr * HOP_MS / 1000)
    f0s, rmss = [], []
    for start in range(0, len(x) - fl, hop):
        fr = x[start:start + fl]
        rms_db = 20 * math.log10(np.sqrt(np.mean(fr ** 2)) + 1e-9)
        rmss.append(rms_db)
        if rms_db < RMS_GATE_DB:
            f0s.append(0.0)
            continue
        f0, clarity = autocorr_f0(fr, sr)
        f0s.append(f0 if clarity >= CLARITY_TH else 0.0)
    f0s, rmss = np.array(f0s), np.array(rmss)
    voiced = f0s[f0s > 0]
    voiced_pct = 100.0 * len(voiced) / max(1, len(f0s))
    if len(voiced) >= 5:
        cents = 1200 * np.log2(voiced / np.median(voiced))
        stab = float(np.std(cents))   # 낮을수록 안정 (cents)
        med = float(np.median(voiced))
        p10, p90 = np.percentile(voiced, [10, 90])
    else:
        stab, med, p10, p90 = float("nan"), float("nan"), float("nan"), float("nan")
    return dict(dur=len(x) / sr, sr=sr, voiced_pct=voiced_pct, med_f0=med,
                p10=p10, p90=p90, stab=stab,
                rms_med=float(np.median(rmss)), rms_peak=float(np.max(rmss)))


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print(f"{'파일':<42}{'초':>5}{'유성%':>7}{'중앙F0':>8}{'F0폭':>10}{'안정(c)':>8}{'RMS중앙':>8}{'RMS피크':>8}")
    print("-" * 96)
    for p in sys.argv[1:]:
        try:
            r = analyze(p)
        except Exception as e:
            print(f"{p:<42} 오류: {e}")
            continue
        name = p.split("/")[-1]
        span = f"{r['p10']:.0f}-{r['p90']:.0f}" if not math.isnan(r["p10"]) else "-"
        print(f"{name:<42}{r['dur']:>5.0f}{r['voiced_pct']:>7.0f}"
              f"{r['med_f0']:>8.0f}{span:>10}{r['stab']:>8.0f}{r['rms_med']:>8.1f}{r['rms_peak']:>8.1f}")
    print("-" * 96)
    print("해석 가이드:")
    print("  · '반주만(노래X)' 기준선의 유성%가 높으면 → 그 소스는 반주를 보컬로 오인(=채점 오염). 낮을수록 좋음.")
    print("  · 노래한 파일은 유성%가 높고, 안정(cents)이 낮을수록(예:<60) F0 가 깨끗하게 잡힌 것.")
    print("  · 반주만 기준선 유성%가 가장 낮으면서 노래 파일 F0 가 잘 잡히는 소스/AEC 조합이 승자.")


if __name__ == "__main__":
    main()

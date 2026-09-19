"""
간주점프 검출 — 인트로 프레임들에서 "첫 가사(로마자 발음줄) 등장" 시각을 찾는다.

검출 신호(intro-jump-detection 메모 참고, TJ/금영 5곡 실측 검증):
- TJ/금영 노래방 영상은 카운트 마커가 곡마다 다르다(3·2·1 / "GO!" / 없음) — 불안정.
- 항상 있는 건 "큰 한글 가사 줄 + 그 아래 로마자 발음 줄". 타이틀카드는 대개 한글만.
- 단, 금영 일부는 타이틀카드에도 곡명 로마자가 붙는다("jo eun nal") → 아티스트/작사/
  작곡 등 메타데이터가 같이 뜨면 타이틀카드로 간주해 제외.
"""
import subprocess, glob, os, sys, select

PY = sys.executable
HERE = os.path.dirname(os.path.abspath(__file__))

_TITLE_MARKERS = ("작사", "작곡", "노래", "현재음정", "원음정", "Karaoke", "KY.", "TJ Karaoke")


def is_title_card(lines):
    joined = " ".join(lines)
    return any(m in joined for m in _TITLE_MARKERS)


def is_romanization(s):
    s = s.strip()
    letters = [c for c in s if c.isalpha()]
    if len(letters) < 8:
        return False
    latin = sum(1 for c in letters if c.lower() in 'abcdefghijklmnopqrstuvwxyz')
    lower = sum(1 for c in letters if c.islower())
    tokens = s.split()
    return latin / len(letters) > 0.85 and lower / max(latin, 1) > 0.7 and len(tokens) >= 3


def first_lyric_onset(frame_dir, fps=2, first_timeout=100, rest_timeout=10):
    """frame_dir 안의 f_NNN.png (fps 간격) 중 첫 가사 등장 시각(초)과 근거 줄을 반환.
    못 찾으면 (None, None)."""
    files = sorted(glob.glob(os.path.join(frame_dir, 'f_*.png')))
    if not files:
        return None, None
    proc = subprocess.Popen([PY, os.path.join(HERE, "ocr_batch.py"), *files],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, bufsize=1)
    try:
        for i, f in enumerate(files):
            t = (i + 1) / fps
            per_frame_timeout = first_timeout if i == 0 else rest_timeout
            ready, _, _ = select.select([proc.stdout], [], [], per_frame_timeout)
            if not ready:
                break
            line = proc.stdout.readline()
            if not line:
                break
            line = line.rstrip("\n")
            if not line.startswith("OK\t"):
                continue
            lines = line[3:].split("\x1f") if len(line) > 3 else []
            if is_title_card(lines):
                continue
            rom = [l for l in lines if is_romanization(l)]
            if rom:
                return t, lines[:4]
        return None, None
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()


if __name__ == "__main__":
    folder = sys.argv[1]
    t, lines = first_lyric_onset(folder)
    print(f"{os.path.basename(folder)}: onset={t}s evidence={lines}")

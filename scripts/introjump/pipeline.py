#!/usr/bin/env python3
"""
간주점프 데이터 파이프라인 — 맥미니 전용(유튜브 추출이 되는 유일한 곳).

주어진 유튜브 videoId 목록마다:
  1. NewPipe(Gradle IntroFrameDumpTest, YoutubeSmokeTest 와 같은 추출 경로)로 progressive
     스트림 URL 확보 — yt-dlp/임베드는 이 환경에서 봇차단되지만 NewPipe 는 된다.
  2. ffmpeg(번들, brew 불필요)로 인트로 30초를 2fps 프레임으로 추출.
  3. detect.first_lyric_onset() 으로 "첫 가사(로마자 발음줄) 등장" 시각 검출.
  4. jump_sec = max(0, onset - LEAD_SEC) 을 landing/introjumps.json 에 upsert.

실행 예:
  python3 scripts/introjump/pipeline.py HtzFBF_mWCI D67jNiKLWC8 ...
  python3 scripts/introjump/pipeline.py --file scripts/introjump/seed_videos.txt

결과는 landing/introjumps.json 에 누적 저장(기존 곡 보존, 재검출 시 덮어씀).
배포(dist 반영 + wrangler pages deploy)는 이 스크립트가 하지 않는다 — 호출자가
확인 후 landing 배포 절차를 밟는다(release-process 메모 참고).
"""
import json, os, subprocess, sys, tempfile, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
LANDING_JSON = os.path.join(REPO_ROOT, "landing", "introjumps.json")
INTRO_URL_FILE = "/tmp/intro_url.txt"
LEAD_SEC = 1.5   # 가사 시작보다 이만큼 일찍 점프(너무 늦게 떨어지면 첫 소절을 놓침)
INTRO_SCAN_SEC = 30
FPS = 2

sys.path.insert(0, HERE)
from detect import first_lyric_onset  # noqa: E402


def get_ffmpeg():
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def extract_stream_url(video_id):
    """Gradle IntroFrameDumpTest 로 NewPipe 추출 → /tmp/intro_url.txt 파싱."""
    if os.path.exists(INTRO_URL_FILE):
        os.remove(INTRO_URL_FILE)
    env = dict(os.environ, RUN_YT_SMOKE="1", SMOKE_VIDEO=video_id)
    subprocess.run(
        ["./gradlew", ":app:testProdReleaseUnitTest",
         "--tests", "com.cseini.byd.karaoke.IntroFrameDumpTest", "--rerun", "-q"],
        cwd=REPO_ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    if not os.path.exists(INTRO_URL_FILE):
        return None, None
    title, video_only_480, muxed = None, None, None
    with open(INTRO_URL_FILE, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "TITLE":
                title = parts[1] if len(parts) > 1 else None
            elif parts[0] == "VIDEOONLY" and len(parts) > 2 and parts[1] == "480p" and video_only_480 is None:
                video_only_480 = parts[2]
            elif parts[0] == "MUXED" and len(parts) > 2 and muxed is None:
                muxed = parts[2]
    return title, (video_only_480 or muxed)


def extract_frames(stream_url, out_dir):
    ff = get_ffmpeg()
    os.makedirs(out_dir, exist_ok=True)
    subprocess.run(
        [ff, "-y", "-loglevel", "error", "-t", str(INTRO_SCAN_SEC), "-i", stream_url,
         "-vf", f"fps={FPS},scale=640:-1", os.path.join(out_dir, "f_%03d.png")],
        check=False,
    )


def load_db():
    if os.path.exists(LANDING_JSON):
        with open(LANDING_JSON, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_db(db):
    with open(LANDING_JSON, "w", encoding="utf-8") as f:
        json.dump(db, f, ensure_ascii=False, indent=1, sort_keys=True)


def process_one(video_id, db):
    print(f"[{video_id}] 스트림 추출 중…")
    title, url = extract_stream_url(video_id)
    if not url:
        print(f"[{video_id}] 추출 실패(스트림 없음) — 건너뜀")
        return
    with tempfile.TemporaryDirectory(prefix="introjump_") as tmp:
        extract_frames(url, tmp)
        onset, evidence = first_lyric_onset(tmp, fps=FPS)
    if onset is None:
        print(f"[{video_id}] 첫가사 미검출 — 건너뜀 (title={title})")
        return
    jump_sec = round(max(0.0, onset - LEAD_SEC), 1)
    db[video_id] = {"jump_sec": jump_sec, "onset_sec": onset, "title": title or ""}
    print(f"[{video_id}] onset={onset}s → jump={jump_sec}s (title={title}) 근거={evidence}")


def main():
    args = sys.argv[1:]
    video_ids = []
    if args and args[0] == "--file":
        with open(args[1], encoding="utf-8") as f:
            video_ids = [l.strip() for l in f if l.strip() and not l.startswith("#")]
    else:
        video_ids = args
    if not video_ids:
        print("사용법: pipeline.py <videoId...> | --file <목록파일>")
        sys.exit(1)

    db = load_db()
    for vid in video_ids:
        process_one(vid, db)
        save_db(db)  # 곡마다 즉시 저장 — 중간에 중단돼도 유실 없음
    print(f"완료. 총 {len(db)}곡 저장됨 → {LANDING_JSON}")


if __name__ == "__main__":
    main()

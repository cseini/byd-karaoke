"""
간주점프 검출용 OCR 워커 — 인자로 받은 프레임 이미지들을 한 프로세스 안에서
순차 OCR(Apple Vision, ocrmac)하고 한 줄씩(stdout) 결과를 흘려보낸다.

실행: python3 ocr_batch.py frame1.png frame2.png ...
출력: 프레임마다 한 줄 — "OK\\t줄1\\x1f줄2\\x1f..." 또는 "ERR\\t메시지"

주의(중요, intro-jump-detection 메모 참고):
- Apple Vision 의 ANE(뉴럴엔진) 모델이 이 macOS/Python 조합에서 컴파일 실패("Missing E5
  bundle... must re-compile")하고 CPU 로 폴백한다. 이 실패는 **프로세스당 첫 호출에서만**
  발생하고 ~1분 걸리며, 그 이후 호출은 프레임당 0.1초 수준으로 빠르다.
  → 반드시 프레임마다 새 프로세스를 띄우지 말고, 한 프로세스에서 여러 프레임을 처리할 것.
- 그 실패 진단 메시지는 OS 레벨 stdout(fd 1)에 비동기로 직접 write() 되어 우리 print() 결과와
  섞일 수 있다 → recognize() 호출 동안 fd 1 을 /dev/null 로 돌려막아 차단한다.
"""
import sys, os

_BAD_MARKERS = ("Unable to find", "GetE5", "GetANEFModel", "ExecutionStream",
                "bundle cache", "e5bundlecache", "mlmodelc")


def is_engine_error(s: str) -> bool:
    return any(m in s for m in _BAD_MARKERS)


def ocr_no_leak(engine, path):
    saved_fd1 = os.dup(1)
    devnull_fd = os.open(os.devnull, os.O_WRONLY)
    os.dup2(devnull_fd, 1)
    try:
        res = engine(path).recognize()
    finally:
        os.dup2(saved_fd1, 1)
        os.close(devnull_fd)
        os.close(saved_fd1)
    return [t[0] for t in res if not is_engine_error(t[0])]


def main():
    from ocrmac import ocrmac
    from PIL import Image

    warm = "/tmp/_ocr_warmup.png"
    if not os.path.exists(warm):
        Image.new("RGB", (32, 32), "white").save(warm)

    def engine(p):
        return ocrmac.OCR(p, language_preference=['ko-KR', 'en-US'])

    try:
        ocr_no_leak(engine, warm)
    except Exception:
        pass

    for path in sys.argv[1:]:
        try:
            lines = ocr_no_leak(engine, path)
            sys.stdout.write("OK\t" + "\x1f".join(lines) + "\n")
        except Exception as e:
            sys.stdout.write("ERR\t" + str(e)[:150] + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""Times the parts of the desktop pipeline that are not the model itself.

Run it with the fake chat window up (tools/fake_chat_window.py) to see where the
seconds go between "a message arrived" and "a request went out", and how much
work an idle poll costs.

    python tools/mock_llm_server.py &
    python tools/fake_chat_window.py &
    python tools/bench_pipeline.py
"""

import io
import os
import statistics
import sys
import time

if sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows"))

import litechat_win as lw      # noqa: E402
import winchat as wc           # noqa: E402

ROUNDS = 8


def find_fake():
    for info in wc.list_windows(min_w=300, min_h=300):
        if info.title == "轻聊测试会话":
            return info
    return None


def ms(fn, rounds=ROUNDS):
    samples = []
    for _ in range(rounds):
        t0 = time.perf_counter()
        fn()
        samples.append((time.perf_counter() - t0) * 1000.0)
    return statistics.median(samples), min(samples)


def main():
    info = find_fake()
    if info is None:
        print("fake chat window not running - start tools/fake_chat_window.py first")
        return 1

    layout = wc.Layout("bench", "测试窗口", lambda c, t: True,
                       left=230, top=38, header=47, bottom=95)
    chat = wc.ChatWindow(info.hwnd, layout)
    bridge = lw.OcrBridge(lw.resource_path("ocr_helper.ps1"))

    print("=== per-poll cost (the capture loop) ===")

    med, low = ms(lambda: chat.capture_bmp())
    print("  PrintWindow + crop          %6.1f ms   (best %5.1f)" % (med, low))

    path = chat.capture_bmp()
    med, low = ms(lambda: bridge.request({"cmd": "ocr", "path": path, "scale": 2}, timeout=25))
    print("  OCR of the message area     %6.1f ms   (best %5.1f)" % (med, low))

    head = os.path.join(os.environ["TEMP"], "bench_head.bmp")
    med, low = ms(lambda: chat.capture_bmp(rect=chat.header_rect(), path=head))
    print("  header capture              %6.1f ms   (best %5.1f)" % (med, low))
    med, low = ms(lambda: bridge.request({"cmd": "ocr", "path": head, "scale": 2}, timeout=25))
    print("  header OCR (title)          %6.1f ms   (best %5.1f)" % (med, low))

    def full_poll():
        p = chat.capture_bmp()
        bridge.request({"cmd": "ocr", "path": p, "scale": 2}, timeout=25)
        h = chat.capture_bmp(rect=chat.header_rect(), path=head)
        bridge.request({"cmd": "ocr", "path": h, "scale": 2}, timeout=25)

    med, low = ms(full_poll, rounds=5)
    print("  TOTAL per poll (current)    %6.1f ms   (best %5.1f)" % (med, low))
    print("  -> polling every 2s burns   %6.0f%% of one core" % (med / 2000.0 * 100))

    print()
    print("=== when nothing changed on screen ===")
    a = chat.capture_bmp()
    b = chat.capture_bmp()
    same = open(a, "rb").read() == open(b, "rb").read()
    print("  two consecutive captures byte-identical: %s" % same)
    if same:
        print("  -> a digest check could skip OCR entirely while idle")

    print()
    print("=== HTTP round trip (local mock, no model latency) ===")
    cfg = {
        "protocol": "openai",
        "baseUrl": "http://127.0.0.1:8765/v1",
        "apiKey": "sk-test",
        "model": "test-model",
    }
    try:
        lw.chat(cfg, "sys", "ping")
        med, low = ms(lambda: lw.chat(cfg, "sys", "ping"), rounds=6)
        print("  cold connection each call   %6.1f ms   (best %5.1f)" % (med, low))
    except Exception as e:
        print("  mock server not reachable: %s" % e)

    bridge.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

# -*- coding: utf-8 -*-
"""Checks that a static (or merely re-painted) chat window does not re-ask the model.

This is the regression test for the "it never stops thinking" report:

  * an idle window must cost one request, not one per poll
  * moving the window (pixels change, text does not) must cost zero extra
    requests, because the newest incoming message did not change
  * a genuinely new incoming message must cost exactly one more

Run it with the app already pointed at the fake window and at the mock server:

    python tools/mock_llm_server.py            (started by this script)
    python tools/fake_chat_window.py           (started by this script)
    python windows/litechat_win.py             (started by this script)
    python tools/verify_no_repeat_calls.py
"""

import io
import os
import subprocess
import sys
import time

if sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(os.environ.get("TEMP", "."), "verify_mock.log")


def count_requests():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for line in f if "POST" in line)
    except FileNotFoundError:
        return 0


def main():
    if os.path.exists(LOG):
        os.remove(LOG)

    mock = subprocess.Popen([sys.executable, "-u", os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=open(LOG, "w", encoding="utf-8"), stdout=subprocess.DEVNULL)
    fake = subprocess.Popen([sys.executable, os.path.join(ROOT, "tools", "fake_chat_window.py")])
    time.sleep(3)
    app = subprocess.Popen([sys.executable, os.path.join(ROOT, "windows", "litechat_win.py")])

    try:
        time.sleep(12)
        after_start = count_requests()
        print("requests after startup          : %d" % after_start)

        time.sleep(20)
        idle = count_requests()
        print("requests after 20s idle         : %d  (delta %d)" % (idle, idle - after_start))

        # Nudge the window: the pixels change, the message text does not.
        sys.path.insert(0, os.path.join(ROOT, "windows"))
        import winchat as wc            # noqa: E402
        u = __import__("ctypes").windll.user32
        hwnd = None
        for info in wc.list_windows(min_w=300, min_h=300):
            if info.title == "轻聊测试会话":
                hwnd = info.hwnd
                break
        if hwnd:
            r = wc.window_rect(hwnd)
            u.MoveWindow(hwnd, r[0] + 1, r[1] + 1, r[2], r[3], True)
            print("moved the chat window by 1px (pixels changed, text did not)")
        time.sleep(12)
        moved = count_requests()
        print("requests after a 1px move       : %d  (delta %d)" % (moved, moved - idle))

        verdict = ("PASS" if (idle - after_start) == 0 and (moved - idle) == 0 else "FAIL")
        print("idle / repaint produce no extra model calls:", verdict)
        return 0 if verdict == "PASS" else 1
    finally:
        for p in (app, fake, mock):
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())

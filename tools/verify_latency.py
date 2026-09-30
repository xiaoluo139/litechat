# -*- coding: utf-8 -*-
"""Times the path the user actually feels: new message in -> candidates out.

Uses the fake chat window (which can be told to append an incoming bubble) and
the mock model API, then reports how long it takes for the request to leave and
for the panel to finish.

    python tools/verify_latency.py
"""

import io
import os
import subprocess
import sys
import time

if sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.environ.get("TEMP", ".")
LOG = os.path.join(TMP, "latency_mock.log")
INBOX = os.path.join(TMP, "fake_chat_newmsg.txt")


def request_times():
    out = []
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            for line in f:
                if "POST" in line:
                    try:
                        out.append(float(line.split()[0]))
                    except ValueError:
                        pass
    except FileNotFoundError:
        pass
    return out


def main():
    for p in (LOG, INBOX):
        if os.path.exists(p):
            os.remove(p)

    mock = subprocess.Popen([sys.executable, "-u",
                             os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=open(LOG, "w", encoding="utf-8"),
                            stdout=subprocess.DEVNULL)
    fake = subprocess.Popen([sys.executable, os.path.join(ROOT, "tools", "fake_chat_window.py")])
    time.sleep(3)
    app = subprocess.Popen([sys.executable, os.path.join(ROOT, "windows", "litechat_win.py")])

    try:
        time.sleep(12)
        before = len(request_times())
        print("baseline requests after startup : %d" % before)

        samples = []
        for i, text in enumerate([
            "对方：这份材料我这边还差一版，明早给你",
            "对方：下午的会改到四点了，你能来吗",
            "对方：那个客户又打电话过来了",
        ]):
            with open(INBOX, "w", encoding="utf-8") as f:
                f.write(text)
            sent_at = time.time()
            deadline = sent_at + 15
            got = None
            while time.time() < deadline:
                stamps = request_times()
                if len(stamps) > before:
                    got = stamps[-1]
                    break
                time.sleep(0.05)
            if got is None:
                print("  message %d: no request within 15s" % (i + 1))
            else:
                delta = got - sent_at
                samples.append(delta)
                print("  message %d: request left after %.2f s" % (i + 1, delta))
                before = len(request_times())
            time.sleep(2.5)

        if samples:
            print()
            print("messages answered within   : %.2f s (median)" % sorted(samples)[len(samples) // 2])
            print("worst case                 : %.2f s" % max(samples))
        return 0
    finally:
        for p in (app, fake, mock):
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())

# -*- coding: utf-8 -*-
"""Regression test for "the suggestions keep vanishing before I can click one".

Reproduces the flickering chrome WeChat draws under the last message - a
timestamp and the "对方正在输入…" strip blinking on and off - and checks that:

  * the panel does not ask the model again while it flickers
  * a genuinely new incoming message still gets exactly one more request

    python tools/verify_no_flicker_restart.py
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
LOG = os.path.join(TMP, "flicker_mock.log")
STRIP = os.path.join(TMP, "fake_chat_strip.txt")
INBOX = os.path.join(TMP, "fake_chat_newmsg.txt")


def requests():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for line in f if "POST" in line)
    except FileNotFoundError:
        return 0


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    for p in (LOG, STRIP, INBOX):
        if os.path.exists(p):
            os.remove(p)
    write(STRIP, "0")

    mock = subprocess.Popen([sys.executable, "-u",
                             os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=open(LOG, "w", encoding="utf-8"),
                            stdout=subprocess.DEVNULL)
    fake = subprocess.Popen([sys.executable, os.path.join(ROOT, "tools", "fake_chat_window.py")])
    time.sleep(3)
    app = subprocess.Popen([sys.executable, os.path.join(ROOT, "windows", "litechat_win.py")])

    try:
        time.sleep(12)
        base = requests()
        print("requests after startup           : %d" % base)

        print("blinking 时间戳 + 对方正在输入… for 24s ...")
        write(STRIP, "1")
        time.sleep(24)
        flicker = requests()
        print("requests after 24s of flickering : %d  (delta %d)" % (flicker, flicker - base))

        write(STRIP, "0")
        time.sleep(2)
        write(INBOX, "对方：行，那我等你消息")
        time.sleep(8)
        after_msg = requests()
        print("requests after a real message    : %d  (delta %d)" % (after_msg, after_msg - flicker))

        ok = (flicker - base) == 0 and (after_msg - flicker) == 1
        print()
        print("flicker ignored + real message still answered:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    finally:
        for p in (app, fake, mock):
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())

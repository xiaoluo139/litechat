# -*- coding: utf-8 -*-
"""Checks that the app asks the model about the OTHER person's last line.

The reported failure was replies that "belong to another conversation". Two
causes, both checked here against the request the app really sends:

  1. my own message must be labelled 我, not 对方
     (read from the bubble colour, since WeChat paints mine green)
  2. 【要回的那句】 must be the other person's newest line - never my own

    python tools/verify_prompt_anchor.py
"""

import io
import json
import os
import subprocess
import sys
import time

if sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.environ.get("TEMP", ".")
DUMP = os.path.join(TMP, "mock_last_request.json")
INBOX = os.path.join(TMP, "fake_chat_newmsg.txt")
STRIP = os.path.join(TMP, "fake_chat_strip.txt")


def last_user_message():
    """The user-role content of the most recent request, or ""."""
    for _ in range(60):
        try:
            with open(DUMP, encoding="utf-8") as f:
                body = json.load(f)
            for m in body.get("messages", []):
                if m.get("role") == "user":
                    return m.get("content", "")
        except Exception:
            pass
        time.sleep(0.5)
    return ""


def main():
    for p in (DUMP, INBOX, STRIP):
        if os.path.exists(p):
            os.remove(p)
    with open(STRIP, "w", encoding="utf-8") as f:
        f.write("0")

    mock = subprocess.Popen([sys.executable, "-u",
                             os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
    fake = subprocess.Popen([sys.executable, os.path.join(ROOT, "tools", "fake_chat_window.py")])
    time.sleep(3)
    app = subprocess.Popen([sys.executable, os.path.join(ROOT, "windows", "litechat_win.py")])

    try:
        time.sleep(12)
        # Look 1: the window as it opens. It ends on the user's own bubble, so
        # the anchor has to be the line above it, and the user's own line after
        # the anchor has to be handed over as "already said - do not repeat".
        opening = last_user_message()
        ok = True
        if not opening:
            print("FAIL: no request was sent on startup")
            return 1
        anchor0 = ""
        for line in opening.splitlines():
            if line.startswith("【要回的那句】"):
                anchor0 = line
        if "三点再来问你" not in anchor0:
            print("FAIL: startup anchor is not the other person's newest line")
            print("      anchor was: %r" % anchor0)
            ok = False
        if "我：" not in opening:
            print("FAIL: my own bubble was not labelled 我")
            ok = False
        if "我已经说过（别重复）" not in opening:
            print("FAIL: my own line after the anchor was not handed over")
            ok = False
        if os.path.exists(DUMP):
            os.remove(DUMP)

        # The last bubble in the window is the user's own (green) one; the new
        # line arriving from the other side is the one worth answering.
        new_line = "材料我明天上午给你"
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write(new_line)
        time.sleep(9)

        prompt = last_user_message()
        if not prompt:
            print("no request was sent")
            return 1

        print("--- what the app asked the model ---")
        print(prompt)
        print("------------------------------------")
        # OCR is never perfect, so the checks look for the distinctive words
        # rather than the exact string.
        anchor = ""
        for line in prompt.splitlines():
            if line.startswith("【要回的那句】"):
                anchor = line
        # The window ends on the user's OWN message, so the anchor must not be
        # that line - it must be the newest thing the other person said.
        if "三点再来问你" in anchor and "我先把手上这份发你" in anchor:
            print("FAIL: the anchor is my own message:", anchor)
            ok = False
        # OCR is never perfect ("材料" came back as "俄"), so the check keys on
        # the distinctive part of the injected line rather than the exact text.
        if "明天" not in anchor:
            print("FAIL: the line to answer is not the other person's newest message")
            print("      anchor was: %r" % anchor)
            ok = False
        if "我：" not in prompt:
            print("FAIL: my own bubble was not labelled 我")
            ok = False
        # The new line is the newest thing on screen, so there is nothing the
        # user said after it - the "already said" band is legitimately absent
        # here. Look 1 above is what checks that band.
        print("prompt anchored on the right message:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    finally:
        for p in (app, fake, mock):
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())

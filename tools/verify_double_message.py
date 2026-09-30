# -*- coding: utf-8 -*-
"""Somebody sends two messages in a row - the panel must still answer.

The reported failure: as soon as the other person sent two or more messages,
the app could not tell them apart and then sat there for a long time without
generating anything at all.

This drops two lines into the fake chat window at once (the way a person
double-texts), then checks that a request goes out and that its anchor is the
NEWEST of the two.

    python tools/verify_double_message.py
"""

import io
import json
import os
import re
import subprocess
import sys
import time

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.environ.get("TEMP", ".")
DUMP = os.path.join(TMP, "mock_last_request.json")
LOG = os.path.join(TMP, "double_mock.log")
INBOX = os.path.join(TMP, "fake_chat_newmsg.txt")
STRIP = os.path.join(TMP, "fake_chat_strip.txt")

FIRST = "你看下今天能不能给我个准话"
SECOND = "客户那边已经等了两天了"


def requests():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for line in f if "POST" in line)
    except FileNotFoundError:
        return 0


def anchor_of(path, want_newest=True):
    try:
        with open(path, encoding="utf-8") as f:
            body = json.load(f)
    except Exception:
        return ""
    for m in body.get("messages", []):
        if m.get("role") == "user":
            for line in (m.get("content") or "").splitlines():
                if line.startswith("【要回的那句】"):
                    return re.sub(r"\s+", "", line)
    return ""


def wait_for_request(before, seconds=25):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if requests() > before:
            time.sleep(1.0)
            return anchor_of(DUMP)
        time.sleep(0.3)
    return ""


def main():
    for p in (LOG, DUMP, INBOX):
        if os.path.exists(p):
            os.remove(p)
    with open(STRIP, "w", encoding="utf-8") as f:
        f.write("0")
    # A short app log makes a stuck run diagnosable instead of mysterious.
    app_log = os.path.join(TMP, "double_app.log")
    app_out = open(app_log, "w", encoding="utf-8")

    mock = subprocess.Popen([sys.executable, "-u",
                             os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=open(LOG, "w", encoding="utf-8"),
                            stdout=subprocess.DEVNULL)
    fake = subprocess.Popen([sys.executable,
                             os.path.join(ROOT, "tools", "fake_chat_window.py")])
    time.sleep(3)
    env = dict(os.environ, LITECHAT_DEBUG_POLL="1")
    app = subprocess.Popen([sys.executable, "-u",
                            os.path.join(ROOT, "windows", "litechat_win.py")],
                           stdout=app_out, stderr=subprocess.STDOUT, env=env)
    ok = True
    try:
        time.sleep(13)
        base = requests()
        print("requests after startup      : %d" % base)

        print("--- two messages arrive back to back ---")
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write(FIRST + "\n" + SECOND)
        got = wait_for_request(base, seconds=25)
        print("anchor after the pair       : %s" % (got or "(no request)"))
        if not got:
            print("FAIL: no request at all for two messages")
            ok = False
        elif SECOND not in got:
            print("FAIL: the anchor is not the newest of the two")
            ok = False

        # And it has to stay responsive afterwards, not wedge.
        print("--- one more message ---")
        before = requests()
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write("那就今天下班前吧")
        again = wait_for_request(before, seconds=25)
        print("anchor after the next one   : %s" % (again or "(no request)"))
        if not again:
            print("FAIL: stuck after the pair")
            ok = False

        # The realistic shape of "two messages": SHORT ones, a moment apart.
        # Short messages go through the "second look" gate, so this is the case
        # where a new bubble can keep resetting the confirmation.
        print("--- two SHORT messages, 0.8s apart ---")
        before = requests()
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write("在吗")
        time.sleep(0.8)
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write("忙不忙")
        short = wait_for_request(before, seconds=30)
        print("anchor after the short pair : %s" % (short or "(no request)"))
        if not short:
            print("FAIL: two short messages produced nothing")
            ok = False
        elif "忙不忙" not in short:
            print("FAIL: the anchor is not the newest short message")
            ok = False

        if not ok:
            print("--- what the app said ---")
            try:
                app_out.flush()
                with open(app_log, encoding="utf-8", errors="replace") as f:
                    for line in f.read().splitlines()[-16:]:
                        print("   " + line)
            except Exception as e:
                print("   (no log: %s)" % e)

        print()
        print("double message handled:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    finally:
        for p in (app, fake, mock):
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())

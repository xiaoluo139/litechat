# -*- coding: utf-8 -*-
"""Two chat windows, one panel: switching must switch the answers.

The reported failure: with more than one conversation open, moving to the other
one kept generating replies about the previous person - the new conversation was
never read at all. There were two independent causes on the desktop:

  1. once the app had attached to a window it never looked again, and
     PrintWindow keeps rendering that window perfectly even while it sits behind
     another one, so the "old" conversation was still being read;
  2. an answer already on the wire was applied to whatever conversation was on
     screen when it landed.

This runs two fake conversations side by side, lets the app attach to the first,
then brings the second to the front, and checks that the request that follows
answers the SECOND conversation's newest message - not the first one's.

    python tools/verify_conversation_switch.py
"""

import ctypes
import io
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "windows"))

import winchat as wc          # noqa: E402

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.environ.get("TEMP", ".")
DUMP = os.path.join(TMP, "mock_last_request.json")
LOG = os.path.join(TMP, "switch_mock.log")

# What the panel must answer after the switch: the newest thing the OTHER person
# said in conversation B - and what it must no longer be answering.
# Only the distinctive part: at this font size OCR drops the leading "行，" now
# and then, and the check is about *which conversation*, not about a comma.
B_ANCHOR = "我这就发你"
A_ANCHOR = "那我三点再来问你"
# Conversation C lives in the SAME window as B. Switching to it changes nothing
# about the window - only the name in the header - which is the case the app had
# no way to notice at all.
# The distinctive tail only: at this font size OCR sometimes prefixes a stray
# glyph from the bubble edge ("1先发我一份大纲"), which says nothing about which
# conversation this is.
C_ANCHOR = "先发我一份大纲"
B_ONLY = "我这就发你"

user32 = ctypes.windll.user32
WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)


def bring_to_front(title):
    """Raise the window with this exact title, the way a real click would.

    SetForegroundWindow alone is refused for a process that is not already the
    foreground one, so this goes through winchat's AttachThreadInput dance.
    """
    found = []
    buf = ctypes.create_unicode_buffer(512)

    def cb(hwnd, _lp):
        user32.GetWindowTextW(hwnd, buf, 512)
        if buf.value == title and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True

    user32.EnumWindows(WNDENUMPROC(cb), None)
    if not found:
        return False
    return bool(wc.force_foreground(found[0]))


def anchor_of(path):
    """The 【要回的那句】 line of the most recent request, or ''."""
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


def requests():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for line in f if "POST" in line)
    except FileNotFoundError:
        return 0


def wait_for_request(before, seconds=25):
    """Wait until one more request has been made, then return its anchor."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if requests() > before:
            time.sleep(1.0)                 # let the dump finish
            return anchor_of(DUMP)
        time.sleep(0.3)
    return ""


def main():
    for path in (LOG, DUMP):
        if os.path.exists(path):
            os.remove(path)
    for tag in ("a", "b"):
        for stem in ("fake_chat_newmsg", "fake_chat_strip", "fake_chat_contact"):
            p = os.path.join(TMP, "%s_%s.txt" % (stem, tag))
            if os.path.exists(p):
                os.remove(p)

    mock = subprocess.Popen([sys.executable, "-u",
                             os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=open(LOG, "w", encoding="utf-8"),
                            stdout=subprocess.DEVNULL)
    win_a = subprocess.Popen([sys.executable,
                              os.path.join(ROOT, "tools", "fake_chat_window.py"),
                              "--variant", "a", "--tag", "a"])
    win_b = subprocess.Popen([sys.executable,
                              os.path.join(ROOT, "tools", "fake_chat_window.py"),
                              "--variant", "b", "--tag", "b"])
    time.sleep(4)

    # The app remembers the window it was pointed at last. Pin it to A, the way
    # a user who picked a window from the list would have.
    cfg_path = os.path.join(os.environ["APPDATA"], "LiteChat", "config.json")
    try:
        with open(cfg_path, encoding="utf-8") as f:
            original = json.load(f)
    except Exception:
        original = None
    cfg = dict(original or {})
    cfg.update({"baseUrl": "http://127.0.0.1:8765/v1", "apiKey": "sk-test",
                "model": "test-model", "protocol": "openai", "providerId": "custom",
                "intervalMs": 600, "autoGenerate": True, "startRunning": True,
                "manualRect": None,
                # Phase 1 is about the automatic behaviour: no pin.
                "lockedConversation": "",
                "knownConversations": [],
                "pinnedTarget": {"title": "轻聊测试会话", "cls": "TkTopLevel"}})
    with open(cfg_path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)

    app_log = os.path.join(TMP, "switch_app.log")
    app_out = open(app_log, "w", encoding="utf-8")
    env = dict(os.environ, LITECHAT_DEBUG_POLL="1")
    app = subprocess.Popen([sys.executable, "-u",
                            os.path.join(ROOT, "windows", "litechat_win.py")],
                           stdout=app_out, stderr=subprocess.STDOUT, env=env)
    ok = True
    try:
        time.sleep(14)
        first = anchor_of(DUMP)
        print("conversation A anchor : %s" % first)
        if A_ANCHOR not in first:
            print("FAIL: the first answer is not about conversation A")
            ok = False

        print("--- bringing conversation B to the front ---")
        if not bring_to_front("轻聊第二会话"):
            print("FAIL: could not raise the second window")
            ok = False
        # The app checks once a second, then reads the new window and answers.
        before = requests()
        second = wait_for_request(before, seconds=30)
        print("after the switch      : %s" % second)
        if B_ANCHOR not in second:
            print("FAIL: still answering the old conversation")
            ok = False
        if A_ANCHOR in second:
            print("FAIL: the new request still carries the old conversation")
            ok = False

        print("--- switching person INSIDE that window (李四 -> 刘工) ---")
        with open(os.path.join(TMP, "fake_chat_contact_b.txt"), "w",
                  encoding="utf-8") as f:
            f.write("刘工")
        before = requests()
        third = wait_for_request(before, seconds=30)
        print("after the contact switch: %s" % third)
        if C_ANCHOR not in third:
            print("FAIL: still answering the previous person in that window")
            ok = False
        if B_ONLY in third:
            print("FAIL: the new request still carries the previous person")
            ok = False

        # ---- phase 2: pinning one person -----------------------------------
        print()
        print("--- pinning 李四 (the picker's 锁定) ---")
        app.terminate()
        app.wait(timeout=10)
        with open(os.path.join(TMP, "fake_chat_contact_b.txt"), "w",
                  encoding="utf-8") as f:
            f.write("李四")                    # put B's contact back
        cfg["lockedConversation"] = "李四"
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        time.sleep(2)
        base = requests()
        app_out = open(app_log, "w", encoding="utf-8")
        app = subprocess.Popen([sys.executable, "-u",
                                os.path.join(ROOT, "windows", "litechat_win.py")],
                               stdout=app_out, stderr=subprocess.STDOUT, env=env)
        time.sleep(12)
        quiet = requests() - base
        print("while 张三 (window A) is in front: %d request(s) (pinned to 李四)"
              % quiet)
        if quiet != 0:
            print("FAIL: a pinned panel answered somebody else")
            ok = False

        print("bringing 李四's window forward again")
        raised = bring_to_front("轻聊第二会话")
        fg = wc.foreground_window_info()
        print("raised=%s foreground now=%s" % (raised, fg.title if fg else None))
        fourth = wait_for_request(requests(), seconds=30)
        print("pinned conversation     : %s" % fourth)
        if B_ANCHOR not in fourth:
            print("FAIL: the pinned conversation was not answered")
            ok = False

        print("switching that window to 刘工 while pinned to 李四")
        with open(os.path.join(TMP, "fake_chat_contact_b.txt"), "w",
                  encoding="utf-8") as f:
            f.write("刘工")
        before = requests()
        ignored = wait_for_request(before, seconds=12)
        print("requests for the other person: %s" % (ignored or "(none)"))
        if ignored:
            print("FAIL: the pin was ignored")
            ok = False

        if not ok:
            print("--- what the app said ---")
            try:
                app_out.flush()
                with open(app_log, encoding="utf-8", errors="replace") as f:
                    for line in f.read().splitlines()[-20:]:
                        print("   " + line)
            except Exception as e:
                print("   (no log: %s)" % e)

        print()
        print("switch follows the new conversation:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    finally:
        for p in (app, win_a, win_b, mock):
            try:
                p.terminate()
            except Exception:
                pass
        if original is not None:
            with open(cfg_path, "w", encoding="utf-8") as f:
                json.dump(original, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    raise SystemExit(main())

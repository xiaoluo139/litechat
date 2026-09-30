# -*- coding: utf-8 -*-
"""A message arrives while the model is still answering the previous one.

This is the shape of the reported "sends two messages and it sits there for a
long time without generating anything": the panel asks the model about message
1, the other person sends message 2 while that request is still open, and from
then on the second message never gets answered.

The mock answers slowly (MOCK_DELAY_MS) so the second message really does land
mid-request. Two things are checked:

  * message 2 gets its own request once the first answer lands
  * the anchor of that request is message 2, not message 1

    python tools/verify_inflight_messages.py
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
LOG = os.path.join(TMP, "inflight_mock.log")
# The window runs with --tag a (so a contact can be switched inside it), which
# puts every one of its files behind that tag.
INBOX = os.path.join(TMP, "fake_chat_newmsg_a.txt")
STRIP = os.path.join(TMP, "fake_chat_strip_a.txt")
CONTACT = os.path.join(TMP, "fake_chat_contact_a.txt")

FIRST = "你看下今天能不能给我个准话"
SECOND = "客户那边已经等了两天了"


def requests():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as f:
            return sum(1 for line in f if "POST" in line)
    except FileNotFoundError:
        return 0


def anchor_of(path):
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


def main():
    delay_ms = os.environ.get("MOCK_DELAY_MS", "6000")
    for p in (LOG, DUMP, INBOX):
        if os.path.exists(p):
            os.remove(p)
    if os.path.exists(CONTACT):
        os.remove(CONTACT)                  # start on the window's own contact
    with open(STRIP, "w", encoding="utf-8") as f:
        f.write("0")
    app_log = os.path.join(TMP, "inflight_app.log")
    app_out = open(app_log, "w", encoding="utf-8")

    env_mock = dict(os.environ, MOCK_DELAY_MS=delay_ms)
    mock = subprocess.Popen([sys.executable, "-u",
                             os.path.join(ROOT, "tools", "mock_llm_server.py"), "8765"],
                            stderr=open(LOG, "w", encoding="utf-8"),
                            stdout=subprocess.DEVNULL, env=env_mock)
    fake = subprocess.Popen([sys.executable,
                             os.path.join(ROOT, "tools", "fake_chat_window.py"),
                             "--variant", "a", "--tag", "a"])
    time.sleep(3)
    env_app = dict(os.environ, LITECHAT_DEBUG_POLL="1")
    app = subprocess.Popen([sys.executable, "-u",
                            os.path.join(ROOT, "windows", "litechat_win.py")],
                           stdout=app_out, stderr=subprocess.STDOUT, env=env_app)
    ok = True
    try:
        print("model answers after %s ms on purpose" % delay_ms)
        time.sleep(16)
        base = requests()
        print("requests after startup      : %d" % base)

        print("--- message 1 ---")
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write(FIRST)
        time.sleep(2.0)                       # the request is still open now
        print("requests while it is thinking: %d (should still be %d)"
              % (requests(), base))

        print("--- message 2 arrives mid-request ---")
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write(SECOND)

        deadline = time.time() + 45
        second_anchor = ""
        while time.time() < deadline:
            if requests() >= base + 2:
                time.sleep(1.0)
                second_anchor = anchor_of(DUMP)
                break
            time.sleep(0.3)
        print("anchor of the follow-up     : %s" % (second_anchor or "(none)"))
        if not second_anchor:
            print("FAIL: message 2 was never asked about (stuck)")
            ok = False
        elif SECOND not in second_anchor:
            print("FAIL: the follow-up request is about the wrong message")
            ok = False

        print("--- and it still answers the next one ---")
        before = requests()
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write("那就今天下班前吧")
        deadline = time.time() + 40
        third = ""
        while time.time() < deadline:
            if requests() > before:
                time.sleep(1.0)
                third = anchor_of(DUMP)
                break
            time.sleep(0.3)
        print("anchor of the third         : %s" % (third or "(none)"))
        if not third:
            print("FAIL: stuck after the mid-request message")
            ok = False

        # Switching conversation while the model is still answering. The answer
        # that arrives belongs to the old conversation and must be dropped - but
        # the "busy" lock has to be released too, or the new conversation is
        # never asked about and the panel looks frozen until the watchdog.
        print("--- switching conversation mid-request ---")
        with open(INBOX, "w", encoding="utf-8") as f:
            f.write("先别急着回我，我想想")
        time.sleep(1.5)                       # request is in flight again
        with open(CONTACT, "w", encoding="utf-8") as f:
            f.write("李四")                    # a different person in that window
        deadline = time.time() + 40
        switched = ""
        while time.time() < deadline:
            anchor = anchor_of(DUMP)
            if "我这就发你" in anchor or "机票" in anchor:
                switched = anchor
                break
            time.sleep(0.4)
        print("anchor after the switch    : %s" % (switched or "(none)"))
        if not switched:
            print("FAIL: the switch mid-request wedged the panel")
            ok = False

        if not ok:
            print("--- what the app said ---")
            try:
                app_out.flush()
                with open(app_log, encoding="utf-8", errors="replace") as f:
                    for line in f.read().splitlines()[-18:]:
                        print("   " + line)
            except Exception as e:
                print("   (no log: %s)" % e)

        print()
        print("message arriving mid-request handled:", "PASS" if ok else "FAIL")
        return 0 if ok else 1
    finally:
        for p in (app, fake, mock):
            try:
                p.terminate()
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())

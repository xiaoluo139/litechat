# -*- coding: utf-8 -*-
"""A throwaway chat window shaped like WeChat desktop, for testing LiteChat.

It lets the desktop app be exercised end to end without touching a real chat
account: the same geometry (230px left rail, 47px header, 95px input strip), the
same left/right bubble placement, and a real text box at the bottom that accepts
the Ctrl+V that the "fill" button sends.

Every second the input box content is written to %TEMP%/fake_chat_input.txt, so a
test can prove the paste actually landed in the box rather than just on the
clipboard.

Usage:  python fake_chat_window.py

Two of them can run side by side with different conversations, which is how the
"I switched chats and it answered the previous person" bug is tested:

    python fake_chat_window.py --variant a --tag a
    python fake_chat_window.py --variant b --tag b
"""

import argparse
import os
import sys
import tempfile
import tkinter as tk

DEFAULT_TITLE = "轻聊测试会话"
# Deliberately the same chrome the app assumes for a window it picks by hand
# (winchat.GENERIC_LAYOUT: 280 rail, 34 title bar, 50 header, 150 input strip).
# With the old numbers the app's header crop missed the contact name entirely,
# so the title - and anything that keys on it, like "you switched to somebody
# else" or pinning one person - could never be tested.
LEFT_PANEL = 280
# Previews for the rows of the conversation list. Deliberately about OTHER
# people: real WeChat shows each row's last message here, and those previews are
# exactly what used to leak into the prompt when the list column was wider than
# the layout's constant.
PREVIEW = {
    # Long on purpose: the real client's previews run across the whole row, so a
    # crop that starts inside the list reads them.
    "张三": "手机微信还是连不上，你那边再看看是不是网络的问题，我这边一直转圈，"
            "重启了路由器也不行，有空回我一下…",
    "李四": "思思想思睡觉的，明天早上八点叫我一下别迟到了，我这边闹钟老是听不见，"
            "记得多叫两声…",
    "刘工": "续费充值提醒：您的宽带套餐还有三天到期，请及时续费，逾期会停机，"
            "点这里可以直接办理…",
    "陆林晖": "月满中秋 | 祝大家中秋快乐，阖家团圆，一起赏月吃月饼，"
              "记得给家里打个电话…",
    "项目群": "好久不见！这周末大家有空一起吃顿饭吗，老地方那家店，"
              "六点半开始…",
    "妈妈": "记得吃饭，天冷了多穿一点，别忘了带伞，晚上早点睡，"
            "别老熬夜看手机…",
    "老同学": "在吗？上次说的那个材料你帮我看看呗，不着急，"
              "你什么时候方便都行…",
}
TITLE_BAR = 34
HEADER = 50
INPUT_BAR = 150

# Two conversations, deliberately about completely different things so a reply
# aimed at the wrong one is obvious rather than subtle. The last line of each
# thread is the USER'S own message, which is the case the prompt anchor used to
# get wrong: it took the last line on screen as "the line to answer".
VARIANTS = {
    "a": {
        "title": DEFAULT_TITLE,
        "contact": "张三",
        "thread": [
            ("other", "昨天那份材料你看过了吗？"),
            ("other", "客户那边催得有点急，今天能给个说法吗"),
            ("me", "我早上看了一半，下午给你答复"),
            ("other", "行，那我三点再来问你"),
            ("me", "行，那我三点再来问你，我先把手上这份发你"),
        ],
    },
    "b": {
        # A different window title AND a different contact name: the window
        # switch and the contact switch are two separate code paths, and both
        # have to end up here.
        "title": "轻聊第二会话",
        "contact": "李四",
        "thread": [
            ("other", "明天的机票订好了吗"),
            ("other", "我这边一共三个人去，行李有点多"),
            ("me", "航班号发我一下，我发给同事"),
            ("other", "行，我这就发你"),
            ("me", "好，我等你消息，路上注意安全"),
        ],
    },
    # Same window as B, different person: this is the "switch contact inside one
    # WeChat window" case, where nothing about the window changes at all.
    "c": {
        "title": "轻聊第二会话",
        # 刘工, not 王五: this machine's Chinese OCR reads "王五" as "干石" every
        # time, and a test that relies on a name the reader cannot read is
        # testing the reader, not the app. (In real use the picker shows the
        # name as the app read it, so a misread is still usable.)
        "contact": "刘工",
        "thread": [
            ("other", "下周的评审你准备到哪一步了"),
            ("other", "我这边材料还没整理完，有点慌"),
            ("me", "我今天下午把框架过一遍"),
            ("other", "那你先发我一份大纲"),
            ("me", "好，我整理完就发你"),
        ],
    },
    # The screen from the field report: a timestamp, ONE incoming bubble, another
    # timestamp. WeChat draws "昨天 22:18" over the message area, and that line
    # used to be read as a message - which made the newest "message" a timestamp
    # and the panel say "没分清谁说的".
    "d": {
        "title": "轻聊测试会话",
        "contact": "陆林晖",
        "thread": [
            ("stamp", "昨天 22:18"),
            ("other", "宝宝我到长沙啦"),
            ("stamp", "昨天 22:35"),
        ],
    },
}

CONTACTS = ["张三", "李四", "项目群", "妈妈", "老同学"]

# Which variant's conversation a contact name belongs to, so a test can switch
# the person inside a running window by dropping a name into a file.
CONTACT_VARIANT = {"张三": "a", "李四": "b", "刘工": "c"}


def parse_args():
    ap = argparse.ArgumentParser()
    ap.add_argument("--variant", default="a", choices=sorted(VARIANTS))
    ap.add_argument("--tag", default="", help="suffix for the %TEMP% files")
    ap.add_argument("--list-width", type=int, default=0,
                    help="width of the conversation list column in px "
                         "(defaults to the layout constant; pass a LARGER "
                         "number to act out a list the user dragged wider, "
                         "which is the case that used to feed the contact "
                         "list into the prompt)")
    return ap.parse_args()


def main():
    args = parse_args()
    variant = VARIANTS[args.variant]
    title = variant["title"]
    contact = variant["contact"]
    suffix = ("_" + args.tag) if args.tag else ""

    root = tk.Tk()
    root.title(title)
    panel = args.list_width or LEFT_PANEL
    w, h = 820, 700
    # Side by side when two conversations are running, so both stay visible and
    # the test can bring either one to the front.
    x = 80 if args.tag != "b" else 80 + w + 40
    root.geometry("%dx%d+%d+60" % (w, h, x))
    root.configure(bg="#ffffff")

    # ---- title strip (the window's own chrome)
    bar = tk.Frame(root, bg="#f7f7f7", height=TITLE_BAR)
    bar.place(x=0, y=0, width=w, height=TITLE_BAR)
    tk.Label(bar, text=title, bg="#f7f7f7", fg="#666666",
             font=("Microsoft YaHei UI", 9)).place(x=12, y=10)

    # ---- left rail + contact list
    left = tk.Frame(root, bg="#ededed")
    left.place(x=0, y=TITLE_BAR, width=panel, height=h - TITLE_BAR)
    tk.Label(left, text="搜索", bg="#ffffff", fg="#999999", font=("Microsoft YaHei UI", 9),
             anchor="w", padx=8).place(x=10, y=10, width=panel - 20, height=26)
    listed = [contact] + [c for c in CONTACTS if c != contact]
    for i, name in enumerate(listed):
        bg = "#d7d7d7" if i == 0 else "#ededed"
        tk.Label(left, text=name, bg=bg, fg="#333333", anchor="w", padx=12,
                 font=("Microsoft YaHei UI", 10)).place(x=0, y=48 + i * 46,
                                                        width=panel, height=44)
        # ...and the row's last-message preview, the way the real client draws it.
        tk.Label(left, text=PREVIEW.get(name, "好久不见！"), bg=bg, fg="#999999",
                 anchor="w", font=("Microsoft YaHei UI", 8)).place(
                     x=12, y=48 + i * 46 + 24, width=panel - 24, height=18)

    # ---- conversation header
    header = tk.Frame(root, bg="#f7f7f7")
    header.place(x=panel, y=TITLE_BAR, width=w - panel, height=HEADER)
    header_label = tk.Label(header, text=contact, bg="#f7f7f7", fg="#222222",
                            # 16pt on purpose: Windows' Chinese OCR does not
                            # detect a two-character name at 13pt at all (it
                            # detects it at 16). Real clients draw the header
                            # about this size, so this is the honest setting.
                            font=("Microsoft YaHei UI", 16))
    header_label.place(x=16, y=6)

    # ---- messages: incoming hug the left, outgoing hug the right
    area_top = TITLE_BAR + HEADER
    area_h = h - area_top - INPUT_BAR
    messages = tk.Frame(root, bg="#f5f5f5")
    messages.place(x=panel, y=area_top, width=w - panel, height=area_h)
    y = 18
    cursor = {"y": y}
    bubbles = []          # only the message labels - the blinking chrome must survive

    def add_incoming(text):
        label = tk.Label(messages, text=text, bg="#ffffff", fg="#222222", anchor="w",
                         justify="left", wraplength=300, padx=10, pady=7,
                         font=("Microsoft YaHei UI", 10))
        label.place(x=16, y=cursor["y"])
        bubbles.append(label)
        cursor["y"] += 46

    def add_outgoing(text):
        label = tk.Label(messages, text=text, bg="#95ec69", fg="#222222", anchor="e",
                         justify="right", wraplength=300, padx=10, pady=7,
                         font=("Microsoft YaHei UI", 10))
        label.place(x=w - panel - 340, y=cursor["y"], width=320)
        bubbles.append(label)
        cursor["y"] += 46

    def render_thread(thread):
        """Redraw the message area - used when a contact is switched."""
        # Destroy ONLY the messages. Wiping every child of `messages` also took
        # out the blinking timestamp/typing labels, and the very next tick then
        # raised on a dead widget and killed this poll loop - after which the
        # window silently stopped updating.
        for b in bubbles:
            b.destroy()
        del bubbles[:]
        cursor["y"] = 18
        for side, text in thread:
            if side == "stamp":
                # 12pt, not 8: the reader could not read a smaller timestamp at
                # all and turned "昨天 22:18" into "昨 22 ： 18", which is not a
                # faithful stand-in for what WeChat draws.
                label = tk.Label(messages, text=text, bg="#f5f5f5", fg="#a0a0a0",
                                 font=("Microsoft YaHei UI", 12))
                label.place(x=(w - panel) // 2 - 40, y=cursor["y"])
                bubbles.append(label)
                cursor["y"] += 34
            else:
                (add_outgoing if side == "me" else add_incoming)(text)

    render_thread(variant["thread"])

    # ---- input strip
    inp = tk.Frame(root, bg="#ffffff")
    inp.place(x=panel, y=h - INPUT_BAR, width=w - panel, height=INPUT_BAR)
    tk.Frame(root, bg="#e0e0e0").place(x=panel, y=h - INPUT_BAR,
        width=w - panel, height=1)
    box = tk.Text(inp, font=("Microsoft YaHei UI", 10), relief="flat", bg="#ffffff",
                  fg="#222222", insertbackground="#222222", highlightthickness=0)
    box.place(x=14, y=6, width=w - panel - 120, height=INPUT_BAR - 40)
    tk.Button(inp, text="发送", font=("Microsoft YaHei UI", 9), bg="#07c160",
              fg="white", relief="flat", padx=14, pady=4).place(
                  x=w - panel - 90, y=INPUT_BAR - 36)

    dump = os.path.join(tempfile.gettempdir(), "fake_chat_input%s.txt" % suffix)
    log = os.path.join(tempfile.gettempdir(), "fake_chat_log%s.txt" % suffix)
    inbox = os.path.join(tempfile.gettempdir(), "fake_chat_newmsg%s.txt" % suffix)
    strip_file = os.path.join(tempfile.gettempdir(), "fake_chat_strip%s.txt" % suffix)
    contact_file = os.path.join(tempfile.gettempdir(), "fake_chat_contact%s.txt" % suffix)
    seen = {"text": ""}
    strip = {"on": False, "phase": 0}
    who = {"name": contact}

    # The flickering chrome WeChat draws under the last message: a timestamp and
    # the "对方正在输入…" strip. Toggling them here reproduces the exact thing
    # that used to make the desktop panel throw its suggestions away.
    chrome_time = tk.Label(messages, text="14:33", bg="#f5f5f5", fg="#a0a0a0",
                           font=("Microsoft YaHei UI", 8))
    chrome_type = tk.Label(messages, text="对方正在输入…", bg="#f5f5f5", fg="#a0a0a0",
                           font=("Microsoft YaHei UI", 9))

    def note(msg):
        """Event trace, so a test can tell 'never delivered' from 'delivered'."""
        try:
            with open(log, "a", encoding="utf-8") as f:
                f.write("%s %.2f %s\n" % (time.strftime("%H:%M:%S"),
                                          time.time() % 100, msg))
        except Exception:
            pass

    root.bind("<FocusIn>", lambda e: note("focus root"))
    root.bind("<Button-1>", lambda e: note("click root %d,%d" % (e.x_root, e.y_root)))
    root.bind("<Key>", lambda e: note("key %s" % e.keysym))
    box.bind("<FocusIn>", lambda e: note("focus text"))
    box.bind("<Button-1>", lambda e: note("click text %d,%d" % (e.x_root, e.y_root)))
    box.bind("<<Paste>>", lambda e: note("paste event"))

    def poll():
        try:
            try:
                with open(dump, "w", encoding="utf-8") as f:
                    f.write(box.get("1.0", "end").strip())
            except Exception:
                pass
            # Drop a line into %TEMP%/fake_chat_newmsg.txt and it shows up here as
            # a new incoming message - which is how a test can time the whole
            # "message arrives -> candidates appear" path without a real chat app.
            try:
                with open(inbox, encoding="utf-8") as f:
                    text = f.read().strip()
                if text and text != seen["text"]:
                    seen["text"] = text
                    # One line per message, so a test can act out somebody sending
                    # two or three in a row the way people actually do.
                    for line in text.splitlines():
                        if line.strip():
                            add_incoming(line.strip())
            except Exception:
                pass
            # Blink the chrome on and off while fake_chat_strip.txt says "1".
            try:
                with open(strip_file, encoding="utf-8") as f:
                    strip["on"] = f.read().strip() == "1"
            except Exception:
                strip["on"] = False
            if strip["on"]:
                strip["phase"] = 1 - strip["phase"]
                chrome_time.place(x=16, y=cursor["y"])
                if strip["phase"]:
                    chrome_type.place(x=16, y=cursor["y"] + 20)
                else:
                    chrome_type.place_forget()
            else:
                chrome_time.place_forget()
                chrome_type.place_forget()
            # Switching person inside the same window: drop a contact name into
            # %TEMP%/fake_chat_contact<suffix>.txt and the header and thread
            # change, exactly like clicking another chat in WeChat's list.
            try:
                with open(contact_file, encoding="utf-8") as f:
                    want = f.read().strip()
                if want and want != who["name"] and want in CONTACT_VARIANT:
                    who["name"] = want
                    header_label.configure(text=want)
                    render_thread(VARIANTS[CONTACT_VARIANT[want]]["thread"])
            except Exception:
                pass
        except Exception:
            # A test window must never stop updating because one redraw hiccupped.
            pass
        root.after(500, poll)

    poll()
    box.focus_set()
    root.mainloop()


if __name__ == "__main__":
    main()

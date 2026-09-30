# -*- coding: utf-8 -*-
"""Find the chat window on screen, cut the conversation out of it, type into it.

Why this module exists
----------------------
The first version made the user drag a rectangle over their chat window before
the tool did anything, and that selection broke as soon as the window moved or
resized. This module instead:

  1. finds the chat window itself (by window class + title of the known desktop
     chat clients, with a "pick from the list" fallback),
  2. works out where the messages are inside that window from per-client layout
     ratios (see LAYOUTS) scaled by the monitor DPI,
  3. grabs just that area with PrintWindow, so the capture is correct even when
     the chat window is partly covered by something else,
  4. can put text into the chat's input box (clipboard + Ctrl+V) for the
     one-click "fill" button - it never presses Enter.

Plain ctypes only; no third-party packages.
"""

from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import hashlib
import os
import re
import struct
import tempfile
import time

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32


def set_dpi_awareness():
    """Work in physical pixels, so window rects, capture and clicks all agree."""
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)   # PER_MONITOR_AWARE_V2
    except Exception:
        try:
            user32.SetProcessDPIAware()
        except Exception:
            pass


def virtual_screen():
    """(x, y, w, h) of the whole desktop, in physical pixels."""
    gsm = user32.GetSystemMetrics
    return gsm(76), gsm(77), gsm(78), gsm(79)


# ---------------------------------------------------------------- window list

class WindowInfo:
    __slots__ = ("hwnd", "title", "cls", "x", "y", "w", "h", "pid")

    def __init__(self, hwnd, title, cls, x, y, w, h, pid):
        self.hwnd, self.title, self.cls = hwnd, title, cls
        self.x, self.y, self.w, self.h, self.pid = x, y, w, h, pid

    def __repr__(self):
        return "WindowInfo(%r, %r, %dx%d)" % (self.title, self.cls, self.w, self.h)


_WNDENUMPROC = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)


def list_windows(min_w=320, min_h=320, visible_only=True):
    """Every top-level window big enough to plausibly be a chat client."""
    found = []

    def cb(hwnd, _lp):
        try:
            if visible_only and not user32.IsWindowVisible(hwnd):
                return True
            title_buf = ctypes.create_unicode_buffer(512)
            user32.GetWindowTextW(hwnd, title_buf, 512)
            cls_buf = ctypes.create_unicode_buffer(256)
            user32.GetClassNameW(hwnd, cls_buf, 256)
            rect = wt.RECT()
            if not user32.GetWindowRect(hwnd, ctypes.byref(rect)):
                return True
            w, h = rect.right - rect.left, rect.bottom - rect.top
            if w < min_w or h < min_h:
                return True
            pid = wt.DWORD()
            user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            found.append(WindowInfo(hwnd, title_buf.value, cls_buf.value,
                                    rect.left, rect.top, w, h, pid.value))
        except Exception:
            pass
        return True

    user32.EnumWindows(_WNDENUMPROC(cb), None)
    return found


def window_rect(hwnd):
    r = wt.RECT()
    if not user32.GetWindowRect(hwnd, ctypes.byref(r)):
        return None
    return (r.left, r.top, r.right - r.left, r.bottom - r.top)


def client_rect(hwnd):
    """The content area in SCREEN coordinates: (x, y, w, h), or None.

    Everything below is measured against the client area, not the window rect.
    The difference matters: a Qt window like WeChat 4.x has almost no OS frame
    while a Tk/Win32 window hides ~31px of title bar inside its window rect, and
    using the wrong one shifts the whole capture down by that much.
    """
    r = wt.RECT()
    if not user32.GetClientRect(hwnd, ctypes.byref(r)):
        return None
    w, h = r.right - r.left, r.bottom - r.top
    if w <= 0 or h <= 0:
        return None
    origin = wt.POINT(0, 0)
    if not user32.ClientToScreen(hwnd, ctypes.byref(origin)):
        return None
    return (origin.x, origin.y, w, h)


def window_title(hwnd):
    buf = ctypes.create_unicode_buffer(512)
    user32.GetWindowTextW(hwnd, buf, 512)
    return buf.value


def process_name(pid):
    """Executable name (lowercase, no path) behind a pid, or "".

    Needed because chat clients and Chromium browsers all share the
    `Chrome_WidgetWin_1` window class, so the class alone proves nothing.
    """
    PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, int(pid))
    if not handle:
        return ""
    try:
        size = wt.DWORD(1024)
        buf = ctypes.create_unicode_buffer(1024)
        if kernel32.QueryFullProcessImageNameW(handle, 0, buf, ctypes.byref(size)):
            return os.path.basename(buf.value).lower()
        return ""
    finally:
        kernel32.CloseHandle(handle)


# ------------------------------------------------------------------- layouts
#
# One entry per desktop chat client. The numbers are pixel offsets inside the
# window rectangle at 96 DPI, measured from a real 601x694 WeChat 4.x window:
#
#   +--------------------------------------------------+  <- window rect
#   | window controls, ~38px                            |
#   +--------+-----------------------------------------+
#   | rail + | conversation header, ~47px               |
#   | chat   +-----------------------------------------+
#   | list   |                                          |
#   | (230)  | MESSAGES  <- this is what gets OCR'd     |
#   |        |                                          |
#   |        +-----------------------------------------+
#   |        | input area, ~95px                        |
#   +--------+-----------------------------------------+
#
# left / top / header / bottom are measured inside the CLIENT area and scaled by
# dpi/96 at use time, because these clients scale their chrome with the DPI.

class Layout:
    def __init__(self, ident, name, match, left, top, header, bottom,
                 detect_left=False):
        self.id, self.name, self.match = ident, name, match
        self.left, self.top = left, top      # chrome left of / above messages
        self.header, self.bottom = header, bottom
        # Find the list/message boundary in the picture instead of trusting
        # [left]. Every one of these clients lets the user drag the conversation
        # list wider, and when it is wider than [left] the reader was cropping
        # part of the LIST into the "conversation" - contact names and message
        # previews ended up in the prompt, which is how replies came out about
        # somebody else entirely.
        # LITECHAT_NO_LEFT_DETECT=1 turns the boundary detection off, which is
        # what the before/after regression uses (and a way out if some client
        # ever confuses the detector).
        self.detect_left = detect_left and not os.environ.get("LITECHAT_NO_LEFT_DETECT")

    def message_rect(self, win, scale=1.0):
        """Message area in window-relative pixels."""
        mx = int(round(self.left * scale))
        my = int(round((self.top + self.header) * scale))
        mw = win[2] - mx
        mh = win[3] - my - int(round(self.bottom * scale))
        return (mx, my, max(1, mw), max(1, mh))

    def header_rect(self, win, scale=1.0):
        """Conversation-title strip (where the contact name sits)."""
        mx = int(round(self.left * scale))
        my = int(round(self.top * scale))
        return (mx, my, max(1, win[2] - mx), max(1, int(round(self.header * scale))))

    def input_point(self, win, scale=1.0):
        """A safe spot inside the chat's text input box, window coordinates."""
        ix = int(round(self.left * scale)) + int(round(70 * scale))
        # Aim at the text row of the input strip, not the toolbar under it.
        drop = max(8, int(round(self.bottom * scale * 0.13)))
        iy = win[3] - int(round(self.bottom * scale)) + drop
        return (ix, max(0, min(win[3] - 1, iy)))


def _wechat4(cls, title):
    # WeChat 4.x desktop is Qt-based; the window class carries the Qt version.
    return cls.startswith("Qt5") and cls.endswith("QWindowIcon") and "微信" in title


def _wechat3(cls, title):
    return cls == "WeChatMainWndForPC"


def _qqnt(cls, title):
    # QQ NT / TIM are Electron too, so the class is shared with every Chromium
    # browser. Only an exact client title counts, and the browser check below
    # catches the rest.
    return cls.startswith("Chrome_WidgetWin") and title.strip() in (
        "QQ", "TIM", "腾讯QQ", "QQ办公简洁版", "QQ NT")


def _lark(cls, title):
    return cls in ("LarkMainWindow", "FeishuMainWindow") or title.strip() in ("飞书", "Lark")


def _dingtalk(cls, title):
    return cls.startswith("DingTalk") or title.strip() in ("钉钉", "DingTalk")


# WeChat 4.x numbers come from a real 601x694 window: client (585x686) with its
# own 38px title strip, a 230px rail+list, a 47px conversation header and a 95px
# input strip (so 87px of the client's height sits below the messages).
LAYOUTS = [
    # bottom 130, not the measured 87. Measured again on a real 585x686 WeChat
    # 4.x window: the composer's box starts 131px above the client's bottom, so
    # at 87 (and even at 100) a slice of it was inside the picture - and the
    # caret/draft in it was read as the newest incoming message, which the model
    # then answered instead of the actual conversation.
    Layout("wechat4", "微信", _wechat4, left=222, top=38, header=47, bottom=130,
           detect_left=True),
    Layout("wechat3", "微信", _wechat3, left=250, top=0, header=52, bottom=150,
           detect_left=True),
    Layout("qqnt", "QQ / TIM", _qqnt, left=318, top=34, header=56, bottom=170,
           detect_left=True),
    Layout("lark", "飞书", _lark, left=320, top=34, header=48, bottom=150,
           detect_left=True),
    Layout("dingtalk", "钉钉", _dingtalk, left=310, top=34, header=52, bottom=150,
           detect_left=True),
]

# Used when a window is picked by hand whose class we do not know: assume a
# three-column chat layout with a header strip and an input strip.
GENERIC_LAYOUT = Layout("generic", "聊天窗口", lambda c, t: True,
                        left=280, top=34, header=50, bottom=150,
                        detect_left=True)


def match_layout(cls, title):
    """The layout whose signature this window matches, or None."""
    if title.strip() == OWN_TITLE:
        return None                      # our own panel is never a target
    for lay in LAYOUTS:
        try:
            if lay.match(cls, title):
                return lay
        except Exception:
            continue
    return None


# Processes that share a chat client's window class but never are one.
OWN_TITLE = "轻聊助手"

BROWSER_PROCESSES = {
    "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe", "opera.exe",
    "vivaldi.exe", "360se.exe", "360chrome.exe", "sogouexplorer.exe",
    "qqbrowser.exe", "iexplore.exe", "electron.exe", "code.exe",
    "explorer.exe", "applicationframehost.exe",
}


def is_chat_window(info):
    """(layout, None) if this window looks like a chat client, else (None, why)."""
    lay = match_layout(info.cls, info.title)
    if lay is None:
        return None, "not a known chat window"
    proc = process_name(info.pid)
    if proc in BROWSER_PROCESSES:
        return None, "browser process"
    if lay.id == "qqnt" and not proc.startswith(("qq", "tim")):
        return None, "not the QQ process"
    return lay, None


def detect_content_left(buf, w, h, y0, y1, fallback):
    """Window-local x where the conversation list ends and the messages begin.

    Returns [fallback] when the picture does not clearly show a boundary.

    The list column is one flat panel - white in WeChat 4.x, light grey in the
    older client - while the conversation behind the bubbles is the wallpaper
    (a grey, or a photo). Scanning a band of rows for the last column that is
    still panel-coloured and whose right neighbour is not gives the boundary.

    The constant in [Layout] can only ever be a guess: the list column is
    user-resizable, and when it is dragged wider than the constant the reader
    was cropping part of the LIST into the conversation. The prompt then held
    contact names and unread previews, and the model answered about those -
    exactly the "牛头不对马嘴" this is here to stop.
    """
    if w < 160 or h < 80 or buf is None:
        return fallback
    try:
        hi = min(w - 4, int(w * 0.62))
        lo = max(4, min(int(fallback * 0.45), hi - 40))
        if hi - lo < 30 or y1 - y0 < 40:
            return fallback
        rows = [y0 + (y1 - y0) * i // 10 for i in range(1, 10)]

        def rgb(x, y):
            o = (y * w + x) * 4
            return buf[o + 2], buf[o + 1], buf[o]

        # The panel's own colour, sampled from the left part of the strip: that
        # is inside the list whatever the constant claims.
        span = max(3, (hi - lo) // 5)
        samples = [rgb(x, y) for y in rows for x in range(lo, lo + span, 3)]
        if not samples:
            return fallback
        panel = tuple(sorted(s[c] for s in samples)[len(samples) // 2]
                      for c in range(3))

        def same(p):
            return (abs(p[0] - panel[0]) <= 5 and abs(p[1] - panel[1]) <= 5
                    and abs(p[2] - panel[2]) <= 5)

        share = [sum(1 for y in rows if same(rgb(x, y))) / float(len(rows))
                 for x in range(lo, hi)]
        edge = None
        for i in range(len(share) - 3):
            if share[i] >= 0.75 and share[i + 2] <= 0.40:
                edge = lo + i
        if edge is None:
            return fallback
        if edge < int(w * 0.10) or edge > int(w * 0.62):
            return fallback
        return edge
    except Exception:
        return fallback


# ------------------------------------------------------------------ discovery

class ChatWindow:
    """A live chat window plus the layout used to read it."""

    def __init__(self, hwnd, layout, manual_rect=None):
        self.hwnd = hwnd
        self.layout = layout
        self.manual_rect = manual_rect    # (x, y, w, h) screen coords, if the
                                          # user overrode the automatic area

    @property
    def alive(self):
        return bool(user32.IsWindow(self.hwnd))

    @property
    def title(self):
        return window_title(self.hwnd)

    @property
    def rect(self):
        return window_rect(self.hwnd)

    def dpi_scale(self):
        try:
            dpi = user32.GetDpiForWindow(self.hwnd) or 96
        except Exception:
            dpi = 96
        return dpi / 96.0

    def message_rect(self):
        """The message area, in SCREEN coordinates."""
        win = self.client_rect()
        if not win:
            return None
        if self.manual_rect:
            return self.manual_rect
        mx, my, mw, mh = self.layout.message_rect(win, self.dpi_scale())
        return (win[0] + mx, win[1] + my, mw, mh)

    def header_rect(self):
        """The conversation-title strip, in SCREEN coordinates."""
        win = self.client_rect()
        if not win:
            return None
        mx, my, mw, mh = self.layout.header_rect(win, self.dpi_scale())
        return (win[0] + mx, win[1] + my, mw, mh)

    def title_rect(self):
        """The title strip PLUS the first lines under it.

        A crop holding nothing but the contact name is often not read at all:
        Windows' recogniser needs a certain amount of text in the picture to
        lock onto, and "张三" alone in a wide short strip comes back empty.
        Widening the crop downwards gives it that text; the caller keeps only
        the lines that sit inside the strip itself.

        Returns (rect, header_height) in screen pixels, or (None, 0).
        """
        head = self.header_rect()
        if not head:
            return None, 0
        scale = self.dpi_scale()
        extra = max(40, int(round(90 * scale)))
        win = self.client_rect() or (0, 0, 0, 0)
        room = max(0, (win[1] + win[3]) - (head[1] + head[3]))
        return (head[0], head[1], head[2], head[3] + min(extra, room)), head[3]

    def client_rect(self):
        """The window's content area in screen coordinates."""
        return client_rect(self.hwnd)

    def capture_regions(self, rects, names=None, with_pixels=False,
                        refine_left=None):
        """One PrintWindow, cropped into several regions.

        Returns [(path, digest, pixels)] in the same order as `rects`; the parts
        are None when that region could not be cropped.

        * digest - the caller compares it with the previous poll and skips OCR
          entirely when the pixels have not moved, which is what keeps an idle
          chat window from costing a recognition pass every second.
        * pixels - (w, h, bgra) when `with_pixels`, so the caller can look at
          bubble colours to tell "my message" from "theirs".

        PrintWindow rather than a screen grab, so the picture is right even when
        another window covers the chat.

        [refine_left] is a per-rect flag (default: all True). Only the message
        area uses the detected boundary: it is what the model is shown, so a
        conversation list bleeding into it is not survivable. The header strip
        keeps its measured constant - a one-pixel shift there makes Windows'
        recogniser drop a two-character conversation name altogether, and the
        name was already being read correctly from the constant.
        """
        win = self.rect
        if not win:
            return []
        shot = _print_window_bgra(self.hwnd, win[2], win[3])
        if shot is None:
            return []
        buf, w, h = shot
        stride = w * 4
        tmp = tempfile.gettempdir()

        # Where the message area really starts, when the picture shows it. The
        # layout's constant is only a hint (see detect_content_left).
        #
        # Everything here is in WINDOW coordinates (the shot is the whole
        # window, title bar included), while the layout constant is measured
        # inside the CLIENT area - so the frame's own left edge has to be added
        # before the two can be compared. Getting that wrong by the width of the
        # border is what made the first version of this fix silently do nothing.
        client = self.client_rect()
        frame_dx = (client[0] - win[0]) if client else 0
        base_left = int(round(self.layout.left * self.dpi_scale()))
        base_local = base_left + frame_dx
        content_left = None
        if getattr(self.layout, "detect_left", False):
            first = next((t for t in rects if t), None)
            if first:
                fy = max(0, min(h - 1, int(first[1] - win[1])))
                fh = max(1, min(int(first[3]), h - fy))
                content_left = detect_content_left(buf, w, h, fy, fy + fh,
                                                   base_local)

        out = []
        for i, target in enumerate(rects):
            if not target:
                out.append((None, None, None))
                continue
            # screen -> window-local crop, clamped so OCR never gets a bad box
            cx = max(0, int(target[0] - win[0]))
            cy = max(0, int(target[1] - win[1]))
            # The crop's right edge is fixed by the requested rect (clamped to
            # the client area), so moving the left edge right SHRINKS the
            # picture instead of sliding it into the window frame.
            right = int(target[0] - win[0]) + int(target[2])
            if client:
                right = min(right, (client[0] - win[0]) + client[2])
            right = min(right, w)
            # Only rects anchored at the content edge (the message area and the
            # conversation header) are moved; anything else keeps its own box.
            use_refine = (refine_left[i] if refine_left and i < len(refine_left)
                          else True)
            if (use_refine and content_left is not None
                    and abs(cx - base_local) <= 20):
                cx = content_left
            cw = max(0, right - cx)
            ch = min(int(target[3]), h - cy)
            if cw < 8 or ch < 8:
                out.append((None, None, None))
                continue
            rows = [buf[y * stride + cx * 4: y * stride + cx * 4 + cw * 4]
                    for y in range(cy, cy + ch)]
            cropped = b"".join(rows)
            name = (names[i] if names and i < len(names)
                    else ("litechat_cap.bmp" if i == 0 else "litechat_cap%d.bmp" % i))
            path = os.path.join(tmp, name)
            _write_bmp32(path, cropped, cw, ch)
            out.append((path, hashlib.sha1(cropped).hexdigest(),
                        (cw, ch, cropped) if with_pixels else None))
        return out

    def capture_bmp(self, rect=None, path=None):
        """Single-region convenience wrapper. Returns the file path, or None."""
        got = self.capture_regions([rect or self.message_rect()],
                                   [os.path.basename(path)] if path else None)
        if not got:
            return None
        first = got[0][0]
        if first and path and first != path:
            os.replace(first, path)
            return path
        return first

    def input_screen_point(self):
        """Where to click inside the chat's input box, in screen coordinates."""
        win = self.client_rect()
        if not win:
            return None
        ix, iy = self.layout.input_point(win, self.dpi_scale())
        return (win[0] + ix, win[1] + iy)

    def paste_text(self, text):
        """Put `text` into the chat's input box. Never sends."""
        if not self.alive:
            return False, "聊天窗口已经关掉了"
        if not self.rect:
            return False, "读不到聊天窗口的位置"
        _set_clipboard(text)
        force_foreground(self.hwnd)
        pt = self.input_screen_point()
        if pt:
            click_at(pt[0], pt[1])
        send_ctrl_v()
        return True, "已填入聊天输入框，确认后自己按发送"


def find_chat_window(exclude=(OWN_TITLE,)):
    """The best guess at "the chat window the user is looking at".

    Order of preference:
      1. the window that is in the foreground right now (that is the one the
         user is reading),
      2. otherwise the largest matching chat window,
      3. otherwise whatever was remembered for this session.
    Returning None means "ask the user which window it is".
    """
    candidates = list_chat_windows(exclude)
    if not candidates:
        return None
    fg = user32.GetForegroundWindow()
    for info, lay in candidates:
        if info.hwnd == fg:
            return ChatWindow(info.hwnd, lay)
    info, lay = max(candidates, key=lambda c: c[0].w * c[0].h)
    return ChatWindow(info.hwnd, lay)


def foreground_chat_window(exclude=(OWN_TITLE,)):
    """(WindowInfo, Layout) only when the FOREGROUND window is a chat client.

    [find_chat_window] deliberately falls back to "the largest one", which is
    right when the app starts and wrong when it is already attached: if the user
    is reading a browser, the largest chat window in the background is not the
    one they switched to. This says "nothing" instead of guessing, so an
    already-attached app only ever moves to a window the user actually brought
    forward.
    """
    fg = user32.GetForegroundWindow()
    if not fg:
        return None
    for info, lay in list_chat_windows(exclude):
        if info.hwnd == fg:
            return info, lay
    return None


def foreground_window_info():
    """The WindowInfo of the foreground window, or None.

    Unlike [foreground_chat_window] this does not require a *recognised* client:
    the app also reads windows the user picked by hand, and the "follow me to
    the window I just switched to" rule has to work for those too.
    """
    fg = user32.GetForegroundWindow()
    if not fg or not user32.IsWindowVisible(fg):
        return None
    for info in list_windows():
        if info.hwnd == fg:
            return info
    return None


def list_chat_windows(exclude=(OWN_TITLE,)):
    """[(WindowInfo, Layout)] for every window that looks like a chat client."""
    out = []
    for info in list_windows():
        if any(t in info.title for t in exclude):
            continue
        lay, _why = is_chat_window(info)
        if lay is not None:
            out.append((info, lay))
    return out


def attach_window(info, layout=None):
    """Wrap a manually picked window with a layout."""
    return ChatWindow(info.hwnd, layout or GENERIC_LAYOUT)


# ------------------------------------------------------------- win32 plumbing

def force_foreground(hwnd):
    """SetForegroundWindow, with the AttachThreadInput dance that makes it work
    when the caller is a background process."""
    try:
        if user32.IsIconic(hwnd):
            user32.ShowWindow(hwnd, 9)          # SW_RESTORE
        fg = user32.GetForegroundWindow()
        if fg == hwnd:
            return True
        cur = ctypes.windll.kernel32.GetCurrentThreadId()
        tgt = user32.GetWindowThreadProcessId(fg, None)
        attached = False
        if tgt and tgt != cur:
            attached = bool(user32.AttachThreadInput(cur, tgt, True))
        user32.BringWindowToTop(hwnd)
        user32.SetForegroundWindow(hwnd)
        if attached:
            user32.AttachThreadInput(cur, tgt, False)
        return user32.GetForegroundWindow() == hwnd
    except Exception:
        return False


def click_at(x, y):
    user32.SetCursorPos(int(x), int(y))
    time.sleep(0.08)
    user32.mouse_event(0x0002, 0, 0, 0, 0)      # LEFTDOWN
    time.sleep(0.05)
    user32.mouse_event(0x0004, 0, 0, 0, 0)      # LEFTUP
    time.sleep(0.08)


def send_ctrl_v(gap=0.06):
    """Inject Ctrl+V.

    The small gaps are not decoration: firing all four events back to back
    without them makes the target miss the combination entirely (verified by
    pasting into a test window - zero-delay produced nothing, 60-80ms worked).
    """
    VK_CONTROL, VK_V, KEYUP = 0x11, 0x56, 0x0002
    user32.keybd_event(VK_CONTROL, 0, 0, 0)
    time.sleep(gap)
    user32.keybd_event(VK_V, 0, 0, 0)
    time.sleep(gap)
    user32.keybd_event(VK_V, 0, KEYUP, 0)
    time.sleep(gap)
    user32.keybd_event(VK_CONTROL, 0, KEYUP, 0)


def _set_clipboard(text):
    """Clipboard through Win32 directly, so no extra window has to be created."""
    CF_UNICODETEXT = 13
    GMEM_MOVEABLE = 0x0002
    kernel32 = ctypes.windll.kernel32

    # Handles are pointer-sized; without explicit prototypes ctypes truncates
    # them to 32-bit ints and the GlobalLock/SetClipboardData calls fail.
    kernel32.GlobalAlloc.restype = ctypes.c_void_p
    kernel32.GlobalAlloc.argtypes = [wt.UINT, ctypes.c_size_t]
    kernel32.GlobalLock.restype = ctypes.c_void_p
    kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
    kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    user32.OpenClipboard.argtypes = [ctypes.c_void_p]
    user32.OpenClipboard.restype = wt.BOOL
    user32.SetClipboardData.restype = ctypes.c_void_p
    user32.SetClipboardData.argtypes = [wt.UINT, ctypes.c_void_p]

    if not user32.OpenClipboard(None):
        return False
    try:
        if not user32.EmptyClipboard():
            return False
        data = text.encode("utf-16-le") + b"\x00\x00"
        handle = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
        if not handle:
            return False
        ptr = kernel32.GlobalLock(handle)
        if not ptr:
            return False
        try:
            ctypes.memmove(ptr, data, len(data))
        finally:
            kernel32.GlobalUnlock(handle)
        # After SetClipboardData succeeds the system owns the block - do not
        # free it here.
        return bool(user32.SetClipboardData(CF_UNICODETEXT, handle))
    finally:
        user32.CloseClipboard()


class _BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wt.DWORD), ("biWidth", ctypes.c_long),
                ("biHeight", ctypes.c_long), ("biPlanes", wt.WORD),
                ("biBitCount", wt.WORD), ("biCompression", wt.DWORD),
                ("biSizeImage", wt.DWORD), ("biXPelsPerMeter", ctypes.c_long),
                ("biYPelsPerMeter", ctypes.c_long), ("biClrUsed", wt.DWORD),
                ("biClrImportant", wt.DWORD)]


class _BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", _BITMAPINFOHEADER), ("bmiColors", wt.DWORD * 3)]


def _bitmap_info(w, h):
    info = _BITMAPINFO()
    info.bmiHeader.biSize = ctypes.sizeof(_BITMAPINFOHEADER)
    info.bmiHeader.biWidth = w
    info.bmiHeader.biHeight = h          # negative = top-down rows
    info.bmiHeader.biPlanes = 1
    info.bmiHeader.biBitCount = 32
    info.bmiHeader.biCompression = 0     # BI_RGB
    return info


def _print_window_bgra(hwnd, w, h):
    """Capture a window into a raw BGRA buffer. Returns (bytes, w, h) or None."""
    hdc = user32.GetWindowDC(hwnd)
    if not hdc:
        return None
    mdc = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    if not mdc or not bmp:
        user32.ReleaseDC(hwnd, hdc)
        return None
    old = gdi32.SelectObject(mdc, bmp)
    try:
        # PW_RENDERFULLCONTENT (2) is what makes this work for GPU-composited
        # windows such as Qt (WeChat 4.x) and Electron (QQ NT).
        if not user32.PrintWindow(hwnd, mdc, 2):
            user32.PrintWindow(hwnd, mdc, 0)
        info = _bitmap_info(w, -h)
        buf = ctypes.create_string_buffer(w * h * 4)
        gdi32.GetDIBits(mdc, bmp, 0, h, buf, ctypes.byref(info), 0)
        return (buf.raw, w, h)
    except Exception:
        return None
    finally:
        gdi32.SelectObject(mdc, old)
        gdi32.DeleteObject(bmp)
        gdi32.DeleteDC(mdc)
        user32.ReleaseDC(hwnd, hdc)


def _write_bmp32(path, bgra, w, h):
    """32-bit BGRA -> uncompressed .bmp. The Windows imaging layer reads this
    directly, which is why the OCR helper needs no PNG encoder of its own."""
    header = b"BM" + struct.pack("<IHHI", 14 + 40 + len(bgra), 0, 0, 14 + 40)
    header += struct.pack("<IiiHHIIiiII", 40, w, -h, 1, 32, 0, len(bgra),
                          2835, 2835, 0, 0)
    with open(path, "wb") as f:
        f.write(header)
        f.write(bgra)


# --------------------------------------------------------- message reassembly

# Lines a chat window draws that are not somebody's words. They appear, change
# and disappear on their own - the "对方正在输入…" strip in particular blinks in
# and out while the other person types - and every flicker used to register as a
# brand new message, which meant the panel kept throwing the suggestions away
# and going back to "正在想…" before anyone could click one.
_NOISE_LINE = re.compile(
    r"^(?:"
    # Every one of these carries an OPTIONAL trailing clock, because that is how
    # a chat client actually draws a date separator: "昨天 22:18",
    # "星期一 22:18", "9月29日 22:18". Without the clock the whole line failed
    # to match, the timestamp was read as a message, and the newest "message"
    # on screen became a time - which is what made the panel say "没分清谁说的"
    # and left the model with nothing to answer.
    r"(?:昨天|今天|前天|星期[一二三四五六日天]|周[一二三四五六日天])"
    r"(?:\s*(?:上午|下午|凌晨|中午|晚上))?"
    r"(?:\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?)?"
    r"|\d{1,2}\s*月\s*\d{1,2}\s*日"
    r"(?:\s*(?:上午|下午|星期[一二三四五六日天]|周[一二三四五六日天]))?"
    r"(?:\s*\d{1,2}\s*[:：]\s*\d{2}(?:\s*[:：]\s*\d{2})?)?"
    r"|\d{4}\s*年\s*\d{1,2}\s*月\s*\d{1,2}\s*日"
    r"(?:\s*(?:上午|下午))?(?:\s*\d{1,2}\s*[:：]\s*\d{2})?"
    r"|(?:(?:上午|下午|凌晨|中午|晚上)\s*)?"
    # The recogniser often puts a space between the digits of a clock ("15:46"
    # comes back as "1 5 ： 4 6"), and requiring them adjacent let the timestamp
    # survive the filter and become the newest "message" on screen.
    r"\d(?:\s*\d)?\s*[:：]\s*\d(?:\s*\d)?"
    r"(?:\s*[:：]\s*\d(?:\s*\d))?\s*(?:上午|下午|AM|PM|am|pm)?"
    r"|[^\w\u4e00-\u9fff]+"                                       # pure symbols
    r")$",
    re.IGNORECASE)

# Only applied to SHORT lines, so a real message that happens to contain one of
# these words ("我看了，已读不回是吧") is never thrown away.
_NOISE_PHRASES = (
    "正在输入", "撤回了一条消息", "以上是打招呼", "以下为新消息",
    "消息已发出，但被对方拒收", "对方已开启朋友验证", "拍了拍",
    "该消息已过期", "已被领取", "通话时长", "已取消", "语音通话",
    "视频通话", "你已添加了", "现在可以开始聊天了", "新的朋友",
)
# A system strip is the phrase plus at most a nickname around it; anything
# longer is somebody actually saying something that contains the words.
_NOISE_SLACK = 6

# The typing strip and the timestamps get read badly often enough that matching
# the phrase exactly is not enough - "对方正在输入…" comes back as things like
# "三在窪入一". The giveaway is that the garble keeps the characters that carry
# the strip's shape, so a very short line holding two *different* ones of these
# is chrome. Two different ones, not two hits: "现在在路上了" is a real reply.
# Deliberately limited to 6 characters: a real sentence that mentions typing
# ("我在输入法里找不到") is far longer and is left alone.
_TYPING_MARK_CHARS = "在入输正"
_TYPING_MAX_LEN = 6


def _looks_like_typing_garble(t: str) -> bool:
    if not 1 <= len(t) <= _TYPING_MAX_LEN:
        return False
    marks = {ch for ch in t if ch in _TYPING_MARK_CHARS}
    return len(marks) >= 2


def is_noise_line(text):
    """True for chrome the chat window draws, not something a person said."""
    t = (text or "").strip()
    if not t:
        return True
    if _NOISE_LINE.match(t):
        return True
    for phrase in _NOISE_PHRASES:
        if phrase in t and len(t) <= len(phrase) + _NOISE_SLACK:
            return True
    return _looks_like_typing_garble(t)

def group_lines(lines, area_width, gap_factor=1.35, drop_noise=True, classify=None):
    """OCR lines -> [(side, text)] messages, plus whether both sides were seen.

    Desktop clients put incoming bubbles on the left and outgoing ones hugging
    the right, so the horizontal centre of a line says who spoke. When every
    line lands on the same side the guess is meaningless (a very narrow window,
    or a stretch where only one person talked), so the second return value says
    so instead of handing back a fabricated 我/对方 split.

    `classify(x, y, w, h)` may return "me" / "other" to override that geometry -
    the desktop caller uses it to read the bubble colour, which is a far better
    signal than position for the one case that matters most (telling the user's
    own message apart from the other person's).
    """
    usable = []
    for ln in lines:
        text = (ln.get("t") or "").strip()
        if not text:
            continue
        if drop_noise and is_noise_line(text):
            continue
        usable.append({"t": text,
                       "x": int(ln.get("x") or 0),
                       "y": int(ln.get("y") or 0),
                       "w": int(ln.get("w") or 0),
                       "h": max(1, int(ln.get("h") or 1))})
    usable.sort(key=lambda r: (r["y"], r["x"]))
    if not usable:
        return [], False

    groups = []
    current = [usable[0]]
    for prev, item in zip(usable, usable[1:]):
        gap = item["y"] - (prev["y"] + prev["h"])
        if gap > max(prev["h"], 8) * gap_factor:
            groups.append(current)
            current = []
        current.append(item)
    if current:
        groups.append(current)

    out = []
    confident = []
    for g in groups:
        text = " ".join(item["t"] for item in g).strip()
        if not text:
            continue
        left = min(item["x"] for item in g)
        right = max(item["x"] + item["w"] for item in g)
        top = min(item["y"] for item in g)
        bottom = max(item["y"] + item["h"] for item in g)
        side = None
        sure = False
        if classify is not None:
            try:
                side = classify(left, top, right - left, bottom - top)
            except Exception:
                side = None
            # The bubble colour is a direct reading, not an inference: a green
            # bubble IS the user's, a white/grey one is not.
            sure = side in ("me", "other")
        if side not in ("me", "other"):
            side = None
        if side is None:
            side, sure = side_from_position_ex(left, right, area_width)
        out.append((side, text))
        confident.append(sure)

    sides = {s for s, _ in out}
    both = "me" in sides and "other" in sides
    # One direction on screen is not the same as "unknown". A single bubble that
    # hugs the left edge is the other person's in every client, and a bubble the
    # colour reader called is answered just as plainly. Calling that "没分清谁
    # 说的" threw away the anchor - the model then had to guess which line to
    # answer, and a screen showing one short message produced nothing usable.
    known = both or (bool(out) and all(confident))
    return out, known


def side_from_position(left, right, area_width):
    """Who spoke, judged from where the bubble sits.

    Used when the bubble colour says nothing (a wallpaper, a dark theme, a
    custom skin). The LEFT gap is checked first on purpose: chat clients put the
    other person's bubbles hard against the left and the user's own hard against
    the right, and a long message from either side reaches past the middle - so
    "which edge does it hug" beats "which side is its centre on", which is what
    this used to ask and what mislabelled long messages.
    """
    if area_width <= 0:
        return "other"
    edge = max(8.0, area_width * 0.12)
    if left <= edge:
        return "other"
    if (area_width - right) <= edge:
        return "me"
    centre = (left + right) / 2.0
    band = max(8.0, area_width * 0.06)
    mid = area_width / 2.0
    if centre > mid + band:
        return "me"
    # Genuinely ambiguous. "other" is the safer guess: a wrong "me" would mean
    # the app never replies at all.
    return "other"


def side_from_position_ex(left, right, area_width):
    """[(side, confident)] for a line - see [side_from_position].

    "Confident" means the bubble visibly hugs one edge, which is what every
    mainstream client does with the two sides. The dead-centre fallback is a
    guess, so it is not confident: a screen holding one centred line cannot be
    used to claim the sides are known.
    """
    if area_width <= 0:
        return "other", False
    edge = max(8.0, area_width * 0.12)
    if left <= edge:
        return "other", True
    if (area_width - right) <= edge:
        return "me", True
    return side_from_position(left, right, area_width), False

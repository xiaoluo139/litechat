# -*- coding: utf-8 -*-
"""Does the floating window come back when the system takes it away?

Reported symptom: "手机端的悬浮窗很不稳定 悬浮窗按钮总是不见，还经常点不开".

Three things are checked here, on a real device or emulator, against the window
manager - not against the app's own idea of what it is showing:

  1. the bubble exists
  2. tapping it opens the panel
  3. the window is torn away underneath the app and comes back on its own

Step 3 uses the debug build's test hook: a broadcast that calls
WindowManager.removeView without the app knowing, which is exactly what a ROM
does when it takes a TYPE_APPLICATION_OVERLAY window away. The app only gets
onDetachedFromWindow - and before this fix that was the last the user ever saw
of the bubble.

Prerequisites (see docs/verification/README.md):
  * x86_64 debug build installed (gradlew assembleDebug -PlitechatAbis=...)
  * accessibility service enabled, overlay permission granted
  * a chat window on screen (android/testchat, or a real chat app)

    python tools/verify_overlay_selfheal.py
"""

import io
import os
import re
import subprocess
import sys
import time

if sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ADB = os.environ.get("ADB") or os.path.join(
    os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb.exe")
PKG = "com.litechat.app"
TESTCHAT = "com.litechat.testchat"
DETACH_ACTION = "com.litechat.app.DEBUG_DETACH_OVERLAY"
SHOT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "docs", "verification")


def adb(*args, timeout=30):
    p = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return p.stdout or ""


def window():
    """The app's overlay window as the window manager sees it.

    Returns None when there is no such window, otherwise the on-screen frame.
    """
    dump = adb("shell", "dumpsys", "window", "windows")
    for b in re.split(r"\n\s*Window #", dump):
        if "package=" + PKG not in b:
            continue
        frame = re.search(r"frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
        if not frame:
            continue
        l, t, r, bo = (int(x) for x in frame.groups())
        return {
            "left": l, "top": t, "right": r, "bottom": bo,
            "width": r - l, "height": bo - t,
        }
    return None


def wait_for(pred, seconds, step=0.4):
    """Waits for pred() to be truthy; returns (value or None, seconds waited)."""
    started = time.time()
    while time.time() - started < seconds:
        v = pred()
        if v:
            return v, time.time() - started
        time.sleep(step)
    return None, time.time() - started


def tap(x, y):
    adb("shell", "input", "tap", str(int(x)), str(int(y)))


def screenshot(name):
    path = os.path.join(SHOT_DIR, name)
    with open(path, "wb") as f:
        p = subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=subprocess.PIPE)
        f.write(p.stdout)
    return path


def main():
    failures = []

    # ---- 1. the bubble is there ------------------------------------------
    if not window():
        adb("shell", "am", "start", "-n", TESTCHAT + "/.MainActivity")
    win, waited = wait_for(window, 25)
    if not win:
        print("FAIL: no floating window after %.0f s" % waited)
        print("      is the accessibility service on and the overlay permission granted?")
        return 1
    print("bubble on screen: %dx%d at (%d,%d)"
          % (win["width"], win["height"], win["left"], win["top"]))
    screenshot("android-55-v1.25-bubble.png")

    # ---- 2. tapping it opens the panel -----------------------------------
    collapsed_w = win["width"]
    tap(win["left"] + win["width"] / 2, win["top"] + win["height"] / 2)
    opened, waited = wait_for(lambda: (w := window()) and w["width"] > 300 and w, 6)
    if not opened:
        print("FAIL: tapping the bubble did not open the panel")
        failures.append("tap opens the panel")
    else:
        print("panel opened in %.1f s (%dx%d)"
              % (waited, opened["width"], opened["height"]))
        screenshot("android-56-v1.25-panel-opened.png")
        # Collapse again: the bubble sits at the panel's top-left corner.
        tap(opened["left"] + 68, opened["top"] + 68)
        back, _ = wait_for(lambda: (w := window()) and w["width"] <= collapsed_w + 8 and w, 6)
        if not back:
            print("FAIL: tapping again did not collapse the panel back to the bubble")
            failures.append("collapse")
        else:
            print("collapsed back to %dx%d" % (back["width"], back["height"]))

    # ---- 3. the system tears the window away -----------------------------
    # The app re-adds the window within half a second, and one dumpsys takes
    # longer than that, so the proof that the window really left the screen is
    # the app's own "detached by the system" line: a View only gets
    # onDetachedFromWindow when its window is gone.
    adb("logcat", "-c")
    adb("shell", "am", "broadcast", "-a", DETACH_ACTION, "-p", PKG)
    fired, _ = wait_for(lambda: "detaching the window underneath" in adb("logcat", "-d", "-s", "LITECHAT"), 8)
    if not fired:
        print("FAIL: the test hook never ran (broadcast not delivered?)")
        failures.append("detach")
    else:
        detached, waited = wait_for(
            lambda: "detached by the system; respawning" in adb("logcat", "-d", "-s", "LITECHAT"), 8)
        if not detached:
            print("FAIL: the window was removed but the app never noticed")
            failures.append("detach noticed")
        else:
            print("window torn away, app noticed after %.1f s" % waited)
    back, waited = wait_for(window, 15, step=0.3)
    if not back:
        print("FAIL: the floating window never came back on its own")
        failures.append("self-heal")
    else:
        print("came back on its own after %.1f s (%dx%d)"
              % (waited, back["width"], back["height"]))
        if back["width"] > collapsed_w + 8:
            print("FAIL: what came back is not the collapsed bubble")
            failures.append("shape after respawn")

    print()
    if failures:
        print("FAILED: " + ", ".join(failures))
        return 1
    print("overlay self-heal: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

# -*- coding: utf-8 -*-
"""Point the desktop app at the local mock API and the fake chat window.

Development helper only - it overwrites %APPDATA%/LiteChat/config.json, so it is
never run automatically. Pass `--restore` to put a plain DeepSeek setup back
(the API key is deliberately left blank; it is never written by a script).
"""

import json
import os
import sys

CONFIG = os.path.join(os.environ.get("APPDATA", "."), "LiteChat", "config.json")

MOCK = {
    "providerId": "custom",
    "protocol": "openai",
    "baseUrl": "http://127.0.0.1:8765/v1",
    "apiKey": "sk-test",
    "model": "test-model",
    "relationship": "对方是我的老板",
    "pinnedTarget": {"title": "轻聊测试会话", "cls": "TkTopLevel"},
}

PLAIN = {
    # The preset the user actually runs. The key is deliberately left blank:
    # a script never writes an API key anywhere.
    "providerId": "longcat",
    "protocol": "openai",
    "baseUrl": "https://api.longcat.chat/openai/v1",
    "apiKey": "",
    "model": "LongCat-2.0",
    "relationship": "对方是我的老板",
    "pinnedTarget": {"title": "微信", "cls": "Qt51514QWindowIcon"},
}


def main():
    defaults = {
        "temperature": "", "extraHeaders": "",
        "intervalMs": 600, "autoGenerate": True, "alwaysOnTop": True,
        "followWindow": True, "manualRect": None, "startRunning": True,
        "tunedV11": True,
    }
    defaults.update(PLAIN if "--restore" in sys.argv else MOCK)
    for i, arg in enumerate(sys.argv):
        if arg == "--skill" and i + 1 < len(sys.argv):
            defaults["skillId"] = sys.argv[i + 1]
    os.makedirs(os.path.dirname(CONFIG), exist_ok=True)
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(defaults, f, ensure_ascii=False, indent=2)
    print(("restored" if "--restore" in sys.argv else "mock"), "config ->", CONFIG)
    print("apiKey:", "(blank)" if not defaults["apiKey"] else "(set for the local mock)")
    print("skill :", defaults.get("skillId", "general"))


if __name__ == "__main__":
    main()

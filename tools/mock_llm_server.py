# -*- coding: utf-8 -*-
"""A tiny local stand-in for an LLM API, used to test the app without a key.

It answers all three wire formats the app speaks, so the app can be pointed at
it with any protocol for a real end-to-end round trip:

    POST /v1/chat/completions                     OpenAI-compatible
    POST /messages                                Anthropic
    POST /v1beta/models/<model>:generateContent   Google Gemini

It returns a fixed 3-candidate suggestion, or the two characters the settings
page's short connectivity probe asks for.

Usage:  python mock_llm_server.py [port]        (default 8765)
From an Android emulator, the host is reachable as http://10.0.2.2:<port>/v1

Set MOCK_DELAY_MS to make it answer slowly, which is how the "suggestions stay
on screen while a refresh is in flight" behaviour gets checked by hand.
"""

import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SUGGESTION = json.dumps({
    "intent": "催你交代进度",
    "danger": 3,
    "advice": "给个具体时间",
    # 军师 mode asks for one extra field; the mock returns it so the panel's
    # 军师-mode line can be exercised without a real model.
    "stance": "他在借客户施压，要的是明确时间",
    "replies": [
        "在弄了，今晚之前给你一个能看的东西",
        "还差最后一点，等我半小时",
        "抱歉拖了，我先把手上这份发你",
    ],
}, ensure_ascii=False)


def reply_for(body: str) -> str:
    """Short probe -> the two characters; anything else -> the canned suggestion."""
    if "收到" in body:
        return "收到"
    return SUGGESTION


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%.3f [mock] %s %s\n" % (time.time(), self.command, self.path))

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        # `or 0` on purpose: an empty MOCK_DELAY_MS is a very easy way to set
        # this from a shell, and float("") would take the whole mock down.
        delay = float(os.environ.get("MOCK_DELAY_MS") or 0) / 1000.0
        if delay > 0:
            time.sleep(delay)
        # Keep the last request body so a test can check exactly what the app
        # asked the model - which is the only way to verify that the reply is
        # anchored on the right message.
        try:
            dump = os.path.join(os.environ.get("TEMP", "."), "mock_last_request.json")
            with open(dump, "w", encoding="utf-8") as f:
                f.write(raw)
        except Exception:
            pass
        text = reply_for(raw)
        path = self.path.split("?")[0]

        if path.endswith("/messages"):                 # Anthropic
            out = {"content": [{"type": "text", "text": text}]}
        elif ":generateContent" in path:               # Google Gemini
            out = {"candidates": [{"content": {"parts": [{"text": text}]}}]}
        else:                                          # OpenAI-compatible
            out = {"choices": [{"message": {"role": "assistant", "content": text}}]}

        data = json.dumps(out, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("mock LLM API listening on http://0.0.0.0:%d" % port)
    print("  OpenAI path   : POST /v1/chat/completions")
    print("  Anthropic path: POST /messages")
    print("  Gemini path   : POST /v1beta/models/<model>:generateContent")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

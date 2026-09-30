# -*- coding: utf-8 -*-
"""The "don't think" hint must never break an endpoint that does not know it.

Turning thinking off is worth 3-4x on the clock (see the README), so it is sent
by default. A strict gateway that validates the request body would answer 400 -
and the user would lose their reply to a speed optimisation, which is not a
trade anyone should have to make. So `chat()` drops the hint on a 400/415/422,
remembers the host, and asks again the old way.

This points the app's own `chat()` at a local server that plays exactly that
strict gateway, and checks all three behaviours.

    python tools/verify_thinking_fallback.py
"""

import io
import json
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "windows"))

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

import litechat_win as lw          # noqa: E402

REPLY = json.dumps({"choices": [{"message": {"content": json.dumps({
    "intent": "催促", "danger": 3, "advice": "给时间",
    "replies": ["甲", "乙", "丙"]})}}]}, ensure_ascii=False)

seen_with_hint = []
seen_without_hint = []


class StrictGateway(BaseHTTPRequestHandler):
    """Rejects any field it does not know, the way a validating proxy would."""

    KNOWN = {"model", "messages", "max_tokens", "temperature", "stream"}

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n).decode("utf-8"))
        unknown = set(body) - self.KNOWN
        (seen_with_hint if unknown else seen_without_hint).append(sorted(unknown))
        if unknown:
            payload = json.dumps({"error": {"message": "unknown field: %s"
                                            % sorted(unknown)[0]}}).encode()
            self.send_response(400)
        else:
            payload = REPLY.encode("utf-8")
            self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_a):
        pass


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", 8791), StrictGateway)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    cfg = {"protocol": lw.PROTOCOL_OPENAI, "baseUrl": "http://127.0.0.1:8791/v1",
           "model": "strict-model", "apiKey": "", "noThinking": True}
    # The host is remembered per process, so start from a clean slate.
    lw._NO_THINKING_REJECTED.discard("127.0.0.1")

    ok = True
    print("1) an endpoint that rejects the hint still answers")
    text = lw.chat(cfg, "系统提示", "用户内容")
    got = lw.parse_suggestion(text)
    print("   replies:", [t for t, _ in got[3]])
    print("   hint rejected:", lw.LAST_HINT_REJECTED)
    ok &= len(got[3]) == 3 and lw.LAST_HINT_REJECTED is True
    print("   attempted with hint:", seen_with_hint, "then without:", seen_without_hint)
    ok &= len(seen_with_hint) == 1 and len(seen_without_hint) == 1

    print("2) the host is remembered, so the hint is not tried again")
    seen_with_hint.clear()
    seen_without_hint.clear()
    lw.chat(cfg, "系统提示", "用户内容")
    print("   with hint:", seen_with_hint, "| without hint:", seen_without_hint)
    ok &= len(seen_with_hint) == 0 and len(seen_without_hint) == 1

    print("3) turning the setting off sends nothing extra")
    seen_with_hint.clear()
    seen_without_hint.clear()
    lw._NO_THINKING_REJECTED.clear()
    cfg2 = dict(cfg, noThinking=False)
    lw.chat(cfg2, "系统提示", "用户内容")
    print("   with hint:", seen_with_hint, "| without hint:", seen_without_hint)
    ok &= len(seen_with_hint) == 0 and len(seen_without_hint) == 1

    print()
    print("strict-gateway fallback:", "PASS" if ok else "FAIL")
    srv.shutdown()
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

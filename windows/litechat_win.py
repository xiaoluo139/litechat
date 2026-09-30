# -*- coding: utf-8 -*-
"""LiteChat for Windows: chat reply suggestions, straight off the chat window.

What it does
------------
It finds your open chat window by itself (WeChat, QQ/TIM, Feishu, DingTalk),
cuts the conversation area out of it, reads it with the OCR engine Windows
already ships, and asks your own model API for three replies. Click one and it
lands in the chat's input box. It never presses Enter - you send.

No manual region drawing is needed. If the automatic guess is ever wrong there
is a "pick the window" list and a manual region fallback, but neither is part of
the normal flow.

Simplifications against the upstream project it is derived from:
  * one API to configure (address + key + model), not three routes;
  * one model call per turn (understanding + three candidates together);
  * the protocol adapts (OpenAI-compatible / Anthropic / Google Gemini), so any
    provider works;
  * no knowledge base - just one free-text "who is this person" line.

Requires only the Python standard library plus the PowerShell 5.1 and OCR
engine that ship with Windows.
"""

from __future__ import annotations

import ctypes
import difflib
import http.client
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import urllib.error
import urllib.parse
import urllib.request
from tkinter import ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import winchat as wc            # noqa: E402  (needs the sys.path line above)

APP_NAME = "轻聊助手"
APP_ID = "LiteChat"
VERSION = "1.25"

set_dpi_awareness = wc.set_dpi_awareness


def resource_path(name: str) -> str:
    """Bundled resources live in sys._MEIPASS once frozen, next to us otherwise."""
    base = getattr(sys, "_MEIPASS", None)
    if base:
        candidate = os.path.join(base, name)
        if os.path.exists(candidate):
            return candidate
    here = os.path.dirname(os.path.abspath(__file__))
    candidate = os.path.join(here, name)
    if os.path.exists(candidate):
        return candidate
    return os.path.join(os.path.dirname(os.path.abspath(sys.executable)), name)


def config_path() -> str:
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    folder = os.path.join(base, APP_ID)
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, "config.json")


# ---------------------------------------------------------------- providers

PROTOCOL_OPENAI = "openai"
PROTOCOL_ANTHROPIC = "anthropic"
PROTOCOL_GEMINI = "gemini"

PROTOCOL_LABELS = ["OpenAI 兼容协议", "Anthropic 协议", "Google Gemini 协议"]
PROTOCOL_IDS = [PROTOCOL_OPENAI, PROTOCOL_ANTHROPIC, PROTOCOL_GEMINI]

# (id, label, protocol, base url, default model)
PROVIDERS = [
    ("deepseek", "DeepSeek 深度求索", PROTOCOL_OPENAI, "https://api.deepseek.com/v1", "deepseek-chat"),
    ("dashscope", "通义千问（阿里云百炼）", PROTOCOL_OPENAI,
     "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ("zhipu", "智谱 GLM", PROTOCOL_OPENAI, "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    ("moonshot", "月之暗面 Kimi", PROTOCOL_OPENAI, "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    ("siliconflow", "硅基流动 SiliconFlow", PROTOCOL_OPENAI,
     "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
    ("volcengine", "火山方舟（豆包）", PROTOCOL_OPENAI,
     "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k"),
    ("hunyuan", "腾讯混元", PROTOCOL_OPENAI,
     "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbos-latest"),
    ("minimax", "MiniMax 稀宇", PROTOCOL_OPENAI, "https://api.minimax.chat/v1", "abab6.5s-chat"),
    ("baichuan", "百川智能", PROTOCOL_OPENAI, "https://api.baichuan-ai.com/v1", "Baichuan4"),
    ("stepfun", "阶跃星辰 Step", PROTOCOL_OPENAI, "https://api.stepfun.com/v1", "step-1-8k"),
    ("lingyiwanwu", "零一万物 Yi", PROTOCOL_OPENAI, "https://api.lingyiwanwu.com/v1", "yi-lightning"),
    ("openai", "OpenAI", PROTOCOL_OPENAI, "https://api.openai.com/v1", "gpt-4o-mini"),
    ("openrouter", "OpenRouter（聚合多模型）", PROTOCOL_OPENAI,
     "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
    ("groq", "Groq（超快推理）", PROTOCOL_OPENAI,
     "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    ("anthropic", "Anthropic Claude", PROTOCOL_ANTHROPIC,
     "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
    ("gemini", "Google Gemini", PROTOCOL_GEMINI,
     "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
    ("oneapi", "One API / New API 中转站", PROTOCOL_OPENAI,
     "http://127.0.0.1:3000/v1", "gpt-4o-mini"),
    ("ollama", "Ollama（本机离线）", PROTOCOL_OPENAI, "http://127.0.0.1:11434/v1", "qwen2.5:7b"),
    ("lmstudio", "LM Studio（本机离线）", PROTOCOL_OPENAI, "http://127.0.0.1:1234/v1", "local-model"),
    ("custom", "自定义（任意兼容服务）", PROTOCOL_OPENAI, "", ""),
]

PROVIDER_BY_ID = {p[0]: p for p in PROVIDERS}
PROVIDER_LABELS = [p[1] for p in PROVIDERS]

# Where an unknown stored provider id lands. A preset that a later version
# stopped shipping (the config still names it) means "the user's own endpoint",
# so the dropdown shows 自定义 instead of pretending it is some other vendor.
CUSTOM_INDEX = next(i for i, p in enumerate(PROVIDERS) if p[0] == "custom")


def build_endpoint(protocol: str, base_url: str, model: str) -> str:
    """Full POST URL for one round trip. A complete URL is used verbatim."""
    base = (base_url or "").strip().rstrip("/")
    if not base:
        return ""
    if protocol == PROTOCOL_ANTHROPIC:
        return base if base.endswith("/messages") else base + "/messages"
    if protocol == PROTOCOL_GEMINI:
        if base.endswith(":generateContent"):
            return base
        return "%s/models/%s:generateContent" % (base, model.strip())
    return base if base.endswith("/chat/completions") else base + "/chat/completions"


# ---------------------------------------------------------------- model call

# The prompt earns its length. Replies that "read like they belong to another
# conversation" almost always come from the model guessing which line it is
# supposed to answer, so the input is anchored on 【要回的那句】 and the rules
# are about answering *that* line and nothing else.
#
# `replies` is asked for first so a truncated answer still carries the
# candidates.
SYSTEM_PROMPT = (
    "你是中文聊天回复助手，替\"我\"回复对话。\n"
    "输入里的【要回的那句】是必须回应的那句话，其余内容只是背景。\n"
    "只输出一个 JSON 对象，不要解释、不要代码块、不要多余文字：\n"
    "{\"replies\":[\"回复1\",\"回复2\",\"回复3\"],"
    "\"intent\":\"对方想要什么，10字内\",\"danger\":1到9,"
    "\"advice\":\"给我的下一步建议，12字内\"}\n"
    "写回复的硬要求：\n"
    "1. 三条都必须直接回应【要回的那句】，不许答非所问，不许复述对方说过的话。\n"
    "2. 长度和语气贴着对方刚发的那句：对方一句话你也一句话；"
    "对方在催、在问，就正面回应，不要打太极。\n"
    "3. 三条要有区别：第一条最稳妥、能直接解决问题；"
    "第二条给出具体行动或时间；第三条简短、留有余地。\n"
    "4. 口语，像真人打字。不要书面语，不要加引号、序号或括号说明。\n"
    "5. 聊天记录是屏幕识别出来的，可能有错别字或漏字，"
    "按最合理的意思理解，不要因为错字就说看不懂。\n"
    "6. 用和聊天记录相同的语言回复。不要编造记录里没有的时间、金额或承诺；"
    "确实缺关键信息时，就用一句问句去确认。"
)


# ---------------------------------------------------------------- reply skills
#
# Two ways to answer, picked with the button on the panel:
#   general        - the plain chat-reply assistant;
#   goutoujunshi   - the open-source 狗头军师 skill (MIT, © powerycy): a
#                    relationship/communication advisor whose method is
#                    "先接住情绪，再分清事实，最后给能执行的选择", backed by 43
#                    reference documents that ship in windows/skills/.

GOUTOU_PROMPT = (
    "你是\"狗头军师\"：一个清醒、站在用户这边的中文恋爱／沟通军师。"
    "你不只替用户回消息，也帮他看清关系、判断局势、决定下一步。\n"
    "工作方式（每次都要走完）：\n"
    "1. 先接住情绪：点出他的感受和眼下的纠结，不评判。\n"
    "2. 再分清事实：把「聊天记录能证明的」「合理推测」「还不知道的」分开；"
    "不读心，不凭单条消息下定论，看持续主动、兑现、投入和边界。\n"
    "3. 最后给能执行的选择：一句首选加理由，再给风格不同的版本。\n"
    "写回复的硬要求：\n"
    "- 输入里的【要回的那句】是必须回应的那句话，其余只是背景。\n"
    "- 每条直接回应那句话，不许答非所问，不许复述对方的话。\n"
    "- 长度和语气贴着对方刚发的那句；对方在催、在问就正面回应，别打太极。\n"
    "- 口语，像真人打字；不要书面语，不要加引号、序号或括号说明。\n"
    "- 聊天记录是屏幕识别出来的，可能有错别字，按最合理的意思理解。\n"
    "- 用和聊天记录相同的语言回复。\n"
    "- 不编造记录里没有的时间、金额或承诺；缺关键信息就用一句问句确认。\n"
    "安全边界（不可越过）：\n"
    "- 不诊断心理疾病，不用标签替代行为证据。\n"
    "- 不提供贬低、服从性测试、虚假时间限制、嫉妒操控、煤气灯、孤立、跟踪或"
    "性施压的做法；冷读只能表述成「观察到的事实 + 暂定假设 + 邀请纠正」。\n"
    "- 对方明确拒绝、要求别联系或反复表示不欢迎时停止推进，帮他体面退出。\n"
    "- 出现家暴、跟踪、胁迫、人身威胁或自伤风险时先确认当下安全，"
    "建议联系可信的人或当地紧急服务，不写任何\"话术\"去对付对方。\n"
    "- 最终决定权留给用户，并说明关键的不确定性和何时该换策略。\n"
    "只输出一个 JSON 对象，不要解释、不要代码块、不要多余文字：\n"
    "{\"stance\":\"你对局势的判断，20字内\","
    "\"replies\":[\"回复1\",\"回复2\",\"回复3\"],"
    "\"intent\":\"对方想要什么，12字内\",\"danger\":1到9,"
    "\"advice\":\"下一步建议，15字内\"}\n"
    "replies 恰好 3 条，风格要拉开：第一条最稳妥、能直接解决问题；"
    "第二条更主动或更有分寸；第三条守边界或留余地。每条不超过 40 字。"
    "danger：1 日常闲聊，5 对方明显不高兴，9 严重冲突或风险。"
)

SKILLS = {
    "general": {"id": "general", "label": "通用", "prompt": SYSTEM_PROMPT},
    "goutoujunshi": {"id": "goutoujunshi", "label": "军师", "prompt": GOUTOU_PROMPT},
}

_SKILL_EXCERPT = 1500
_SKILL_DEFAULT_REF = "实战话术编排器：从一句回复到后续分支"
_skill_index = None


def skill_index():
    """The reference routing table shipped with the skill, or []."""
    global _skill_index
    if _skill_index is None:
        try:
            with open(resource_path("skills/goutoujunshi/index.json"), encoding="utf-8") as f:
                _skill_index = json.load(f)
        except Exception:
            _skill_index = []
    return _skill_index


def pick_references(conversation, limit=2):
    """The one or two reference documents that fit this conversation.

    The skill says to load 1-3 relevant references rather than the whole
    library; that is also what keeps a request small enough to stay fast.
    Returns [(title, excerpt)].
    """
    index = skill_index()
    if not index:
        return []
    haystack = conversation.lower()
    scored = []
    for entry in index:
        keywords = [k for k in (entry.get("k") or "").split() if k]
        hits = sum(1 for k in keywords if k.lower() in haystack)
        if hits:
            scored.append((hits, entry))
    scored.sort(key=lambda pair: -pair[0])
    picked = [entry for _hits, entry in scored[:limit]]
    if not picked:
        picked = [next((e for e in index if e.get("t") == _SKILL_DEFAULT_REF), index[0])]
    out = []
    for entry in picked:
        try:
            with open(resource_path("skills/goutoujunshi/" + entry["f"]),
                      encoding="utf-8") as f:
                text = f.read()
        except Exception:
            continue
        if len(text) > _SKILL_EXCERPT:
            text = text[:_SKILL_EXCERPT] + "\n…（节选）"
        out.append((entry.get("t", ""), text))
    return out


def build_user_prompt(who, relationship, messages, sides_known):
    """The model-facing view of one conversation.

    Two shapes, because the difference matters a lot to answer quality:
      * sides known  -> name the one line that has to be answered;
      * sides unknown (a screen read that caught only one direction) -> hand over
        the transcript and say outright that the last line might be the user's
        own, so the model must not answer the user's own words.
    """
    parts = []
    if who:
        parts.append("【会话】%s" % who)
    parts.append("【关系】%s" % (relationship.strip() or "未说明"))

    if not messages:
        return "\n".join(parts)

    if sides_known:
        # Anchor on the newest line that is actually THEIRS - not simply the last
        # line on screen. The last line is very often the user's own (they just
        # sent something, or the window is scrolled to their own message), and
        # telling the model "this is the line you must answer" while pointing at
        # the user's own words is exactly how replies end up belonging to
        # another conversation.
        anchor = None
        for i in range(len(messages) - 1, -1, -1):
            if messages[i][0] == "other":
                anchor = i
                break
        if anchor is None:
            # Everything on screen is the user's own doing; there is nothing to
            # answer. Hand over the transcript and let the model judge.
            parts.append("【提示】这一屏只有我自己说过的话，没有对方的新消息。"
                         "如果确实需要接话，请针对上面最后一句继续。")
            parts.append("【看到的对话，越靠下越新】")
            for _side, text in messages:
                parts.append(text)
            return "\n".join(parts)

        parts.append("【要回的那句】%s" % messages[anchor][1])
        before = messages[:anchor]
        after = messages[anchor + 1:]
        if before:
            parts.append("【上文，越靠下越新】")
            for side, text in before:
                parts.append("%s：%s" % ("我" if side == "me" else "对方", text))
        if after:
            # Usually the user's own messages. Without saying so the model
            # cheerfully drafts a reply to a line that has already been answered.
            parts.append("【这句话之后我已经说过（别重复）】")
            for _side, text in after:
                parts.append("我：%s" % text)
    else:
        parts.append(
            "【提示】这一屏没能确定哪句是我说的、哪句是对方说的。"
            "请先判断最后一条是谁发的：如果是对方发的，就回复它；"
            "如果是我自己刚发的，就回复它前面那句。")
        parts.append("【看到的对话，越靠下越新】")
        for _side, text in messages:
            parts.append(text)
    return "\n".join(parts)


class LlmError(RuntimeError):
    """A failed model call. [status] is the HTTP status when there was one."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


def _friendly(code: int, raw: str) -> str:
    head = {
        400: "请求被拒绝",
        401: "密钥无效或未授权",
        403: "没有权限（模型可能未开通或未实名）",
        404: "地址或模型名不对",
        429: "频率超限或余额不足",
    }.get(code, "请求失败")
    msg = ""
    try:
        o = json.loads(raw)
        err = o.get("error")
        if isinstance(err, dict):
            msg = err.get("message") or err.get("msg") or ""
        elif isinstance(err, str):
            msg = err
        msg = msg or o.get("message") or o.get("msg") or ""
    except Exception:
        pass
    msg = str(msg).strip()
    if msg:
        return "HTTP %d %s：%s" % (code, head, msg)
    return "HTTP %d %s：%s" % (code, head, raw[:200])


# --------------------------------------------------------------- HTTP plumbing
#
# Connections are kept alive across calls. Every request used to open a fresh
# TLS connection, which costs a handshake (DNS + TCP + TLS) before the model is
# even asked anything - pure added latency on a call the user is waiting for.

_conns = {}
_conn_lock = threading.Lock()
# Read timeout for one model call. Generous because a reasoning model thinks
# before it writes: with the 320-token cap this app used to send, LongCat-2.0
# spent the whole budget thinking and never answered at all.
REQUEST_TIMEOUT = 40.0

# Output budget for one reply draft. Not a target - a cap - and deliberately
# generous, because a reasoning model spends tokens thinking before it writes
# anything. Measured against LongCat-2.0 on a two-line conversation: a
# 320-token cap was consumed entirely by the thinking, `content` came back
# missing, and the app showed the model's notes as the candidate replies.
THINKING_SAFE_TOKENS = 1600
RETRY_TOKENS = 3200
DIRECT_ANSWER_RULE = ("\n不要输出思考过程、分析或解释。想清楚后直接给出那个 JSON 对象，"
                      "第一个字符就是 {")

# ---------------------------------------------------------------- thinking off
#
# Reasoning models think before they answer, and the thinking is generated text
# like any other, so it dominates the wait. Measured against LongCat-2.0 on the
# same 军师 request, same conversation:
#
#   thinking on (server default)  14.0 / 16.0 / 23.8 s   ~900-1300 thinking tokens
#   thinking off                  3.4 / 3.6 / 4.0 / 4.4 s  0 thinking tokens
#
# The app never shows the thinking, so it is pure waiting. Turning it off is
# therefore on by default, and an endpoint that does not understand the field
# answers 400/422 - we then drop the hint, remember that, and carry on the old
# way rather than failing.
NO_THINKING_HINT = {
    "thinking": {"type": "disabled"},                     # Anthropic/Qwen-style
    "chat_template_kwargs": {"enable_thinking": False},   # vLLM/SGLang style
}
_NO_THINKING_REJECTED = set()      # hosts that answered "I do not know that field"
LAST_HINT_REJECTED = False         # the last call had to drop the hint


def _endpoint(url):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise LlmError("接口地址不对：%s" % url)
    port = parts.port or (443 if parts.scheme == "https" else 80)
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    return parts.scheme, parts.hostname, port, path


def _host_of(url: str) -> str:
    """Just the host, for remembering which endpoints rejected a request field."""
    try:
        return (urllib.parse.urlsplit(url).hostname or url).lower()
    except Exception:
        return url


def _same_person(a: str, b: str) -> bool:
    """True when two header names are the same person seen through OCR.

    Names are short, so the fuzzy rule used for messages would call a single
    wrong character a different person - and a header that reads "李沅汐" one
    frame and something one character off the next would then wipe the panel
    every second. Only a clearly different name counts as somebody else.
    """
    x = re.sub(r"[\s·、,，.。]+", "", (a or "").strip())
    y = re.sub(r"[\s·、,，.。]+", "", (b or "").strip())
    if x == y:
        return True
    if not x or not y:
        return True                      # one unreadable header is not a switch
    if min(len(x), len(y)) >= 2 and (x in y or y in x):
        return True
    if min(len(x), len(y)) < 2:
        return False
    return difflib.SequenceMatcher(None, x, y).ratio() >= 0.5


def _connection(scheme, host, port, timeout):
    key = (scheme, host, port)
    with _conn_lock:
        conn = _conns.get(key)
        if conn is None:
            cls = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
            conn = cls(host, port, timeout=timeout)
            _conns[key] = conn
        else:
            conn.timeout = timeout
        return conn


def _drop_connection(scheme, host, port):
    with _conn_lock:
        conn = _conns.pop((scheme, host, port), None)
    if conn is not None:
        try:
            conn.close()
        except Exception:
            pass


def warm_up_connection(cfg: dict) -> None:
    """Open the TLS connection now, so the first real reply is not paying for it.

    Called on a background thread right after startup and after settings change.
    """
    def work():
        try:
            url = build_endpoint(cfg.get("protocol", PROTOCOL_OPENAI),
                                 cfg.get("baseUrl", ""), cfg.get("model", ""))
            if not url:
                return
            scheme, host, port, _path = _endpoint(url)
            conn = _connection(scheme, host, port, REQUEST_TIMEOUT)
            if scheme == "https":
                conn.connect()
        except Exception:
            pass

    threading.Thread(target=work, daemon=True).start()


def _post_json(url: str, body: dict, headers: dict, timeout: float = REQUEST_TIMEOUT) -> dict:
    scheme, host, port, path = _endpoint(url)
    data = json.dumps(body).encode("utf-8")
    send_headers = {"Content-Type": "application/json; charset=utf-8",
                    "Accept": "application/json",
                    "Connection": "keep-alive"}
    send_headers.update(headers)

    last_error = None
    for attempt in (0, 1):
        conn = _connection(scheme, host, port, timeout)
        try:
            conn.request("POST", path, body=data, headers=send_headers)
            resp = conn.getresponse()
            raw = resp.read().decode("utf-8", "replace")
            if resp.status >= 400:
                raise LlmError(_friendly(resp.status, raw), status=resp.status)
            if not raw.strip():
                raise LlmError("接口返回了空内容")
            try:
                return json.loads(raw)
            except Exception:
                raise LlmError("接口返回的不是 JSON：%s" % raw[:200])
        except LlmError:
            raise
        except Exception as e:
            # A pooled connection the server has since closed is the common
            # case here; reconnect once and try again.
            last_error = e
            _drop_connection(scheme, host, port)
            if attempt == 1:
                break
    raise LlmError(_transport_message(last_error))


def _transport_message(e) -> str:
    if e is None:
        return "请求失败"
    if e is None:
        return "请求失败"
    if isinstance(e, TimeoutError) or "timed out" in str(e).lower():
        return "接口超时（超过 %d 秒没返回）" % int(REQUEST_TIMEOUT)
    if isinstance(e, urllib.error.URLError):
        return "连不上接口：%s" % getattr(e, "reason", e)
    return "请求失败：%s" % e


# ------------------------------------------------------- deciding what is new


MAX_KNOWN_CONVERSATIONS = 6

_NAME_JUNK = set("，。！？、：；“”‘’《》〈〉()（）[]【】{}<>@#￥%…&*+=|\\/!?,.:;'\"")


def plausible_conversation_name(name: str) -> bool:
    """Could this plausibly be the name of a chat?

    The name is read out of the strip above the messages, and a missed read
    comes back as a fragment of whatever else was on screen - a message preview,
    a slogan, or digits and Latin glued to Chinese. Those were all remembered as
    "conversations", so the picker filled up with entries nobody could
    recognise. The Android build has the same rule (MessageKeys.plausibleName).
    """
    t = (name or "").strip()
    if not t or len(t) > 12:
        return False
    if any(c.isspace() for c in t):          # two OCR fragments joined up
        return False
    if any(c.isdigit() for c in t):
        return False
    if any(c in _NAME_JUNK for c in t):
        return False
    cjk = sum(1 for c in t if "\u4e00" <= c <= "\u9fff")
    latin = sum(1 for c in t if c.isascii() and c.isalpha())
    if cjk and latin:                        # a half-read line
        return False
    return bool(cjk or latin)


def _incoming_key(messages) -> str:
    """A fingerprint of the newest incoming message.

    Only this decides whether a model call is warranted. Whitespace and
    punctuation are dropped because OCR wobbles on them between frames.
    """
    text = ""
    for candidate in reversed(messages or []):
        # Skip the chrome a chat window draws: the blinking "对方正在输入…" strip
        # and timestamps would otherwise each look like a fresh message.
        if not wc.is_noise_line(candidate):
            text = candidate
            break
    return re.sub(r"[\s，。！？,.!?、~～…:：;；\"'“”‘’]+", "", text)[-48:]


def _looks_same(a: str, b: str) -> bool:
    """True when two fingerprints are the same message read twice by OCR.

    Without this, a single flickering character made the app ask the model
    again - and again - which is exactly the "it never stops thinking" the
    first build suffered from. Short messages are never treated as similar:
    "好" and "嗯" are one character apart and genuinely different.
    """
    if a == b:
        return True
    if min(len(a), len(b)) < 8:
        return False
    return difflib.SequenceMatcher(None, a, b).ratio() >= 0.82


def confirm_pending(pending_key, pending_hash, hits, key, frame):
    """One step of "is this really a new incoming message?".

    A brand new line only counts once the *same* line has been read twice off
    an unchanged screen. The newest thing on a chat screen is often not a
    message: WeChat repaints a timestamp and a "对方正在输入…" strip under the
    last bubble, and OCR turns that flicker into a different line every frame.
    Because the two looks have to agree on the pixels as well as on the text,
    the check cannot be fooled by landing on the same phase of a blink.

    Returns ``(accepted, pending_key, pending_hash, hits)``.
    """
    if (pending_key and _looks_same(key, pending_key)
            and frame is not None and frame == pending_hash):
        hits += 1
    else:
        pending_key, pending_hash, hits = key, frame, 1
    return (hits >= 2, pending_key, pending_hash, hits)


def make_bubble_classifier(pixels):
    """Guess "me" from the bubble colour, or None to fall back to geometry.

    WeChat paints the user's own bubbles green and everyone else's white/grey,
    so the colour is a much stronger signal than position - especially when the
    screen only shows one message and the left/right rule has nothing to compare
    against. Non-green deliberately returns None rather than "other": dark
    themes and other chat apps use colours this knows nothing about, and a wrong
    "other" would mean replying to the user's own words.
    """
    if not pixels:
        return None
    width, height, bgra = pixels

    def classify(x, y, w, h):
        total_r = total_g = total_b = 0
        samples = 0
        for fy in (0.25, 0.5, 0.75):
            for fx in (0.15, 0.35, 0.5, 0.65, 0.85):
                px = int(x + w * fx)
                py = int(y + h * fy)
                if not (0 <= px < width and 0 <= py < height):
                    continue
                i = (py * width + px) * 4
                total_b += bgra[i]
                total_g += bgra[i + 1]
                total_r += bgra[i + 2]
                samples += 1
        if samples == 0:
            return None
        r = total_r / samples
        g = total_g / samples
        b = total_b / samples
        return "me" if g - max(r, b) > 18 else None

    return classify


def extract_text(resp: dict) -> str:
    """Pull the assistant text out of any of the three vendors' envelopes."""
    content = resp.get("content")
    if isinstance(content, list):                    # Anthropic
        buf = "".join(p.get("text", "") for p in content if isinstance(p, dict))
        if buf:
            return buf
    cands = resp.get("candidates")                   # Google Gemini
    if isinstance(cands, list) and cands:
        parts = (cands[0].get("content") or {}).get("parts") or []
        buf = "".join(p.get("text", "") for p in parts if isinstance(p, dict))
        if buf:
            return buf
    choices = resp.get("choices")                    # OpenAI-compatible
    if isinstance(choices, list) and choices:
        msg = choices[0].get("message") or {}
        c = msg.get("content")
        if isinstance(c, str) and c.strip():
            return c
        if isinstance(c, list):
            buf = "".join((p.get("text") or p.get("content") or "")
                          for p in c if isinstance(p, dict))
            if buf:
                return buf
        # A reasoning model that ran out of room answers with its thinking and
        # no answer at all: "content" is missing (not empty - absent) and
        # "reasoning_content" holds the chain of thought. Handing that back as
        # the answer is what made the panel show three lines of the model
        # muttering to itself. Say so instead, so the caller can retry with a
        # budget that leaves room for both.
        if msg.get("reasoning_content"):
            raise LlmError("THINKING_ONLY")
    for key in ("output_text", "text"):
        v = resp.get(key)
        if isinstance(v, str) and v.strip():
            return v
    raise LlmError("没读懂服务商返回的内容：%s" % json.dumps(resp, ensure_ascii=False)[:200])


def chat(cfg: dict, system: str, user: str, max_tokens: int = 0) -> str:
    """One chat round trip, in whichever wire format the config selects."""
    protocol = cfg.get("protocol", PROTOCOL_OPENAI)
    url = build_endpoint(protocol, cfg.get("baseUrl", ""), cfg.get("model", ""))
    if not url:
        raise LlmError("还没填接口地址")
    key = (cfg.get("apiKey") or "").strip()
    headers = {}

    if protocol == PROTOCOL_ANTHROPIC:
        if key:
            headers["x-api-key"] = key
        headers["anthropic-version"] = "2023-06-01"
        body = {
            "model": cfg.get("model", ""),
            "system": system,
            "messages": [{"role": "user", "content": user}],
        }
        # Anthropic requires a cap; a generous one when the caller did not ask
        # for a specific budget, because some models spend it thinking first.
        body["max_tokens"] = max_tokens or THINKING_SAFE_TOKENS
    elif protocol == PROTOCOL_GEMINI:
        if "key=" not in url:
            url += ("&" if "?" in url else "?") + "key=" + key
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": {"maxOutputTokens": max_tokens or THINKING_SAFE_TOKENS},
        }
    else:
        if key:
            headers["Authorization"] = "Bearer " + key
        body = {
            "model": cfg.get("model", ""),
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }
        # Left out by default: the OpenAI shape is spoken by gateways that
        # disagree about this field. The caller passes a number on the retry.
        if max_tokens:
            body["max_tokens"] = max_tokens

    # temperature is only sent when the user typed one: reasoning models
    # (o1/o3, DeepSeek-R1) reject the parameter outright.
    temp = str(cfg.get("temperature") or "").strip()
    if temp:
        try:
            t = float(temp)
        except ValueError:
            t = None
        if t is not None:
            if protocol == PROTOCOL_GEMINI:
                body["generationConfig"]["temperature"] = t
            else:
                body["temperature"] = t

    for line in (cfg.get("extraHeaders") or "").splitlines():
        if ":" in line:
            name, _, value = line.partition(":")
            if name.strip():
                headers[name.strip()] = value.strip()

    # Ask the model not to think out loud. An endpoint that does not know the
    # field rejects the whole request, so the retry below drops it and remembers
    # that for this host.
    global LAST_HINT_REJECTED
    LAST_HINT_REJECTED = False
    want_hint = (bool(cfg.get("noThinking", True))
                 and protocol != PROTOCOL_ANTHROPIC
                 and _host_of(url) not in _NO_THINKING_REJECTED)
    if want_hint:
        if protocol == PROTOCOL_GEMINI:
            body.setdefault("generationConfig", {})["thinkingConfig"] = {"thinkingBudget": 0}
        else:
            body.update(NO_THINKING_HINT)

    if "openrouter.ai" in url:
        headers.setdefault("HTTP-Referer", "https://litechat.local")
        headers.setdefault("X-Title", "LiteChat")

    try:
        return extract_text(_post_json(url, body, headers))
    except LlmError as e:
        # A hint this endpoint does not understand must not cost the user their
        # answer: drop it, remember the host, and ask again exactly as v1.9 did.
        if not want_hint or e.status not in (400, 415, 422):
            raise
        _NO_THINKING_REJECTED.add(_host_of(url))
        LAST_HINT_REJECTED = True
        for key in list(NO_THINKING_HINT):
            body.pop(key, None)
        body.get("generationConfig", {}).pop("thinkingConfig", None)
        return extract_text(_post_json(url, body, headers))


def ping(cfg: dict) -> str:
    return chat(cfg, "你是连通性测试助手，只按要求回答，不要解释。",
                "请只回复两个字：收到", 64).strip()


def parse_suggestion(text: str):
    """Model output -> (intent, danger, advice, [(reply, pct), ...])."""
    for obj in _json_objects(text):
        if isinstance(obj, dict):
            replies = []
            for item in (obj.get("replies") or []):
                if isinstance(item, str) and item.strip():
                    replies.append((item.strip(), 0))
                elif isinstance(item, dict):
                    t = (item.get("text") or item.get("reply") or "").strip()
                    if t:
                        try:
                            pct = int(item.get("pct") or item.get("score") or 0)
                        except Exception:
                            pct = 0
                        replies.append((t, pct))
            if replies:
                # Three is the contract; a model that answers with six (measured
                # on LongCat without thinking) must not push two extras into the
                # panel.
                replies = replies[:3]
                replies = _normalise_pct(replies)
                danger = obj.get("danger")
                try:
                    danger = int(danger)
                    danger = danger if 1 <= danger <= 9 else None
                except Exception:
                    danger = None
                return (obj.get("intent") or None, danger, obj.get("advice") or None,
                        replies, (obj.get("stance") or "").strip() or None)
    # No usable JSON. Salvaging plain prose only helps when the model actually
    # answered in prose; a model that got cut off mid-thought, or that wrapped
    # its reasoning in bullets, would otherwise have its own notes shown as the
    # three candidate replies - which is exactly what "答非所问" looked like.
    if _looks_like_reasoning(text):
        return (None, None, None, [], None)
    lines = []
    for raw in text.splitlines():
        t = raw.strip().lstrip("0123456789.、)） ").strip().strip('"“”')
        if not t or t.startswith("```"):
            continue
        if t.endswith("：") or t.endswith(":"):
            continue
        if "**" in t or t.startswith("#"):
            continue
        if len(t) > 60:                     # notes run long, replies do not
            continue
        if t:
            lines.append(t)
        if len(lines) == 3:
            break
    return (None, None, None, _normalise_pct([(t, 0) for t in lines]), None)


def _json_objects(text: str):
    """Every complete {...} in the text, the LAST one first.

    The old parser took everything from the first "{" to the last "}", so a
    single brace in the model's prose broke the whole answer. Scanning for
    balanced objects and starting from the end also matches how the reasoning
    models write: whatever they were thinking, the answer comes last.
    """
    found = []
    depth = 0
    start = -1
    in_str = False
    escaped = False
    for i, ch in enumerate(text):
        if in_str:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}" and depth:
            depth -= 1
            if depth == 0 and start >= 0:
                found.append(text[start:i + 1])
                start = -1
    for chunk in reversed(found):
        try:
            yield json.loads(chunk)
        except Exception:
            continue


def _looks_like_reasoning(text: str) -> bool:
    """True when the text reads like a model's notes rather than an answer."""
    marks = ("**", "*   ", "-   ", "分析输入", "约束条件", "上下文：",
             "分析意图", "构思回复", "让我", "首先，", "步骤")
    hits = sum(1 for m in marks if m in text)
    return hits >= 2 or text.count("\n") > 12


def _normalise_pct(replies):
    """Make the three percentages share a sensible 100%, or spread them evenly."""
    pcts = [p for _, p in replies]
    if not any(p > 0 for p in pcts):
        spread = [86, 11, 3]
        return [(t, spread[i] if i < len(spread) else 1)
                for i, (t, _) in enumerate(replies)]
    total = sum(p for p in pcts if p > 0)
    if total <= 0:
        return replies
    out = []
    running = 0
    for i, (t, p) in enumerate(replies):
        if i == len(replies) - 1:
            out.append((t, max(1, 100 - running)))
        else:
            v = max(1, int(round(p / total * 100)))
            running += v
            out.append((t, v))
    return out


# ---------------------------------------------------------------- OCR bridge

class OcrBridge:
    """One long-lived PowerShell process; one JSON command per line on stdin.

    Keeping it alive matters: starting PowerShell costs ~600ms, which would
    dwarf the ~250ms the OCR itself takes when watching a chat every couple of
    seconds.
    """

    def __init__(self, helper: str):
        self.helper = helper
        self.proc = None
        self.lock = threading.Lock()
        self.cv = threading.Condition(self.lock)
        self.pending = {}
        self.next_id = 1

    def start(self) -> None:
        if self.proc and self.proc.poll() is None:
            return
        self.proc = subprocess.Popen(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-File", self.helper, "-Server"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            creationflags=0x08000000)         # CREATE_NO_WINDOW
        threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self) -> None:
        try:
            for raw in iter(self.proc.stdout.readline, b""):
                try:
                    msg = json.loads(raw.decode("utf-8", "replace"))
                except Exception:
                    continue
                with self.cv:
                    self.pending[msg.get("id")] = msg
                    self.cv.notify_all()
        except Exception:
            pass
        finally:
            with self.cv:
                self.cv.notify_all()

    def request(self, cmd: dict, timeout: float = 20.0) -> list:
        self.start()
        with self.lock:
            rid = self.next_id
            self.next_id += 1
        payload = dict(cmd)
        payload["id"] = rid
        try:
            self.proc.stdin.write((json.dumps(payload) + "\n").encode("utf-8"))
            self.proc.stdin.flush()
        except Exception as e:
            raise RuntimeError("屏幕识别组件没起来：%s" % e)
        deadline = time.time() + timeout
        with self.cv:
            while rid not in self.pending:
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise TimeoutError("屏幕识别超时")
                self.cv.wait(remaining)
            msg = self.pending.pop(rid)
        if not msg.get("ok"):
            raise RuntimeError(msg.get("error") or "屏幕识别失败")
        return msg.get("lines") or []

    def stop(self) -> None:
        try:
            if self.proc and self.proc.poll() is None:
                self.proc.stdin.write(b'{"cmd":"quit"}\n')
                self.proc.stdin.flush()
                self.proc.wait(timeout=2)
        except Exception:
            pass
        try:
            if self.proc:
                self.proc.kill()
        except Exception:
            pass


# ------------------------------------------------------- manual region picker

class RegionPicker:
    """Full-screen dimmer + drag, kept only as a fallback for odd windows."""

    def __init__(self, root: tk.Misc, on_done, initial=None):
        self.on_done = on_done
        vx, vy, vw, vh = wc.virtual_screen()
        self.origin = (vx, vy)
        self.top = tk.Toplevel(root)
        self.top.overrideredirect(True)
        self.top.geometry("%dx%d+%d+%d" % (vw, vh, vx, vy))
        self.top.attributes("-topmost", True)
        try:
            self.top.attributes("-alpha", 0.30)
        except Exception:
            pass
        self.top.configure(bg="#0b1220")
        self.canvas = tk.Canvas(self.top, bg="#0b1220", highlightthickness=0,
                                cursor="crosshair")
        self.canvas.pack(fill="both", expand=True)
        self.canvas.create_text(
            vw // 2, 48, fill="#ffffff", font=("Microsoft YaHei UI", 18, "bold"),
            text="手动框选：拖出聊天消息所在的那一块　·　按 Esc 取消")
        self.start = None
        self.rect = None
        self.canvas.bind("<ButtonPress-1>", self._press)
        self.canvas.bind("<B1-Motion>", self._drag)
        self.canvas.bind("<ButtonRelease-1>", self._release)
        self.top.bind("<Escape>", lambda e: self._cancel())
        self.top.focus_force()
        if initial:
            self.start = (initial[0], initial[1])
            self._drag_box(initial[0], initial[1], initial[0] + initial[2],
                           initial[1] + initial[3])

    def _drag_box(self, x1, y1, x2, y2):
        ox, oy = self.origin
        if self.rect:
            self.canvas.delete(self.rect)
        self.rect = self.canvas.create_rectangle(
            x1 - ox, y1 - oy, x2 - ox, y2 - oy,
            outline="#10A37F", width=3, fill="#10A37F", stipple="gray25")

    def _press(self, e):
        self.start = (e.x_root, e.y_root)
        self._drag_box(e.x_root, e.y_root, e.x_root, e.y_root)

    def _drag(self, e):
        if self.start:
            self._drag_box(self.start[0], self.start[1], e.x_root, e.y_root)

    def _release(self, e):
        if not self.start:
            return self._cancel()
        x1, y1 = self.start
        x2, y2 = e.x_root, e.y_root
        x, y = min(x1, x2), min(y1, y2)
        w, h = abs(x2 - x1), abs(y2 - y1)
        self.top.destroy()
        self.on_done((x, y, w, h) if (w >= 24 and h >= 24) else None)

    def _cancel(self):
        try:
            self.top.destroy()
        except Exception:
            pass
        self.on_done(None)


# ---------------------------------------------------------------- main panel

BG = "#f2f3f5"
CARD = "#ffffff"
INK = "#111827"
SUB = "#6b7280"
ACCENT = "#10a37f"
GREEN = "#16a34a"
AMBER = "#d97706"
RED = "#dc2626"
FONT = "Microsoft YaHei UI"

# Give up on a single request after this many seconds. Without it one stalled
# socket left the panel saying "正在想…" for minutes and blocked every later
# message from ever being analysed.
# Must sit above REQUEST_TIMEOUT: it is the "the panel is not stuck, honestly"
# release valve, and firing it before the request itself gives up would throw
# away an answer that was about to arrive.
WATCHDOG_SECONDS = 50.0

# A new incoming message has to survive a second look before the model is
# asked about it. Read on. The delay is deliberately not a round number: the
# chrome that blinks under the last message repeats on a roughly half-second
# beat, and a second look taken an odd interval later lands on the other half
# of that beat, which is what makes the check meaningful.
VERIFY_DELAY_MS = 550

# Only short candidates are put through that second look. Chrome is short by
# nature - a timestamp, the typing strip, "以上是打招呼的内容" - while a real
# message that is long enough to be worth answering a second later is still
# worth answering now, and making every reply wait for the second look is what
# the "为什么这么慢" complaint was about.
CONFIRM_MAX_LEN = 10

# Two model calls closer together than this are almost always the same message
# read twice; the second one just makes the panel flicker back to "正在想…".
MIN_REQUEST_GAP = 1.2

# How often to look at the chat window. Cheap when nothing changed, because the
# pixel comparison short-circuits before OCR.
SPEED_CHOICES = {
    "极快（0.4 秒）": 400,
    "快（0.6 秒）": 600,
    "标准（1 秒）": 1000,
    "省电（2 秒）": 2000,
}
SPEED_LABELS = {v: k for k, v in SPEED_CHOICES.items()}


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.cfg = self.load_cfg()
        self.bridge = OcrBridge(resource_path("ocr_helper.ps1"))
        self.chat = None                 # wc.ChatWindow
        self.running = False
        self.busy = False                # a model call is in flight
        self.capturing = False           # a capture+OCR pass is in flight
        self.job = None
        self.last_pass = 0.0
        self.msgs = []
        self.mixed = True
        self.convo_title = ""
        self.picks = []                  # [(WindowInfo, Layout)] for the picker
        self.last_chat_rect = None

        # Change detection: pixel digests tell us whether OCR is worth running at
        # all, and the incoming-message fingerprint tells us whether the model is
        # worth asking.
        self.last_body_hash = None
        self.head_hash = None
        self.analyzed_key = ""
        self.pending_key = ""            # a candidate new message, not yet confirmed
        self.pending_hash = None         # the frame it was read from
        self.pending_hits = 0            # how many looks in a row agreed on it
        self.verify_job = None           # the booked "second look"
        self.force_capture = False       # next capture ignores the pixel digest
        self.last_payload = None         # (msgs, mixed, title) of the last OCR pass
        self.settle_job = None
        self.gen_start = 0.0
        self.queued = False
        self.last_request_at = 0.0
        self.last_replies_sig = ""
        self.last_refs = []              # 军师 references used for the last answer
        self.gen_token = 0               # invalidates answers for a conversation we left
        self.last_target_check = 0.0

        root.title(APP_NAME)
        root.configure(bg=BG)
        root.geometry("430x820")
        root.minsize(400, 640)
        root.attributes("-topmost", bool(self.cfg.get("alwaysOnTop", True)))

        self._build()
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        root.after(300, self.bootstrap)

    # ------------------------------------------------------------ 配置

    def load_cfg(self) -> dict:
        try:
            with open(config_path(), "r", encoding="utf-8") as f:
                cfg = json.load(f)
        except Exception:
            cfg = {}
        base = PROVIDER_BY_ID["deepseek"]
        defaults = {
            "providerId": "deepseek",
            "protocol": PROTOCOL_OPENAI,
            "baseUrl": base[3],
            "model": base[4],
            "apiKey": "",
            "temperature": "",
            "extraHeaders": "",
            "relationship": "对方是我的朋友，我们平时随便聊",
            # Polling is now nearly free when nothing moved (one PrintWindow,
            # no OCR), so the default is fast enough that a new message is
            # noticed in well under a second.
            "intervalMs": 600,
            "autoGenerate": True,
            "noThinking": True,
            "alwaysOnTop": True,
            "followWindow": True,
            "manualRect": None,
            "startRunning": True,
            "skillId": "general",
        }
        defaults.update(cfg or {})
        # v1.1 retuned the default; an old config still carries 2000ms and would
        # keep the sluggish feel this release is about. Migrate it once, and
        # leave any other value alone (that one was a deliberate choice).
        if not defaults.get("tunedV11"):
            if defaults.get("intervalMs") == 2000:
                defaults["intervalMs"] = 600
            defaults["tunedV11"] = True
        return defaults

    def save_cfg(self) -> None:
        try:
            with open(config_path(), "w", encoding="utf-8") as f:
                json.dump(self.cfg, f, ensure_ascii=False, indent=2)
        except Exception as e:
            self.set_status("保存设置失败：%s" % e, RED)

    # ------------------------------------------------------------ 布局

    def _build(self) -> None:
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=12, pady=10)

        head = tk.Frame(outer, bg=BG)
        head.pack(fill="x")
        tk.Label(head, text=APP_NAME, bg=BG, fg=INK,
                 font=(FONT, 17, "bold")).pack(side="left")
        self.badge = tk.Label(head, text=" 已暂停 ", bg="#e5e7eb", fg=SUB,
                              font=(FONT, 9, "bold"))
        self.badge.pack(side="left", padx=8)
        tk.Button(head, text="设置", font=(FONT, 10), relief="flat", bg=CARD, fg=INK,
                  activebackground="#e5e7eb", padx=12, pady=3, cursor="hand2",
                  command=self.open_settings).pack(side="right")

        control = tk.Frame(outer, bg=CARD)
        control.pack(fill="x", pady=(8, 0))
        inner = tk.Frame(control, bg=CARD)
        inner.pack(fill="x", padx=10, pady=9)
        self.run_btn = tk.Button(inner, text="开始采集", font=(FONT, 11, "bold"),
                                 bg=ACCENT, fg="white", activebackground="#0d8c6c",
                                 activeforeground="white", relief="flat", padx=16,
                                 pady=5, cursor="hand2", command=self.toggle_run)
        self.run_btn.pack(side="left")
        self.follow_var = tk.BooleanVar(value=bool(self.cfg.get("followWindow", True)))
        tk.Checkbutton(inner, text="跟随窗口", variable=self.follow_var, bg=CARD,
                       fg=SUB, font=(FONT, 9), activebackground=CARD,
                       selectcolor=CARD,
                       command=self.on_follow_toggle).pack(side="left", padx=6)
        tk.Button(inner, text="重新识别窗口", font=(FONT, 9), relief="flat",
                  bg="#eef0f3", fg=INK, activebackground="#e0e3e8", padx=10, pady=4,
                  cursor="hand2",
                  command=lambda: self.refresh_target(user=True)).pack(side="right")

        # Skill switch: one button to call the 军师 and one to cancel it, side by
        # side so which one is doing what is never a guess. The 军师 is a whole
        # methodology plus a knowledge base, so it is never on by accident.
        # On its own row: in the run row above, the labels ran off the window.
        skill_row = tk.Frame(control, bg=CARD)
        skill_row.pack(fill="x", padx=10, pady=(0, 9))
        tk.Label(skill_row, text="技能", font=(FONT, 9), bg=CARD, fg=SUB).pack(
            side="left", padx=(0, 6))
        self.skill_on_btn = tk.Button(skill_row, text="调用军师", font=(FONT, 9, "bold"),
                                      relief="flat", bg="#eef0f3", fg=INK,
                                      activebackground="#e0e3e8", padx=8, pady=4,
                                      cursor="hand2",
                                      command=lambda: self.set_skill("goutoujunshi"))
        self.skill_on_btn.pack(side="left", padx=2)
        self.skill_off_btn = tk.Button(skill_row, text="取消军师", font=(FONT, 9, "bold"),
                                       relief="flat", bg="#eef0f3", fg=INK,
                                       activebackground="#e0e3e8", padx=8, pady=4,
                                       cursor="hand2",
                                       command=lambda: self.set_skill("general"))
        self.skill_off_btn.pack(side="left", padx=2)
        self.refresh_skill_button()

        card = self.card(outer)
        top = tk.Frame(card, bg=CARD)
        top.pack(fill="x")
        tk.Label(top, text="回复建议", bg=CARD, fg=INK,
                 font=(FONT, 14, "bold")).pack(side="left")
        self.updated = tk.Label(top, text="", bg=CARD, fg=SUB, font=(FONT, 8))
        self.updated.pack(side="right")

        picker = tk.Frame(card, bg=CARD)
        picker.pack(fill="x", pady=(6, 0))
        tk.Label(picker, text="当前会话", bg=CARD, fg=SUB,
                 font=(FONT, 9)).pack(side="left")
        self.target_var = tk.StringVar()
        self.target_combo = ttk.Combobox(picker, textvariable=self.target_var,
                                         state="readonly", font=(FONT, 9), width=20)
        self.target_combo.pack(side="left", padx=6)
        self.target_combo.bind("<<ComboboxSelected>>", self.on_target_pick)
        tk.Button(picker, text="手动框选", font=(FONT, 8), relief="flat", bg="#eef0f3",
                  fg=INK, activebackground="#e0e3e8", padx=8, pady=2, cursor="hand2",
                  command=self.pick_region_manual).pack(side="right")

        self.headline = tk.Label(card, text="正在找聊天窗口…", bg=CARD, fg=SUB,
                                 font=(FONT, 9), anchor="w", justify="left",
                                 wraplength=380)
        self.headline.pack(fill="x", pady=(6, 0))

        # Who is being answered. "自动跟随" is the default: the panel follows
        # whichever window/contact you move to. Picking a name instead pins the
        # panel to that person, so a busy group or the wrong window can never
        # answer for somebody else.
        who = tk.Frame(card, bg=CARD)
        who.pack(fill="x", pady=(6, 0))
        tk.Label(who, text="对话人", bg=CARD, fg=SUB,
                 font=(FONT, 9)).pack(side="left")
        self.who_btn = tk.Button(who, text="自动跟随", font=(FONT, 9, "bold"),
                                 relief="flat", bg="#eef0f3", fg=INK,
                                 activebackground="#e0e3e8", padx=8, pady=3,
                                 cursor="hand2", command=self.open_conversation_picker)
        self.who_btn.pack(side="left", padx=6)
        self.who_hint = tk.Label(who, text="", bg=CARD, fg=SUB, font=(FONT, 8))
        self.who_hint.pack(side="left")
        self.refresh_who_button()

        self.latest_label = tk.Label(card, text="对方最近说", bg=CARD, fg=SUB,
                                     font=(FONT, 9))
        self.latest_label.pack(anchor="w", pady=(8, 2))
        self.latest = tk.Text(card, height=3, wrap="word", font=(FONT, 10),
                              relief="flat", bg="#f7f8fa", fg=INK, padx=8, pady=6,
                              cursor="arrow", highlightthickness=1,
                              highlightbackground="#e5e7eb")
        self.latest.pack(fill="x")
        self.latest.configure(state="disabled")

        self.advice = tk.Label(card, text="", bg=CARD, fg=INK, font=(FONT, 11, "bold"),
                               anchor="w", justify="left", wraplength=380)
        self.advice.pack(fill="x", pady=(8, 0))
        # Shown only while the 军师 answers: its read of the situation, plus the
        # reference documents it actually used. Without this the two skills look
        # like the same assistant with slightly different wording.
        self.stance = tk.Label(card, text="", bg=CARD, fg="#0d8c6c",
                               font=(FONT, 9), anchor="w", justify="left",
                               wraplength=380)
        self.stance.pack(fill="x", pady=(2, 0))
        self.stance.pack_forget()
        self.intent = tk.Label(card, text="", bg=CARD, fg=SUB, font=(FONT, 9),
                               anchor="w", justify="left", wraplength=380)
        self.intent.pack(fill="x", pady=(2, 0))
        self.replies_box = tk.Frame(card, bg=CARD)
        self.replies_box.pack(fill="x", pady=(8, 0))

        foot = tk.Frame(outer, bg=BG)
        foot.pack(fill="x", pady=(10, 0))
        self.status = tk.Label(foot, text="", bg=BG, fg=SUB, font=(FONT, 9),
                               anchor="w", justify="left", wraplength=400)
        self.status.pack(fill="x")
        tk.Label(foot, bg=BG, fg=SUB, font=(FONT, 8), anchor="w", justify="left",
                 wraplength=400,
                 text=("AI 建议仅供参考 · 只把回复填进输入框，发送由你确认（v%s）\n"
                       "基于 Jev 聊天助手（github.com/jev-chat/jev-chat-jarvis）"
                       "二次开发的简化版 · MIT" % VERSION)).pack(fill="x", pady=(6, 0))

    def card(self, parent) -> tk.Frame:
        frame = tk.Frame(parent, bg=CARD, highlightthickness=1,
                         highlightbackground="#e5e7eb")
        frame.pack(fill="x", pady=(10, 0))
        inner = tk.Frame(frame, bg=CARD)
        inner.pack(fill="both", expand=True, padx=12, pady=10)
        return inner

    def small_button(self, parent, label, cmd, primary=False):
        return tk.Button(parent, text=label, font=(FONT, 9), relief="flat",
                         cursor="hand2",
                         bg=ACCENT if primary else "#eef0f3",
                         fg="white" if primary else INK,
                         activebackground="#0d8c6c" if primary else "#e0e3e8",
                         activeforeground="white" if primary else INK,
                         padx=12, pady=3, command=cmd)

    # ------------------------------------------------------------ 小工具

    def set_status(self, text: str, color: str = SUB) -> None:
        self.status.configure(text=text, fg=color)

    def set_badge(self, text: str, color: str) -> None:
        self.badge.configure(text=" %s " % text, bg=color,
                             fg="white" if color != "#e5e7eb" else SUB)

    def set_latest(self, text: str) -> None:
        self.latest.configure(state="normal")
        self.latest.delete("1.0", "end")
        self.latest.insert("1.0", text)
        self.latest.configure(state="disabled")

    # ------------------------------------------------------------ 目标窗口

    def bootstrap(self) -> None:
        self.refresh_target()
        # Open the TLS connection now so the first reply the user waits for is
        # not also paying for a handshake.
        warm_up_connection(self.cfg)
        if self.cfg.get("startRunning", True):
            self.start_run()

    def refresh_target(self, user=False) -> None:
        """Pick the chat window, and refill the one-click picker list.

        Known clients are recognised automatically. For anything else the list
        falls back to the open windows, so picking the right one is a single
        click - and that choice is remembered for next time.
        """
        matched = wc.list_chat_windows()
        known_hwnds = {info.hwnd for info, _l in matched}
        self.picks = matched + [(info, wc.GENERIC_LAYOUT)
                                for info in self._fallback_windows(known_hwnds)]
        labels = []
        for info, lay in self.picks:
            title = info.title.strip() or info.cls
            labels.append(("★ %s · %s" % (title, lay.name)) if lay.id != "generic"
                          else title)
        self.target_combo.configure(values=labels or ["（没有可选的窗口）"])

        manual = tuple(self.cfg["manualRect"]) if self.cfg.get("manualRect") else None

        # 1. the window remembered from a previous run
        pinned = self.cfg.get("pinnedTarget")
        if pinned:
            for info, lay in self.picks:
                if info.title == pinned.get("title") and info.cls == pinned.get("cls"):
                    # Already attached to exactly this window: refresh the list
                    # and stop. Re-adopting would wipe the panel for nothing.
                    if (self.chat is not None and self.chat.hwnd == info.hwnd
                            and self.chat.layout.id == lay.id):
                        return
                    self._adopt(info, lay, manual)
                    return

        # 2. automatic detection among the recognised clients
        found = wc.find_chat_window()
        if found is not None:
            idx = next((i for i, (info, _l) in enumerate(self.picks)
                        if info.hwnd == found.hwnd), -1)
            if idx >= 0:
                info, lay = self.picks[idx]
                self._adopt(info, lay, manual)
                return

        self.chat = None
        self.target_var.set("")
        msg = ("没自动认出聊天窗口。请把聊天窗口打开，然后在右边下拉里选一个，"
               "程序会记住它。")
        self.headline.configure(text=msg, fg=AMBER)
        if user:
            self.set_status(msg, AMBER)

    def _fallback_windows(self, exclude_hwnds):
        """Visible windows a chat client might be, when nothing matched."""
        skip_classes = {
            "Progman", "Shell_TrayWnd", "Shell_SecondaryTrayWnd",
            "Windows.UI.Core.CoreWindow", "Cua.AgentCursorOverlay",
            "CEF-OSC-WIDGET", "TaskListThumbnailWnd",
        }
        out = []
        for info in wc.list_windows(min_w=420, min_h=340):
            if info.hwnd in exclude_hwnds:
                continue
            if not info.title.strip():
                continue
            if info.title.strip() == wc.OWN_TITLE:
                continue
            if info.cls in skip_classes:
                continue
            out.append(info)
        return out

    def _adopt(self, info, lay, manual) -> None:
        """Make `info` the live target and tell the user what was chosen."""
        self.chat = wc.ChatWindow(info.hwnd, lay, manual)
        labels = self.target_combo.cget("values")
        idx = next((i for i, (i2, _l) in enumerate(self.picks) if i2.hwnd == info.hwnd), -1)
        if idx >= 0 and idx < len(labels):
            self.target_var.set(labels[idx])
        self.last_chat_rect = self.chat.rect
        # A new window means everything we knew about the old one is void.
        self.last_body_hash = None
        self.head_hash = None
        self.analyzed_key = ""
        self.msgs = []
        # A different conversation: the old suggestions must go, and the next
        # result has to redraw even if it happens to match the old signature.
        self.last_replies_sig = ""
        for child in self.replies_box.winfo_children():
            child.destroy()
        self.advice.configure(text="")
        self.intent.configure(text="")
        name = (info.title.strip() or lay.name)
        self.headline.configure(
            text="已锁定「%s」，自动读取消息区（不用框选）。" % name, fg=GREEN)
        # Anything the previous conversation had in flight is now worthless.
        self.gen_token += 1
        self.busy = False
        self.gen_start = 0.0
        self.follow_window()

    def _follow_foreground(self) -> None:
        """Attach to whichever chat window the user just brought to the front.

        A pinned target is a starting point, not a promise: with two chats open,
        switching to the other one used to keep reading the first (the window is
        still there, just behind), so every reply was about the wrong person.
        """
        if self.chat is not None and not self.chat.alive:
            self.refresh_target()
            return
        fg = wc.foreground_window_info()
        if fg is None or (self.chat is not None and fg.hwnd == self.chat.hwnd):
            return
        # Only windows the picker offers are ever adopted, so a random dialog or
        # our own panel can never steal the capture. A window opened after
        # startup is not in the list yet, which is what the re-scan is for.
        hit = next(((i, l) for i, l in self.picks if i.hwnd == fg.hwnd), None)
        if hit is None:
            self.refresh_target()
            if self.chat is not None and self.chat.hwnd == fg.hwnd:
                return
            hit = next(((i, l) for i, l in self.picks if i.hwnd == fg.hwnd), None)
        if hit is None:
            return
        info, lay = hit
        # The picker also lists every other window on the desktop, as a manual
        # fallback. Following THAT blindly meant opening a browser or an editor
        # made the panel start reading it - and the "conversation" it sent the
        # model was the text of whatever was on screen. Only two kinds of window
        # may be followed: a client that is recognised as a chat app, or one of
        # the same class as the window the user pinned (two WeChat windows, two
        # hand-picked ones).
        pinned_cls = (self.cfg.get("pinnedTarget") or {}).get("cls")
        if lay.id == "generic" and info.cls != pinned_cls:
            return
        self._adopt(info, lay, None)
        self.set_status("已跟着你切到「%s」，正在读这段对话…"
                        % (info.title.strip() or lay.name), ACCENT)

    def on_target_pick(self, _event=None) -> None:
        idx = self.target_combo.current()
        if idx < 0 or idx >= len(self.picks):
            return
        info, lay = self.picks[idx]
        manual = tuple(self.cfg["manualRect"]) if self.cfg.get("manualRect") else None
        self._adopt(info, lay, manual)
        # Remember this window so the next launch skips the picker entirely.
        self.cfg["pinnedTarget"] = {"title": info.title, "cls": info.cls}
        self.save_cfg()
        self.set_status("已锁定「%s」，下次打开程序会直接用这个窗口。"
                        % (info.title.strip() or lay.name), GREEN)

    def on_follow_toggle(self) -> None:
        self.cfg["followWindow"] = bool(self.follow_var.get())
        self.save_cfg()
        if self.follow_var.get():
            self.follow_window()

    def pick_region_manual(self) -> None:
        if not self.chat:
            self.set_status("先让程序找到一个聊天窗口，再手动框选。", AMBER)
            return
        RegionPicker(self.root, self._region_picked,
                     initial=self.chat.message_rect())

    def _region_picked(self, rect) -> None:
        if rect is None:
            self.set_status("已取消手动框选。", SUB)
            return
        self.cfg["manualRect"] = list(rect)
        self.save_cfg()
        if self.chat:
            self.chat.manual_rect = tuple(rect)
            self.last_body_hash = None
            self.analyzed_key = ""
        self.headline.configure(text="已改用手动框选的区域。", fg=GREEN)

    def follow_window(self) -> None:
        """Park the panel next to the chat window, like a companion card."""
        if not (self.chat and self.follow_var.get()):
            return
        rect = self.chat.rect
        if not rect:
            return
        try:
            self.root.update_idletasks()
            pw = self.root.winfo_width() or 430
            ph = self.root.winfo_height() or 820
            vx, vy, vw, vh = wc.virtual_screen()
            cx, cy, cw, ch = rect
            x = cx - pw - 10
            if x < vx + 4:
                x = cx + cw + 10           # no room on the left; go right
            if x + pw > vx + vw:
                x = max(vx + 4, vx + vw - pw - 4)
            y = min(max(vy + 4, cy), vy + vh - ph - 4)
            self.root.geometry("%dx%d+%d+%d" % (pw, ph, int(x), int(y)))
        except Exception:
            pass

    # ------------------------------------------------------------ 采集循环

    def toggle_run(self) -> None:
        if self.running:
            self.stop_run()
        else:
            self.start_run()

    def start_run(self) -> None:
        if self.running:
            return
        self.running = True
        self.run_btn.configure(text="暂停采集", bg="#eef0f3", fg=INK)
        self.set_badge("采集中", ACCENT)
        if not (self.cfg.get("baseUrl") and self.cfg.get("model")):
            self.set_status("还没配接口，点右上角「设置」填一个 API。", AMBER)
        elif not self.cfg.get("apiKey"):
            self.set_status("还没填 API Key，点右上角「设置」。", AMBER)
        else:
            self.set_status("采集中：每 %d 秒读一次聊天窗口。"
                            % max(1, int(self.cfg.get("intervalMs", 2000) / 1000)), ACCENT)
        self.tick()

    def stop_run(self) -> None:
        self.running = False
        self._clear_pending()
        self.force_capture = False
        self.run_btn.configure(text="开始采集", bg=ACCENT, fg="white")
        self.set_badge("已暂停", "#e5e7eb")
        if self.job:
            try:
                self.root.after_cancel(self.job)
            except Exception:
                pass
            self.job = None
        self.set_status("已暂停采集。", SUB)

    def tick(self) -> None:
        if not self.running:
            return
        now = time.time()

        # Follow the user to another chat window. PrintWindow keeps rendering the
        # window we are attached to even while it sits behind another one, so
        # without this check the panel went on answering the previous
        # conversation - the user switches windows and the replies never change.
        # Only a window the user actually brought to the front counts, and only
        # once a second.
        if (now - self.last_target_check) >= 1.0 and not self.cfg.get("manualRect"):
            self.last_target_check = now
            self._follow_foreground()

        # Live elapsed time under the spinner. A frozen "正在想…" is
        # indistinguishable from a hang, and a request that never returns used to
        # lock the app out of ever analysing again.
        if self.busy and self.gen_start:
            secs = now - self.gen_start
            self.updated.configure(text="更新中…  %.1f 秒" % secs, fg=ACCENT)
            if secs > WATCHDOG_SECONDS:
                self.busy = False
                self.gen_start = 0.0
                self.updated.configure(text="", fg=SUB)
                self.set_status(
                    "这次接口超过 %d 秒没返回，已放弃。检查网络，或换一个更快的模型。"
                    % WATCHDOG_SECONDS, RED)
                self._run_queued()

        # keep following the chat window as it moves
        if self.chat and self.follow_var.get():
            rect = self.chat.rect
            if rect and rect != self.last_chat_rect:
                self.last_chat_rect = rect
                self.follow_window()

        interval = max(0.8, self.cfg.get("intervalMs", 2000) / 1000.0)
        if (not self.capturing) and (now - self.last_pass) >= interval:
            if self.chat is None or not self.chat.alive:
                self.refresh_target()
            if self.chat:
                self.last_pass = now
                self.capturing = True
                force = self.force_capture
                self.force_capture = False
                threading.Thread(target=self._capture_worker,
                                 args=(force,), daemon=True).start()
        self.job = self.root.after(250, self.tick)

    def _capture_worker(self, force: bool = False) -> None:
        chat = self.chat
        try:
            # One PrintWindow for both regions; the header strip comes out of the
            # same grab instead of costing a second capture.
            title_box, head_h = chat.title_rect()
            got = chat.capture_regions(
                [chat.message_rect(), title_box or chat.header_rect()],
                ["litechat_cap.bmp", "litechat_head.bmp"],
                with_pixels=True,
                # Message area: find the list/message boundary in the picture
                # (the list is user-resizable, so the constant alone lets the
                # contact list leak into the prompt). Header: leave on its
                # measured constant - a one-pixel shift there makes Windows'
                # recogniser drop a two-character conversation name.
                refine_left=[True, False])
            if not got or not got[0][0]:
                self.root.after(0, lambda: self._capture_done(None, "抓不到聊天窗口的画面"))
                return
            body_path, body_hash, body_pixels = got[0]
            head_path, head_hash = (got[1][0], got[1][1]) if len(got) > 1 else (None, None)

            # Identical pixels -> no OCR at all. This is what makes polling every
            # second affordable, and polling fast is what makes a new message get
            # noticed (and answered) sooner.
            #
            # `force` is the one exception: the "second look" that confirms a new
            # message has to actually happen even though the screen has not moved.
            if body_hash == self.last_body_hash and not force:
                self.root.after(0, self._poll_idle)
                return

            # The second look is usually asking exactly one question - "is the
            # screen still showing what I read a moment ago?" - and when the
            # answer is yes the OCR answer is already known. Reusing it is what
            # keeps the confirmation from being felt as extra delay.
            if force and self.pending_key and body_hash == self.pending_hash \
                    and self.last_payload is not None:
                payload = self.last_payload + (body_hash,)
                self.root.after(0, lambda: self._capture_done(payload, None))
                return
            self.last_body_hash = body_hash

            # scale 3: chat text is small, and OCR is much better on the
            # magnified crop (boxes come back in original pixels either way).
            # Measured against 2 and 4 on a real conversation crop: 4 loses
            # characters, 3 is the cleanest of the three.
            lines = self.bridge.request(
                {"cmd": "ocr", "path": body_path, "scale": 3}, timeout=25)
            area = chat.message_rect() or (0, 0, 1, 1)
            msgs, mixed = wc.group_lines(lines, area[2],
                                         classify=make_bubble_classifier(body_pixels))

            # The conversation title is a second OCR call; only pay for it when
            # that strip actually changed (usually never, while chatting).
            title = ""
            if head_path and head_hash != self.head_hash:
                self.head_hash = head_hash
                title = self._ocr_title(head_path, head_h)
            self.root.after(
                0, lambda: self._capture_done((msgs, mixed, title, body_hash), None))
        except Exception as e:
            self.root.after(0, lambda: self._capture_done(None, str(e)))

    def _poll_idle(self) -> None:
        self.capturing = False

    def _ocr_title(self, path: str, header_h: int = 0) -> str:
        """The contact name from the strip above the messages.

        The crop handed to OCR is taller than the strip (see
        [winchat.ChatWindow.title_rect]) because a picture holding one short
        line is frequently read as nothing at all. Only lines that sit inside
        the strip count, so the first message can never be mistaken for a name.
        """
        try:
            lines = self.bridge.request(
                {"cmd": "ocr", "path": path, "scale": 3}, timeout=15)
            for ln in lines:
                t = (ln.get("t") or "").strip()
                if not t or re.fullmatch(r"[\d:：/\s]+", t):
                    continue
                if header_h and int(ln.get("y") or 0) > header_h * 0.9:
                    continue          # the first message, not the name
                return t[:24]
        except Exception:
            pass
        return ""

    def _capture_done(self, payload, error) -> None:
        self.capturing = False
        if error:
            self.set_status("读取失败：%s" % error, RED)
            return
        if payload is None:
            return
        frame = None
        if len(payload) > 3:
            msgs, mixed, title, frame = payload
        else:
            msgs, mixed, title = payload
        self.last_payload = (msgs, mixed, title)
        self.mixed = mixed
        # A different name in the header means a different person, even though
        # it is the same window: switching contacts inside one WeChat window does
        # not move the window at all. Clearing here is what stops the previous
        # conversation's candidates from sitting there looking like an answer to
        # this one.
        if title and self._is_different_conversation(title):
            self._enter_conversation(title)
        if title:
            self.convo_title = title
            self._remember_conversation(title)
        # Pinned to one person: everything else is read but never answered.
        locked = (self.cfg.get("lockedConversation") or "").strip()
        if locked and title and not _same_person(locked, title):
            self.set_status("当前是「%s」，你锁定的是「%s」，所以不分析。"
                            % (title, locked), AMBER)
            return
        if locked and not title:
            # A pin can only be honoured when the header can be read. Saying so
            # is better than quietly answering whoever happens to be on screen.
            self.set_status("已锁定「%s」，但这一屏读不到会话名（窗口太小或字体太小），"
                            "这次按自动跟随处理。" % locked, AMBER)
        if not msgs:
            self.set_status("这一屏没认出文字，等对方发新消息再看。", AMBER)
            return

        self.msgs = msgs
        # "对方最近说": the tail of incoming messages, or the tail of everything
        # when the window was too narrow to tell the two sides apart.
        incoming = [t for s, t in msgs if s == "other"] or [t for _s, t in msgs]
        self.set_latest("\n".join(incoming[-3:]))
        # Say which of the two this is. Claiming "对方最近说" while showing the
        # user's own message is how the panel ended up suggesting replies to the
        # user's own words.
        self.latest_label.configure(
            text="对方最近说" if mixed else "最近消息（没分清谁说的）",
            fg=SUB if mixed else AMBER)
        who = self.convo_title or (self.chat.layout.name if self.chat else "")
        self.headline.configure(
            text=("会话：%s · 读到 %d 条消息%s" %
                  (who, len(msgs), "" if mixed else "（这一屏只看到一个方向）")),
            fg=SUB)

        # Decide whether this is worth a model call. Only a change to the NEWEST
        # incoming message counts: OCR jitter further up the screen, a new
        # timestamp, or the user's own message must not set off another round
        # trip. Re-analysing on any text change was what made the old build sit
        # on "正在想…" forever.
        key = _incoming_key(incoming)
        if os.environ.get("LITECHAT_DEBUG_POLL"):
            # Scaffolding for the regression scripts: shows what one poll read
            # and why it did or did not trigger another model call.
            print("[poll] msgs=%d key=%r same=%s hits=%d" % (
                len(msgs), key, _looks_same(key, self.analyzed_key),
                self.pending_hits), flush=True)
        if not key or _looks_same(key, self.analyzed_key):
            self._clear_pending()
            return

        # A genuinely new line. It still has to survive one more look before the
        # model is asked, because the newest thing on a chat screen is often not
        # a message at all: WeChat repaints a timestamp and a "对方正在输入…"
        # strip under the last message, and OCR reads that flicker as a fresh
        # line every time it comes back - the shared phrase list only catches it
        # when the strip is read cleanly, which it frequently is not. Chrome
        # moves on between the two looks; a real message is still there.
        if len(key) > CONFIRM_MAX_LEN:
            accepted = True
            self.pending_key, self.pending_hash, self.pending_hits = "", None, 0
        else:
            accepted, self.pending_key, self.pending_hash, self.pending_hits = \
                confirm_pending(self.pending_key, self.pending_hash,
                                self.pending_hits, key, frame)
        if not accepted:
            self._schedule_verify()
            return

        self.analyzed_key = key          # claimed now, so it can never fire twice
        self._clear_pending()

        if not self.cfg.get("autoGenerate", True):
            self.set_status("内容有更新，点「生成回复」看看建议。", ACCENT)
            return
        # A short settle before asking: the frame we just read may still be
        # mid-paint, and asking twice for one message is the slowest thing this
        # program can do.
        if self.settle_job:
            try:
                self.root.after_cancel(self.settle_job)
            except Exception:
                pass
        self.settle_job = self.root.after(200, self.generate)

    def _clear_pending(self) -> None:
        self.pending_key = ""
        self.pending_hash = None
        self.pending_hits = 0
        if self.verify_job:
            try:
                self.root.after_cancel(self.verify_job)
            except Exception:
                pass
            self.verify_job = None

    # ------------------------------------------------- who is being answered

    def refresh_who_button(self) -> None:
        """Show, in one glance, whether the panel follows or is pinned."""
        locked = (self.cfg.get("lockedConversation") or "").strip()
        self.who_btn.configure(
            # No emoji: Tk's default font does not always have one, and a tofu
            # box where the lock should be is worse than a plain word.
            text=("锁定：" + locked) if locked else "自动跟随",
            bg="#fde68a" if locked else "#eef0f3",
            fg=INK,
            activebackground="#fcd34d" if locked else "#e0e3e8")
        self.who_hint.configure(
            text=("只答这个人，别人发消息不分析" if locked
                  else "跟着你切换的窗口/联系人走"))

    def _remember_conversation(self, title: str) -> None:
        """Keep the names we have actually seen, newest first, for the picker."""
        name = (title or "").strip()
        if not plausible_conversation_name(name):
            return
        known = [n for n in (self.cfg.get("knownConversations") or []) if n != name]
        known.insert(0, name)
        # Six, not twelve: this is a shortcut to the chats somebody answers, and
        # a long list is mostly OCR misreads (see plausible_conversation_name).
        self.cfg["knownConversations"] = known[:MAX_KNOWN_CONVERSATIONS]

    def clear_known_conversations(self) -> None:
        """Empty the picker's list.

        The list comes out of the header read, which goes wrong now and then.
        Six names that are wrong is better than twelve, but no names at all is
        better than six that nobody recognises - and before this the only way
        out of a bad list was deleting the config file by hand. The phone build
        has the same button (OverlayController / MainActivity 清空这个名单).
        """
        self.cfg["knownConversations"] = []
        self.save_cfg()

    def open_conversation_picker(self) -> None:
        """A list to pick from: follow automatically, or pin one person."""
        dlg = tk.Toplevel(self.root)
        dlg.title("选择对话人")
        dlg.configure(bg=BG)
        dlg.geometry("360x420")
        dlg.transient(self.root)

        tk.Label(dlg, text="这一屏要回答谁？", bg=BG, fg=INK,
                 font=(FONT, 12, "bold")).pack(anchor="w", padx=14, pady=(12, 2))
        tk.Label(dlg,
                 text="程序不会去点你的聊天软件。选一个人＝只分析 TA 的消息；"
                      "选「自动跟随」＝跟着你打开的窗口和人走。",
                 bg=BG, fg=SUB, font=(FONT, 9), wraplength=330,
                 justify="left").pack(anchor="w", padx=14)

        locked = (self.cfg.get("lockedConversation") or "").strip()
        current = self.convo_title or ""
        known = list(self.cfg.get("knownConversations") or [])[:MAX_KNOWN_CONVERSATIONS]
        names = list(known)
        for extra in (current, locked):
            if extra and extra not in names:
                names.append(extra)
        # The open chat windows are conversations too, and picking one switches
        # what the panel reads - that is the multi-window case.
        windows = [(info, lay) for info, lay in self.picks
                   if lay.id != "generic" or self.chat and info.hwnd == self.chat.hwnd]

        body = tk.Frame(dlg, bg=BG)
        body.pack(fill="both", expand=True, padx=14, pady=10)

        def row(label, detail, active, command):
            text = ("✓ " if active else "　") + label
            b = tk.Button(body, text=text, font=(FONT, 10, "bold" if active else "normal"),
                          relief="flat", anchor="w", padx=10, pady=6,
                          bg="#e8f5ee" if active else "#f2f3f5", fg=INK,
                          activebackground="#e0e3e8", cursor="hand2",
                          command=command)
            b.pack(fill="x", pady=2)
            if detail:
                tk.Label(body, text=detail, bg=BG, fg=SUB, font=(FONT, 8),
                         anchor="w", justify="left", wraplength=320).pack(
                             fill="x", padx=(12, 0))

        def choose(name):
            self.cfg["lockedConversation"] = name
            self.save_cfg()
            self.refresh_who_button()
            dlg.destroy()
            if name:
                self._clear_for_pin()
                self.set_status("已锁定「%s」：只分析 TA 的消息。" % name, ACCENT)
            else:
                self.set_status("已回到自动跟随：你切到谁就读谁。", ACCENT)
                if self.chat:
                    self.last_body_hash = None
                    self.analyzed_key = ""

        row("自动跟随", "默认：跟着你切换的窗口和联系人", not locked,
            lambda: choose(""))
        if known:
            tk.Label(body, text="只保留最近读到的 %d 个（最多 %d 个）。"
                     % (len(known), MAX_KNOWN_CONVERSATIONS),
                     bg=BG, fg=SUB, font=(FONT, 8), anchor="w").pack(
                         fill="x", padx=(2, 0), pady=(6, 0))
        else:
            tk.Label(body, text="还没读到过会话名：读一次聊天就会出现在这里。",
                     bg=BG, fg=SUB, font=(FONT, 8), anchor="w").pack(
                         fill="x", padx=(2, 0), pady=(6, 0))
        for name in names:
            row(name,
                "当前正在看" if name == current else "锁定后只分析 TA",
                name == locked, lambda n=name: choose(n))
        for info, lay in windows:
            label = info.title.strip() or lay.name
            row("窗口：" + label, "切到这个窗口去读",
                self.chat is not None and info.hwnd == self.chat.hwnd,
                lambda i=info, l=lay: (dlg.destroy(), self._adopt(i, l, None)))

        if known:
            tk.Button(dlg, text="清空这个名单", font=(FONT, 9), relief="flat",
                      bg="#eef0f3", fg=SUB, padx=14, pady=4, cursor="hand2",
                      command=lambda: (self.clear_known_conversations(),
                                       dlg.destroy(),
                                       self.open_conversation_picker(),
                                       self.set_status("已清空对话人名单。", ACCENT))
                      ).pack(pady=(8, 0))

        tk.Button(dlg, text="关闭", font=(FONT, 10), relief="flat", bg="#eef0f3",
                  fg=INK, padx=14, pady=6, cursor="hand2",
                  command=dlg.destroy).pack(pady=(0, 12))

    def _clear_for_pin(self) -> None:
        """A pin changes who is answered, so the old candidates must go."""
        self.gen_token += 1
        self.busy = False
        self.gen_start = 0.0
        self.analyzed_key = ""
        self.last_replies_sig = ""
        for child in self.replies_box.winfo_children():
            child.destroy()
        self.advice.configure(text="")
        self.intent.configure(text="")
        self.stance.pack_forget()
        self.set_latest("")

    def _pin_allows(self, title: str) -> bool:
        """When pinned, only that person's messages are worth a reply."""
        locked = (self.cfg.get("lockedConversation") or "").strip()
        if not locked:
            return True
        return _same_person(locked, title or "")

    def _is_different_conversation(self, title: str) -> bool:
        """True when the header now names somebody else.

        OCR wobbles on a name ("李沅汐" one frame, "李沅汐 " or a single wrong
        character the next), and wobbing must not be mistaken for a switch - that
        would wipe good candidates for no reason. Only a name that clearly is
        not the same one counts.
        """
        old = (self.convo_title or "").strip()
        new = (title or "").strip()
        if not old or not new or old == new:
            return False
        a = re.sub(r"[\s·、,，.。]+", "", old)
        b = re.sub(r"[\s·、,，.。]+", "", new)
        if a == b:
            return False
        if min(len(a), len(b)) < 2:
            return True
        return difflib.SequenceMatcher(None, a, b).ratio() < 0.5

    def _enter_conversation(self, title: str) -> None:
        """Forget everything about the conversation we just left."""
        print("[conversation] switched to %r" % title)
        self.convo_title = title
        self._remember_conversation(title)
        self.refresh_who_button()
        self.msgs = []
        self.mixed = True
        self.analyzed_key = ""
        self.last_body_hash = None
        self.head_hash = None
        self.last_replies_sig = ""
        self.last_refs = []
        # An answer that was already on its way for the previous conversation
        # must not land on this one.
        self.gen_token += 1
        self.busy = False
        self.gen_start = 0.0
        self.queued = False
        self._clear_pending()
        for child in self.replies_box.winfo_children():
            child.destroy()
        self.advice.configure(text="")
        self.intent.configure(text="")
        self.stance.pack_forget()
        self.set_latest("")
        self.set_status("已切到「%s」，正在读这段对话…" % title, ACCENT)

    def _schedule_verify(self) -> None:
        """Book the second look that a new incoming message has to survive."""
        if self.verify_job:
            return                      # one is already booked
        self.verify_job = self.root.after(VERIFY_DELAY_MS, self._force_capture_now)

    def _force_capture_now(self) -> None:
        self.verify_job = None
        if not self.running:
            return
        self.force_capture = True
        self.last_pass = 0.0            # let the next tick start right away

    # ------------------------------------------------------------ 生成

    def refresh_skill_button(self) -> None:
        """Paint the pair so the active one is the one that describes reality."""
        on = (self.cfg.get("skillId") or "general") == "goutoujunshi"
        self.skill_on_btn.configure(
            text="调用军师 ✓" if on else "调用军师",
            bg=ACCENT if on else "#eef0f3",
            fg="white" if on else INK,
            activebackground="#0d8c6c" if on else "#e0e3e8")
        self.skill_off_btn.configure(
            text="取消军师 ✓" if not on else "取消军师",
            bg="#eef0f3" if on else ACCENT,
            fg=INK if on else "white",
            activebackground="#e0e3e8" if on else "#0d8c6c")

    def is_military_mode(self) -> bool:
        return (self.cfg.get("skillId") or "general") == "goutoujunshi"

    def set_skill(self, skill_id: str) -> None:
        """Call or cancel the 军师. Called by the two buttons and nothing else."""
        if skill_id == "goutoujunshi" and self.cfg.get("skillId") == "goutoujunshi":
            self.set_status("军师已经在用了。", SUB)
            return
        if skill_id == "general" and self.cfg.get("skillId") != "goutoujunshi":
            self.set_status("当前是通用模式，军师没开。", SUB)
            return
        self.cfg["skillId"] = skill_id
        self.save_cfg()
        self.refresh_skill_button()
        self.last_refs = []
        if self.stance.winfo_ismapped():
            self.stance.pack_forget()
        self.set_status("已取消军师，回到通用模式。" if skill_id == "general"
                        else "已调用狗头军师，重新分析中…",
                        SUB if skill_id == "general" else ACCENT)
        if self.msgs:
            self.generate()

    def generate(self) -> None:
        self.settle_job = None
        if self.busy:
            self.queued = True
            return
        if not self.msgs:
            self.set_status("还没读到聊天内容。", AMBER)
            return
        if not (self.cfg.get("baseUrl") and self.cfg.get("model")):
            self.set_status("还没配接口，点右上角「设置」。", RED)
            return
        now = time.time()
        gap = now - self.last_request_at
        if gap < MIN_REQUEST_GAP:
            self.settle_job = self.root.after(
                int((MIN_REQUEST_GAP - gap) * 1000) + 20, self.generate)
            return
        self.last_request_at = now
        self.busy = True
        self.gen_start = time.time()
        # Stamp this request. If the user switches conversation while it is in
        # flight, the answer describes somebody else - [_generate_done] drops it
        # instead of painting it over the new conversation.
        self.gen_token += 1
        token = self.gen_token
        self.updated.configure(text="更新中…  0.0 秒", fg=ACCENT)
        # Everything already on screen stays put. Wiping the candidate cards the
        # moment a new request goes out is what made the panel unusable: the user
        # would reach for a reply and it would vanish under the cursor. The
        # placeholder only appears when there is genuinely nothing to keep.
        if not self.replies_box.winfo_children():
            self.intent.configure(text="")
            tk.Label(self.replies_box, text="正在想一句合适的回复…", bg=CARD,
                     fg=SUB, font=(FONT, 9), anchor="w").pack(fill="x")
        convo = "\n".join(("%s：%s" % ("我" if s == "me" else "对方", t))
                          for s, t in self.msgs[-12:])
        who = self.convo_title or "未命名会话"
        threading.Thread(target=self._generate_worker, args=(who, token),
                         daemon=True).start()

    def _run_queued(self) -> None:
        """A message arrived while a request was in flight; take it now."""
        if self.queued:
            self.queued = False
            self.generate()

    def _generate_worker(self, who: str, token: int) -> None:
        # 20 messages instead of 12: input tokens are cheap next to a reply that
        # misses the point, and more context is what keeps three short lines from
        # drifting off topic.
        user = build_user_prompt(who, self.cfg.get("relationship") or "",
                                 self.msgs[-20:], self.mixed)
        skill = SKILLS.get(self.cfg.get("skillId") or "general", SKILLS["general"])
        refs = []
        if skill["id"] == "goutoujunshi":
            refs = pick_references(user)
            if refs:
                user += "\n\n" + "\n\n".join(
                    "【参考：%s】\n%s" % (title, body) for title, body in refs)
                print("[skill] goutoujunshi refs:", [t for t, _ in refs])
        self.last_refs = [t for t, _ in refs]
        try:
            result = self._ask_model(skill["prompt"], user)
            if not result[3]:
                raise LlmError(
                    "模型没按格式给回复（两次都是）。换一个模型，或把 temperature 留空再试。")
            self.root.after(0, lambda: self._generate_done(result, None, token))
        except Exception as e:
            self.root.after(0, lambda: self._generate_done(None, str(e), token))

    def _ask_model(self, skill_prompt: str, user: str):
        """One answer, with one retry aimed at reasoning models.

        A model that thinks before answering can spend its whole output budget
        doing that and have nothing left to answer with - then `content` is
        missing and only `reasoning_content` comes back. When that happens (or
        when the answer holds no reply at all) ask once more, telling it not to
        show the thinking, with room for both.
        """
        raw = ""
        try:
            raw = chat(self.cfg, skill_prompt, user)
        except LlmError as e:
            if str(e) != "THINKING_ONLY":
                raise
        result = parse_suggestion(raw) if raw else (None, None, None, [])
        if result[3]:
            return result
        print("[retry] first answer had no replies; asking again with a bigger budget")
        try:
            raw = chat(self.cfg, skill_prompt + DIRECT_ANSWER_RULE, user,
                       max_tokens=RETRY_TOKENS)
        except LlmError as e:
            if str(e) == "THINKING_ONLY":
                raise LlmError("模型只输出了思考过程，没有给出回复。"
                               "把模型名换成非推理模型试试，或者稍后重试。")
            raise
        return parse_suggestion(raw)

    def _generate_done(self, result, error, token=None) -> None:
        if token is not None and token != self.gen_token:
            # The conversation moved on while this was on the wire.
            print("[conversation] dropping a stale answer (token %s != %s)"
                  % (token, self.gen_token))
            # The lock has to be released even though the answer is thrown away.
            # Leaving it held is what made the panel stop generating anything
            # until the watchdog fired: the new conversation was never asked
            # about, because generate() kept queueing behind a request that had
            # already been abandoned.
            self.busy = False
            self.gen_start = 0.0
            self.updated.configure(text="", fg=SUB)
            self._run_queued()
            return
        seconds = (time.time() - self.gen_start) if self.gen_start else 0.0
        self.busy = False
        self.gen_start = 0.0
        if error:
            # The previous suggestions stay usable; only the status line reports
            # the failure.
            self.updated.configure(text="%s 更新" % time.strftime("%H:%M"), fg=SUB)
            self.set_status("出错了：%s" % error, RED)
            self._run_queued()
            return
        # Unpack by index: parse_suggestion grew a fifth field (the 军师's read of
        # the situation) and a hard 4-tuple unpack here left the panel frozen on
        # "更新中…" the moment a model answered.
        intent, danger, advice, replies = result[0], result[1], result[2], result[3]
        stance = result[4] if len(result) > 4 else None
        if advice:
            self.advice.configure(text="建议：%s" % advice, fg=INK)
        elif intent:
            self.advice.configure(text="对方意图：%s" % intent, fg=INK)
        else:
            self.advice.configure(text="模型只给了候选回复", fg=INK)

        if self.is_military_mode():
            bits = ["军师模式"]
            if stance:
                bits.append("判断：%s" % stance)
            if self.last_refs:
                bits.append("参考：%s" % "、".join(self.last_refs))
            self.stance.configure(text="　·　".join(bits))
            if not self.stance.winfo_ismapped():
                self.stance.pack(fill="x", pady=(2, 0), before=self.intent)
        elif self.stance.winfo_ismapped():
            self.stance.pack_forget()

        bits = []
        if intent and advice:
            bits.append("可能意图：%s" % intent)
        if danger:
            bits.append("紧张度 %d/9" % danger)
        if not self.mixed:
            bits.append("未区分我/对方")
        self.intent.configure(
            text=("　·　".join(bits)),
            fg=(GREEN if (danger or 1) < 3 else (AMBER if (danger or 1) < 6 else RED)))

        # Redraw only when the candidates actually differ. Rebuilding identical
        # widgets still steals the click that was already on its way.
        signature = "|".join("%s:%d" % (t, p) for t, p in replies)
        if signature != self.last_replies_sig:
            self.last_replies_sig = signature
            self.render_replies(replies)
        self.updated.configure(text="%s 更新" % time.strftime("%H:%M"), fg=SUB)
        note = ""
        if LAST_HINT_REJECTED:
            note = "（这个接口不支持关闭模型思考，回复会慢一些）"
        self.set_status("建议已更新（用时 %.1f 秒）%s，选一句填入聊天框，发送由你确认。"
                        % (seconds, note), GREEN)
        self._run_queued()

    def render_replies(self, replies) -> None:
        for child in self.replies_box.winfo_children():
            child.destroy()
        if not replies:
            tk.Label(self.replies_box, text="（模型没有给出候选回复）", bg=CARD,
                     fg=SUB, font=(FONT, 9), anchor="w").pack(fill="x")
            return
        for i, (text, pct) in enumerate(replies[:3]):
            top = (i == 0)
            bg = "#e7f7f1" if top else "#f5f6f8"
            row = tk.Frame(self.replies_box, bg=bg)
            row.pack(fill="x", pady=(0, 6))
            head = tk.Frame(row, bg=bg)
            head.pack(fill="x", padx=10, pady=(7, 0))
            tk.Label(head, bg=bg, fg=ACCENT, font=(FONT, 9, "bold"), anchor="w",
                     text=("推荐回复 · %d%%" % pct) if top else ("备选 %d · %d%%" % (i, pct))
                     ).pack(side="left")
            tk.Button(head, text="复制", font=(FONT, 8), relief="flat", bg="#ffffff",
                      fg=INK, activebackground="#e0e3e8", padx=8, pady=1,
                      cursor="hand2",
                      command=lambda t=text: self.copy(t)).pack(side="right")
            tk.Label(row, text=text, bg=bg, fg=INK, font=(FONT, 11), anchor="w",
                     justify="left", wraplength=350).pack(fill="x", padx=10, pady=(3, 5))
            tk.Button(row, text="填入聊天框", font=(FONT, 9, "bold"), bg=ACCENT,
                      fg="white", activebackground="#0d8c6c", activeforeground="white",
                      relief="flat", padx=14, pady=3, cursor="hand2",
                      command=lambda t=text: self.fill(t)).pack(anchor="w", padx=10,
                                                                 pady=(0, 8))

    def copy(self, text: str) -> None:
        try:
            self.root.clipboard_clear()
            self.root.clipboard_append(text)
            self.root.update()
            self.set_status("已复制到剪贴板。", GREEN)
        except Exception as e:
            self.set_status("复制失败：%s" % e, RED)

    def fill(self, text: str) -> None:
        """Click the chat's input box and paste. Never sends."""
        if not self.chat or not self.chat.alive:
            self.refresh_target()
            if not self.chat:
                self.set_status("找不到聊天窗口，先把聊天窗口打开。", RED)
                return
        try:
            ok, msg = self.chat.paste_text(text)
        except Exception as e:
            ok, msg = False, "填入失败：%s" % e
        self.set_status(msg, GREEN if ok else RED)

    # ------------------------------------------------------------ 设置 / 收尾

    def open_settings(self) -> None:
        SettingsWindow(self.root, self.cfg, self.saved_from_settings)

    def saved_from_settings(self) -> None:
        self.save_cfg()
        warm_up_connection(self.cfg)
        try:
            self.root.attributes("-topmost", bool(self.cfg.get("alwaysOnTop", True)))
        except Exception:
            pass
        self.follow_var.set(bool(self.cfg.get("followWindow", True)))
        self.set_status("设置已保存。", GREEN)

    def on_close(self) -> None:
        self.running = False
        self.save_cfg()
        self.bridge.stop()
        self.root.destroy()


# ------------------------------------------------------------------- settings

class SettingsWindow:
    """One page, one subject: the third-party API.

    The confirm/save buttons are pinned to the bottom of the window instead of
    sitting at the end of the form, so they are visible no matter how far the
    fields scroll - the earlier build put them after the last field and people
    did not find them.
    """

    def __init__(self, root: tk.Tk, cfg: dict, on_save):
        self.cfg = cfg
        self.on_save = on_save
        self.widgets = {}
        self.win = tk.Toplevel(root)
        self.win.title("设置 · " + APP_NAME)
        self.win.configure(bg=BG)
        self.win.geometry("540x720")
        self.win.transient(root)

        # --- pinned action bar (packed first so it can never be pushed off)
        bar = tk.Frame(self.win, bg=CARD, highlightthickness=1,
                       highlightbackground="#e5e7eb")
        bar.pack(side="bottom", fill="x")
        inner = tk.Frame(bar, bg=CARD)
        inner.pack(fill="x", padx=14, pady=10)
        tk.Button(inner, text="确认并保存", font=(FONT, 12, "bold"), bg=ACCENT,
                  fg="white", activebackground="#0d8c6c", activeforeground="white",
                  relief="flat", padx=24, pady=9, cursor="hand2",
                  command=self.save).pack(side="left")
        tk.Button(inner, text="保存并测试连接", font=(FONT, 11), bg="#eef0f3",
                  fg=INK, activebackground="#e0e3e8", relief="flat", padx=16, pady=9,
                  cursor="hand2", command=self.test).pack(side="left", padx=8)
        self.status = tk.Label(inner, text="", bg=CARD, fg=SUB, font=(FONT, 9),
                               anchor="w", justify="left", wraplength=200)
        self.status.pack(side="left", padx=8)

        # --- scrollable form
        holder = tk.Frame(self.win, bg=BG)
        holder.pack(side="top", fill="both", expand=True)
        canvas = tk.Canvas(holder, bg=BG, highlightthickness=0)
        scroll = ttk.Scrollbar(holder, orient="vertical", command=canvas.yview)
        canvas.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        outer = tk.Frame(canvas, bg=BG)
        window_id = canvas.create_window((0, 0), window=outer, anchor="nw")
        outer.bind("<Configure>",
                   lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>",
                    lambda e: canvas.itemconfigure(window_id, width=e.width))
        canvas.bind_all("<MouseWheel>",
                        lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"))

        pad = tk.Frame(outer, bg=BG)
        pad.pack(fill="both", expand=True, padx=14, pady=12)
        tk.Label(pad, text="接口设置", bg=BG, fg=INK,
                 font=(FONT, 16, "bold")).pack(anchor="w")
        tk.Label(pad, text="填一个第三方大模型接口就能用：地址、密钥、模型三样齐全即可。",
                 bg=BG, fg=SUB, font=(FONT, 9)).pack(anchor="w", pady=(2, 8))

        self._combo(pad, "服务商预设", "providerId")
        self._combo(pad, "协议（决定请求格式）", "protocol")
        self._entry(pad, "接口地址（base URL）", "baseUrl",
                    "例如 https://api.deepseek.com/v1")
        self._entry(pad, "API Key", "apiKey", secret=True)
        self._entry(pad, "模型名", "model", "例如 deepseek-chat")
        self._entry(pad, "temperature", "temperature",
                    "留空用服务商默认值（推理模型建议留空）")
        self._entry(pad, "对方是谁 / 关系", "relationship",
                    "例如：对方是我的女朋友，我们在一起两年了")
        self._check(pad, "读到新内容就自动生成建议", "autoGenerate")
        self._check(pad, "关闭模型思考（更快，推荐）", "noThinking")
        self._hint(pad, "推理模型会先想一大段再回答，光想就要 10-25 秒。"
                        "关掉后实测 3-4 秒。个别接口不认识这个参数会自动忽略它。")
        self._check(pad, "窗口置顶", "alwaysOnTop")
        self._check(pad, "面板跟随聊天窗口移动", "followWindow")
        self._check(pad, "打开程序就开始采集", "startRunning")

        self._label(pad, "刷新速度")
        speed = tk.StringVar(value=SPEED_LABELS.get(self.cfg.get("intervalMs", 600),
                                                    "标准（1 秒）"))
        combo = ttk.Combobox(pad, textvariable=speed, state="readonly",
                             values=list(SPEED_CHOICES.keys()), font=(FONT, 10))
        combo.pack(fill="x")
        self.widgets["speed"] = combo
        self._hint(pad, "越快越跟手；屏幕没变化时几乎不耗性能，默认就够快")

        tk.Label(pad, text="额外请求头（每行一条 Name: value，一般不用填）",
                 bg=BG, fg=SUB, font=(FONT, 9)).pack(anchor="w", pady=(10, 2))
        self.headers = tk.Text(pad, height=3, font=(FONT, 10), relief="flat",
                               bg=CARD, fg=INK, padx=8, pady=6,
                               highlightthickness=1, highlightbackground="#e5e7eb")
        self.headers.pack(fill="x")
        self.headers.insert("1.0", cfg.get("extraHeaders", ""))
        tk.Label(pad, text="改完点底部的「确认并保存」。", bg=BG, fg=SUB,
                 font=(FONT, 8)).pack(anchor="w", pady=(8, 0))

        self.widgets["providerId"].bind("<<ComboboxSelected>>", self.on_provider)

    # ---- widgets

    def _label(self, parent, text):
        tk.Label(parent, text=text, bg=BG, fg=INK,
                 font=(FONT, 10, "bold")).pack(anchor="w", pady=(8, 2))

    def _hint(self, parent, text):
        tk.Label(parent, text=text, bg=BG, fg=SUB,
                 font=(FONT, 8)).pack(anchor="w", pady=(1, 0))

    def _combo(self, parent, label, key):
        self._label(parent, label)
        values = PROVIDER_LABELS if key == "providerId" else PROTOCOL_LABELS
        var = tk.StringVar()
        widget = ttk.Combobox(parent, textvariable=var, values=values,
                              state="readonly", font=(FONT, 10))
        widget.pack(fill="x")
        if key == "providerId":
            idx = next((i for i, p in enumerate(PROVIDERS)
                        if p[0] == self.cfg.get("providerId")), CUSTOM_INDEX)
        else:
            idx = PROTOCOL_IDS.index(self.cfg.get("protocol", PROTOCOL_OPENAI))
        widget.current(idx)
        self.widgets[key] = widget

    def _entry(self, parent, label, key, hint="", secret=False):
        self._label(parent, label)
        var = tk.StringVar(value=str(self.cfg.get(key, "") or ""))
        widget = tk.Entry(parent, textvariable=var, font=(FONT, 10), relief="flat",
                          bg=CARD, fg=INK, show="*" if secret else "",
                          insertbackground=INK, highlightthickness=1,
                          highlightbackground="#e5e7eb")
        widget.pack(fill="x", ipady=5)
        if hint:
            self._hint(parent, hint)
        self.widgets[key] = widget
        return widget

    def _check(self, parent, label, key):
        var = tk.BooleanVar(value=bool(self.cfg.get(key, True)))
        widget = tk.Checkbutton(parent, text=label, variable=var, bg=BG, fg=INK,
                                activebackground=BG, selectcolor=CARD, font=(FONT, 10),
                                anchor="w")
        widget.pack(fill="x", pady=(8, 0))
        widget.var = var
        self.widgets[key] = widget
        return widget

    # ---- read / write

    def on_provider(self, _event=None):
        pos = self.widgets["providerId"].current()
        if pos < 0:
            return
        pid, _label, protocol, base_url, model = PROVIDERS[pos]
        if pid == "custom":
            return
        self.widgets["protocol"].current(PROTOCOL_IDS.index(protocol))
        for key, value in (("baseUrl", base_url), ("model", model)):
            widget = self.widgets[key]
            widget.delete(0, "end")
            widget.insert(0, value)

    def collect(self):
        idx = max(self.widgets["providerId"].current(), 0)
        self.cfg["providerId"] = PROVIDERS[idx][0]
        self.cfg["protocol"] = PROTOCOL_IDS[max(self.widgets["protocol"].current(), 0)]
        for key in ("baseUrl", "apiKey", "model", "temperature", "relationship"):
            self.cfg[key] = self.widgets[key].get().strip()
        for key in ("autoGenerate", "noThinking", "alwaysOnTop", "followWindow",
                    "startRunning"):
            self.cfg[key] = bool(self.widgets[key].var.get())
        self.cfg["extraHeaders"] = self.headers.get("1.0", "end").strip()
        self.cfg["intervalMs"] = SPEED_CHOICES.get(
            self.widgets["speed"].get(), self.cfg.get("intervalMs", 600))

    def save(self, silent=False):
        self.collect()
        self.on_save()
        if not silent:
            self.status.configure(text="已保存 ✔", fg=GREEN)

    def test(self):
        self.save(silent=True)
        self.status.configure(text="正在测试…", fg=SUB)

        def work():
            try:
                reply = ping(self.cfg)
                msg, color = "连接成功：%s" % reply[:20], GREEN
            except Exception as e:
                msg, color = "连接失败：%s" % e, RED
            self.win.after(0, lambda: self.status.configure(text=msg, fg=color))

        threading.Thread(target=work, daemon=True).start()


def main() -> None:
    wc.set_dpi_awareness()
    root = tk.Tk()
    try:
        root.tk.call("tk", "scaling", root.winfo_fpixels("1i") / 72.0)
    except Exception:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()

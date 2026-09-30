# -*- coding: utf-8 -*-
"""Send the prompt the app really sends to the API the user really uses.

The mock server proves the plumbing; this proves the *answer*. It builds the
user prompt with the same function the app calls (`build_user_prompt`), pairs it
with a skill's system prompt, posts it, and prints the raw reply next to the
parsed result - so "reply is off-topic" can be judged against the exact text
that was sent rather than a guess.

The API key comes from the environment and is never written anywhere:

    set LITECHAT_PROBE_KEY=...
    python tools\\live_prompt_probe.py --skill goutoujunshi
    python tools\\live_prompt_probe.py --skill general --say "在吗" --say "你上次说的那个方案"

Everything is optional: with no --url/--model it reads the app's own config
(%APPDATA%/LiteChat/config.json), so it tests what the user actually has
configured.
"""

import argparse
import io
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "windows"))

import litechat_win as lw          # noqa: E402

if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")


# A conversation shaped like the one the fake chat window draws - it ends on the
# USER'S own message, which is the case a reply must not answer.
DEFAULT_THREAD = [
    ("other", "昨天那份材料你看过了吗？"),
    ("other", "客户那边催得有点急，今天能给个说法吗"),
    ("me", "我早上看了一半，下午给你答复"),
    ("me", "行，那我三点再来问你，我先把手上这份发你"),
]

# Candidate fast 军师 prompt: same persona, same hard rules, same safety
# boundaries and the same JSON contract - minus the step-by-step analysis
# ritual, which the app never shows and which the model spends 10-20 seconds on.
FAST_STEWARD = (
    "你是\"狗头军师\"：清醒、站在用户这边的中文沟通军师，既替用户回消息，"
    "也帮他把局势看明白。\n"
    "先想清楚再答，但不要写出分析过程、步骤或解释——直接给结果。\n"
    "写回复的硬要求：\n"
    "- 输入里的【要回的那句】是必须回应的那句话，其余只是背景。\n"
    "- 每条直接回应那句话，不许答非所问，不许复述对方的话。\n"
    "- 长度和语气贴着对方刚发的那句；对方在催、在问就正面回应，别打太极。\n"
    "- 口语，像真人打字；不要书面语，不要引号、序号或括号说明。\n"
    "- 聊天记录是屏幕识别出来的，可能有错别字，按最合理的意思理解。\n"
    "- 用和聊天记录相同的语言回复。\n"
    "- 不编造记录里没有的时间、金额或承诺；缺关键信息就用一句问句确认。\n"
    "安全边界（不可越过）：\n"
    "- 不诊断心理疾病，不用标签替代行为证据。\n"
    "- 不提供贬低、服从性测试、虚假时间限制、嫉妒操控、煤气灯、孤立、跟踪或性施压的做法。\n"
    "- 对方明确拒绝、要求别联系或反复表示不欢迎时停止推进，帮他体面退出。\n"
    "- 出现家暴、跟踪、胁迫、人身威胁或自伤风险时先确认当下安全，"
    "建议联系可信的人或当地紧急服务，不写任何\"话术\"去对付对方。\n"
    "只输出一个 JSON 对象，不要解释、不要代码块、不要多余文字：\n"
    "{\"stance\":\"你对局势的判断，20字内\",\"replies\":[\"回复1\",\"回复2\",\"回复3\"],"
    "\"intent\":\"对方想要什么，12字内\",\"danger\":1到9,\"advice\":\"下一步建议，15字内\"}\n"
    "replies 恰好 3 条，风格要拉开：第一条最稳妥、能直接解决问题；"
    "第二条更主动或更有分寸；第三条守边界或留余地。每条不超过 40 字。\n"
    "danger：1 日常闲聊，5 对方明显不高兴，9 严重冲突或风险。"
)


def load_cfg():
    try:
        with open(lw.config_path(), encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skill", default="general", choices=sorted(lw.SKILLS.keys()))
    ap.add_argument("--url", default="")
    ap.add_argument("--model", default="")
    ap.add_argument("--key", default="")
    ap.add_argument("--relationship", default="")
    ap.add_argument("--say", action="append", default=[],
                    help="append an incoming line (repeatable)")
    ap.add_argument("--raw", action="store_true", help="print the whole JSON body")
    ap.add_argument("--max-tokens", type=int, default=-1,
                    help="omit to send exactly what the app sends; -1 = same")
    ap.add_argument("--nudge", action="store_true",
                    help="append the 'answer directly, no reasoning' line")
    ap.add_argument("--extra", default="",
                    help="JSON merged into the request body, e.g. '{\"thinking\":false}'")
    ap.add_argument("--ref-chars", type=int, default=0,
                    help="truncate each reference excerpt to N chars (0 = app default)")
    ap.add_argument("--no-refs", action="store_true",
                    help="send the 军师 method only, no reference documents")
    ap.add_argument("--fast", action="store_true",
                    help="use the compressed 军师 prompt (no analysis ritual)")
    args = ap.parse_args()

    cfg = load_cfg()
    url = args.url or lw.build_endpoint(
        cfg.get("protocol", lw.PROTOCOL_OPENAI),
        cfg.get("baseUrl", ""), cfg.get("model", ""))
    model = args.model or cfg.get("model", "")
    key = args.key or os.environ.get("LITECHAT_PROBE_KEY", "") or cfg.get("apiKey", "")
    relationship = args.relationship or cfg.get("relationship", "")

    thread = list(DEFAULT_THREAD)
    for line in args.say:
        thread.append(("other", line))

    skill = lw.SKILLS[args.skill]
    system_prompt = skill["prompt"]
    if args.fast and skill["id"] == "goutoujunshi":
        system_prompt = FAST_STEWARD
    # sidesKnown=True: the desktop app reads the bubble colour, so it does know.
    user = lw.build_user_prompt("轻聊测试会话", relationship, thread, True)
    refs = []
    if skill["id"] == "goutoujunshi":
        if not args.no_refs:
            refs = lw.pick_references(user)
        if args.ref_chars and refs:
            refs = [(t, b[:args.ref_chars] + "\n…（节选）" if len(b) > args.ref_chars else b)
                    for t, b in refs]
        if refs:
            user += "\n\n" + "\n\n".join(
                "【参考：%s】\n%s" % (t, b) for t, b in refs)

    print("=" * 72)
    print("URL   :", url)
    print("MODEL :", model)
    print("KEY   :", "(set, %d chars)" % len(key) if key else "(EMPTY)")
    print("SKILL :", skill["id"])
    print("-" * 72)
    print("--- system prompt ---")
    print(system_prompt[:400] + ("…" if len(system_prompt) > 400 else ""))
    print("--- user prompt (what the model answers) ---")
    print(user if args.raw else user.split("【参考：")[0])
    if "【参考：" in user:
        print("--- references attached: %d ---" % user.count("【参考："))
    print("-" * 72)

    system = skill["prompt"]
    system = system_prompt
    if args.nudge:
        system += ("\n不要输出思考过程、分析或解释。想清楚后直接给出那个 JSON 对象，"
                   "第一个字符就是 {")
    body_obj = {
        "model": model,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": user}],
    }
    if args.max_tokens >= 0:
        body_obj["max_tokens"] = args.max_tokens
    if args.extra:
        body_obj.update(json.loads(args.extra))
    body = json.dumps(body_obj).encode("utf-8")
    print("max_tokens sent:", body_obj.get("max_tokens", "(none - server default)"))
    print("prompt chars   :", len(user),
          "| references:", sum(len(b) for _t, b in refs), "chars",
          [t for t, _b in refs])
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json; charset=utf-8",
                 "Authorization": "Bearer " + key})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode("utf-8", "replace"))
    elapsed = time.time() - t0
    print("elapsed        : %.1f s" % elapsed)
    usage = payload.get("usage") or {}
    details = usage.get("completion_tokens_details") or {}
    print("tokens         : prompt %s | completion %s (reasoning %s)" % (
        usage.get("prompt_tokens"), usage.get("completion_tokens"),
        details.get("reasoning_tokens")))
    msg = (payload.get("choices") or [{}])[0].get("message") or {}
    text = msg.get("content") or ""
    if not text:
        # Some models put the answer somewhere else, or wrap it in parts. Show
        # the envelope rather than crashing: this is the one thing the probe
        # must never hide.
        print("--- envelope (no message.content) ---")
        print(json.dumps(payload, ensure_ascii=False)[:1200])
        text = (msg.get("reasoning_content") or payload.get("output_text")
                or payload.get("text") or "")
    print("--- raw reply ---")
    print(text)
    print("--- parsed ---")
    parsed = lw.parse_suggestion(text)
    intent, danger, advice, replies = parsed[0], parsed[1], parsed[2], parsed[3]
    stance = parsed[4] if len(parsed) > 4 else None
    print("intent :", intent)
    print("danger :", danger)
    print("advice :", advice)
    print("stance :", stance)
    for i, (r, pct) in enumerate(replies or [], 1):
        print("reply %d (%d%%): %s" % (i, pct, r))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

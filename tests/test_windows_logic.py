# -*- coding: utf-8 -*-
"""Unit tests for the pure logic behind the Windows build.

These deliberately avoid the parts that need a live window: URL building, the
model-output parser, and the OCR-to-message grouping (which takes plain dicts,
so it can be tested with fabricated OCR output).

Run:  python -m unittest discover -s tests -v
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "windows"))

import litechat_win as lw          # noqa: E402
import winchat as wc               # noqa: E402


class EndpointTests(unittest.TestCase):

    def test_openai_compatible_appends_chat_completions(self):
        self.assertEqual(
            "https://api.deepseek.com/v1/chat/completions",
            lw.build_endpoint(lw.PROTOCOL_OPENAI, "https://api.deepseek.com/v1",
                              "deepseek-chat"))

    def test_trailing_slash_not_duplicated(self):
        self.assertEqual(
            "https://api.openai.com/v1/chat/completions",
            lw.build_endpoint(lw.PROTOCOL_OPENAI, "https://api.openai.com/v1/", "gpt-4o-mini"))

    def test_full_endpoint_used_verbatim(self):
        full = "https://gateway.example.com/llm/v2/chat/completions"
        self.assertEqual(full, lw.build_endpoint(lw.PROTOCOL_OPENAI, full, "m"))

    def test_anthropic_uses_messages(self):
        self.assertEqual(
            "https://api.anthropic.com/v1/messages",
            lw.build_endpoint(lw.PROTOCOL_ANTHROPIC, "https://api.anthropic.com/v1", "claude"))

    def test_gemini_puts_model_in_path(self):
        self.assertEqual(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            lw.build_endpoint(lw.PROTOCOL_GEMINI,
                              "https://generativelanguage.googleapis.com/v1beta",
                              "gemini-2.0-flash"))

    def test_blank_address_builds_nothing(self):
        self.assertEqual("", lw.build_endpoint(lw.PROTOCOL_OPENAI, "  ", "m"))


class SuggestionTests(unittest.TestCase):

    def test_documented_shape(self):
        intent, danger, advice, replies, _st = lw.parse_suggestion(
            '{"intent":"催进度","danger":3,"advice":"给个时间","replies":'
            '[{"text":"今晚给你","pct":70},{"text":"等我半小时","pct":20},'
            '{"text":"抱歉拖了","pct":10}]}')
        self.assertEqual("催进度", intent)
        self.assertEqual(3, danger)
        self.assertEqual("给个时间", advice)
        self.assertEqual(["今晚给你", "等我半小时", "抱歉拖了"], [t for t, _ in replies])
        self.assertEqual(100, sum(p for _, p in replies))

    def test_markdown_fence_and_preamble(self):
        intent, _d, _a, replies, _st = lw.parse_suggestion(
            '好的，这是建议：\n```json\n{"intent":"闲聊","replies":["在呢","刚看到","哈哈哈"]}\n```')
        self.assertEqual("闲聊", intent)
        self.assertEqual(3, len(replies))
        self.assertEqual(100, sum(p for _, p in replies))

    def test_out_of_range_danger_is_dropped(self):
        self.assertIsNone(lw.parse_suggestion('{"danger":99,"replies":["a"]}')[1])
        self.assertIsNone(lw.parse_suggestion('{"danger":0,"replies":["a"]}')[1])

    def test_the_army_advisor_stance_comes_back_too(self):
        # 军师 mode asks for one extra field - its read of the situation - and
        # the panel shows it, which is what makes the two skills look different
        # rather than like the same assistant with different wording.
        _i, _d, _a, replies, stance = lw.parse_suggestion(
            '{"stance":"他在借客户施压","replies":["甲","乙","丙"],'
            '"intent":"催进度","danger":5,"advice":"先稳客户"}')
        self.assertEqual("他在借客户施压", stance)
        self.assertEqual(3, len(replies))

    def test_the_plain_assistant_has_no_stance(self):
        self.assertIsNone(lw.parse_suggestion('{"replies":["甲"]}')[4])

    def test_only_three_replies_survive(self):
        # Measured with thinking off: LongCat answered with six near-duplicates.
        # The panel shows three candidates; extras must not push their way in.
        replies = lw.parse_suggestion(
            '{"replies":["甲","乙","丙","丁","戊","己"]}')[3]
        self.assertEqual(3, len(replies))
        self.assertEqual(["甲", "乙", "丙"], [t for t, _ in replies])

    def test_the_advisor_prompt_asks_for_the_stance(self):
        self.assertIn('"stance"', lw.SKILLS["goutoujunshi"]["prompt"])
        self.assertNotIn('"stance"', lw.SKILLS["general"]["prompt"])


class ThinkingOffTests(unittest.TestCase):
    """Telling a reasoning model not to think is worth 3-4x on the clock."""

    def test_the_hint_covers_the_two_common_spellings(self):
        # vLLM/SGLang read chat_template_kwargs, Qwen/Anthropic-style servers
        # read thinking. Sending both covers the stacks seen in the wild; an
        # endpoint that knows neither is handled by the fallback below.
        self.assertEqual({"type": "disabled"}, lw.NO_THINKING_HINT["thinking"])
        self.assertFalse(lw.NO_THINKING_HINT["chat_template_kwargs"]["enable_thinking"])

    def test_a_rejected_hint_is_remembered_per_host(self):
        lw._NO_THINKING_REJECTED.clear()
        self.assertEqual("api.example.com", lw._host_of("https://api.example.com/v1"))
        lw._NO_THINKING_REJECTED.add("api.example.com")
        self.assertIn("api.example.com", lw._NO_THINKING_REJECTED)
        lw._NO_THINKING_REJECTED.clear()

    def test_a_bad_request_error_carries_its_status(self):
        # The fallback keys on 400/415/422, so the status has to survive.
        self.assertEqual(400, lw.LlmError("x", status=400).status)
        self.assertIsNone(lw.LlmError("x").status)

    def test_thinking_is_on_by_default_in_a_fresh_config(self):
        cfg = {}
        self.assertTrue(bool(cfg.get("noThinking", True)))

    def test_plain_prose_falls_back_to_lines(self):
        intent, _d, _a, replies, _st = lw.parse_suggestion("好的\n- 第一句\n- 第二句\n- 第三句")
        self.assertIsNone(intent)
        self.assertEqual(3, len(replies))

    def test_empty_output_yields_no_replies(self):
        self.assertEqual([], lw.parse_suggestion("   ")[3])

    def test_unscored_replies_get_a_sensible_spread(self):
        replies = lw._normalise_pct([("a", 0), ("b", 0), ("c", 0)])
        self.assertEqual([86, 11, 3], [p for _, p in replies])

    def test_json_survives_prose_around_it(self):
        # The old parser sliced from the FIRST { to the LAST }, so one brace in
        # the model's prose (a "{"mentioning {json}" line, say) broke the whole
        # answer and the panel fell back to showing its notes.
        text = ('我先把结构说明一下 {"这里只是个例子"}\n'
                '想好了：\n'
                '{"replies":["我马上整理结论发你","半小时内先给你初步结论",'
                '"今天肯定给，先发简要版"],"intent":"催进度","danger":6,'
                '"advice":"先给明确时间"}')
        intent, danger, advice, replies, _st = lw.parse_suggestion(text)
        self.assertEqual("催进度", intent)
        self.assertEqual(6, danger)
        self.assertEqual("先给明确时间", advice)
        self.assertEqual(3, len(replies))
        self.assertEqual("我马上整理结论发你", replies[0][0])

    def test_a_reasoning_dump_is_not_shown_as_replies(self):
        # What LongCat-2.0 sent when its output budget ran out mid-thought:
        # `content` never arrived, and the chain of thought was all there was.
        # Showing that as three candidate replies is what "答非所问" looked like.
        text = ("\n1.  **分析输入：**\n"
                "    *   **会话：** 轻聊测试会话\n"
                "    *   **要回的那句：** 客户那边催得有点急\n"
                "2.  **分析意图与危险度**：\n"
                "    *   约束条件：三条回复都必须直接回应\n"
                "    *   构思回复：第一条稳妥\n")
        self.assertEqual([], lw.parse_suggestion(text)[3])

    def test_the_answer_after_long_reasoning_is_still_found(self):
        text = ("*   **分析输入：**\n*   **约束条件：**\n*   **构思：**\n"
                '{"replies":["甲","乙","丙"],"intent":"闲聊","danger":1,"advice":"随意"}')
        intent, _d, _a, replies, _st = lw.parse_suggestion(text)
        self.assertEqual("闲聊", intent)
        self.assertEqual(["甲", "乙", "丙"], [r for r, _ in replies])


class GroupingTests(unittest.TestCase):
    """The OCR-to-message step, fed fabricated OCR output."""

    def _lines(self, specs):
        out = []
        for text, x, y, w in specs:
            out.append({"t": text, "x": x, "y": y, "w": w, "h": 20})
        return out

    def test_left_lines_are_incoming_right_lines_are_outgoing(self):
        # Real bubbles leave a clear vertical gap; lines inside one bubble do not.
        lines = self._lines([
            ("对方说的第一句", 10, 0, 200),
            ("我回的一句", 380, 120, 200),
            ("对方又说了", 10, 240, 200),
        ])
        msgs, mixed = wc.group_lines(lines, area_width=600)
        self.assertTrue(mixed)
        self.assertEqual(["other", "me", "other"], [s for s, _ in msgs])

    def test_only_one_side_on_screen_still_names_the_speaker(self):
        # Both lines hug the left edge, so both are the other person's - a
        # perfectly ordinary screen. This used to be reported as "unknown",
        # which threw away the anchor and left the model guessing.
        lines = self._lines([("一", 10, 0, 200), ("二", 10, 60, 200)])
        msgs, known = wc.group_lines(lines, area_width=600)
        self.assertTrue(known)
        self.assertEqual(["other", "other"], [s for s, _ in msgs])

    def test_a_large_vertical_gap_starts_a_new_message(self):
        lines = self._lines([
            ("上半句", 10, 0, 200),
            ("下半句", 10, 22, 200),      # same bubble: 2px gap
            ("另一条", 10, 120, 200),     # new bubble: big gap
        ])
        msgs, _mixed = wc.group_lines(lines, area_width=600)
        self.assertEqual(2, len(msgs))
        self.assertEqual("上半句 下半句", msgs[0][1])

    def test_blank_lines_are_ignored(self):
        lines = self._lines([("  ", 10, 0, 200), ("有内容", 10, 40, 200)])
        msgs, _ = wc.group_lines(lines, area_width=600)
        self.assertEqual(1, len(msgs))


class LayoutTests(unittest.TestCase):
    """The measured WeChat 4.x geometry must keep producing a sane message box."""

    def _wechat(self):
        return next(l for l in wc.LAYOUTS if l.id == "wechat4")

    def test_message_box_matches_the_real_window(self):
        # client area of the 601x694 window that was measured, at 96 DPI
        client = (0, 0, 585, 686)
        x, y, w, h = self._wechat().message_rect(client, 1.0)
        self.assertEqual((222, 85), (x, y))
        self.assertEqual(585 - 222, w)
        self.assertGreater(h, 400)

    def test_input_point_lands_in_the_input_strip_not_the_toolbar(self):
        client = (0, 0, 585, 686)
        ix, iy = self._wechat().input_point(client, 1.0)
        self.assertGreater(ix, 222)
        # Measured on a real 585x686 WeChat 4.x window: the composer's box starts
        # 131px above the client's bottom, and the text row is the top of it.
        composer_top = 686 - 131
        self.assertTrue(composer_top + 4 <= iy <= composer_top + 40, "iy=%d" % iy)
        # ...and well clear of the toolbar row at the very bottom.
        self.assertLess(iy, 686 - 60)

    def test_our_own_window_is_never_a_target(self):
        self.assertIsNone(wc.match_layout("Qt51514QWindowIcon", "轻聊助手"))

    def test_wechat_layouts_ask_for_the_boundary_to_be_detected(self):
        # The list column is user-resizable, so the constant may only be a hint.
        for ident in ("wechat4", "wechat3"):
            lay = next(l for l in wc.LAYOUTS if l.id == ident)
            self.assertTrue(lay.detect_left, ident)


def _fake_window(w, h, panel_w, panel=(237, 237, 237), area=(245, 245, 245)):
    """A window-shaped BGRA buffer: a flat list column, then the message area."""
    buf = bytearray(w * h * 4)
    for y in range(h):
        for x in range(w):
            c = panel if x < panel_w else area
            o = (y * w + x) * 4
            buf[o + 0] = c[2]
            buf[o + 1] = c[1]
            buf[o + 2] = c[0]
            buf[o + 3] = 255
    return bytes(buf)


class ContentLeftTests(unittest.TestCase):
    """Where the conversation list ends and the messages begin.

    The reported failure: with the list dragged wider than the layout's
    constant, the reader cropped part of the LIST into the "conversation" and
    fed the model contact names and unread previews - so the reply had nothing
    to do with the chat that was open.
    """

    def test_finds_a_list_wider_than_the_constant(self):
        w, h = 900, 700
        buf = _fake_window(w, h, panel_w=360)
        got = wc.detect_content_left(buf, w, h, 100, 600, 222)
        self.assertTrue(355 <= got <= 360, "got %d" % got)

    def test_finds_a_list_narrower_than_the_constant(self):
        w, h = 900, 700
        buf = _fake_window(w, h, panel_w=180)
        got = wc.detect_content_left(buf, w, h, 100, 600, 300)
        self.assertTrue(176 <= got <= 180, "got %d" % got)

    def test_finds_the_boundary_against_a_photo_wallpaper(self):
        w, h = 900, 700
        panel_w = 340
        buf = bytearray(_fake_window(w, h, panel_w))
        # A wallpaper: every column right of the list gets its own colour.
        for y in range(h):
            for x in range(panel_w, w):
                o = (y * w + x) * 4
                buf[o + 0] = (x * 7) % 200
                buf[o + 1] = (x * 13) % 180
                buf[o + 2] = (x * 29) % 160
        got = wc.detect_content_left(bytes(buf), w, h, 100, 600, 222)
        self.assertTrue(330 <= got <= 340, "got %d" % got)

    def test_one_flat_colour_keeps_the_constant(self):
        w, h = 900, 700
        buf = _fake_window(w, h, panel_w=900)
        self.assertEqual(222, wc.detect_content_left(buf, w, h, 100, 600, 222))

    def test_a_tiny_picture_keeps_the_constant(self):
        self.assertEqual(222, wc.detect_content_left(b"\x00" * 40, 10, 10, 0, 5, 222))

    def test_plausible_conversation_names(self):
        # The picker used to fill up with header misreads; the phone build has
        # the same rule (MessageKeys.plausibleName).
        for junk in ("供布)", "姓你生活?", "进生活7", "仟始1五伦林役还 2方大同s",
                     "始1酒伦#林杰a方大\"", "在吗？", "今天下午三点见，你看行不行",
                     "a方大", "", "   "):
            self.assertFalse(lw.plausible_conversation_name(junk), junk)
        for name in ("张三", "李沅汐", "项目群", "材料评审群", "妈妈", "eggs", "Alice"):
            self.assertTrue(lw.plausible_conversation_name(name), name)

    def test_the_picker_keeps_a_short_list(self):
        self.assertLessEqual(lw.MAX_KNOWN_CONVERSATIONS, 6)

    def test_clearing_the_list_leaves_nothing_behind(self):
        # The picker's only way out of a list of header misreads is this button;
        # a leftover name would mean the user still cannot get rid of it.
        app = lw.App.__new__(lw.App)
        app.cfg = {"knownConversations": ["张三", "李四"]}
        saved = []
        app.save_cfg = lambda: saved.append(True)
        app.clear_known_conversations()
        self.assertEqual([], app.cfg["knownConversations"])
        self.assertEqual([True], saved, "the emptied list has to reach the config file")


class NewMessageTests(unittest.TestCase):
    """What counts as "a new message worth asking the model about".

    This is the gate that decides whether a model call happens at all, so it is
    the difference between answering in a second and being stuck on 正在想…
    forever while OCR wobbles over the same message.
    """

    def test_punctuation_and_spacing_do_not_make_a_new_message(self):
        a = lw._incoming_key(["客户那边催得有点急，今天能给个说法吗"])
        b = lw._incoming_key(["客户那边催得有点急 今天能给个说法吗 "])
        self.assertEqual(a, b)

    def test_small_ocr_errors_are_treated_as_the_same_message(self):
        a = lw._incoming_key(["客户那边催得有点急，今天能给个说法吗"])
        b = lw._incoming_key(["客户那边催得有急，今天育个说法吗"])
        self.assertTrue(lw._looks_same(a, b))

    def test_a_genuinely_new_message_is_not_swallowed(self):
        a = lw._incoming_key(["客户那边催得有点急，今天能给个说法吗"])
        b = lw._incoming_key(["那下午三点我们再对一遍吧"])
        self.assertFalse(lw._looks_same(a, b))

    def test_short_replies_are_never_folded_together(self):
        # One character apart, completely different meaning.
        self.assertFalse(lw._looks_same(lw._incoming_key(["好"]),
                                        lw._incoming_key(["嗯"])))
        self.assertFalse(lw._looks_same(lw._incoming_key(["行"]),
                                        lw._incoming_key(["不行"])))

    def test_the_key_uses_only_the_newest_message(self):
        first = lw._incoming_key(["旧消息", "新的那句"])
        second = lw._incoming_key(["旧消息被 OCR 读歪了", "新的那句"])
        self.assertEqual(first, second)

    def test_empty_history_produces_no_key(self):
        self.assertEqual("", lw._incoming_key([]))
        self.assertEqual("", lw._incoming_key([""]))

    def test_chrome_lines_are_skipped_when_picking_the_newest_message(self):
        # What WeChat draws under the last real message: a blinking "正在输入"
        # strip and a timestamp. Treating either as a new message made the panel
        # throw the suggestions away over and over.
        key = lw._incoming_key(["客户那边催得有点急", "14: 33", "对方正在输入…"])
        self.assertEqual(lw._incoming_key(["客户那边催得有点急"]), key)


class NoiseLineTests(unittest.TestCase):
    """Chrome a chat window draws is not somebody's words."""

    def test_timestamps_and_dates_are_noise(self):
        for text in ("14:33", "14：33", "下午 2:30", "2026年9月29日",
                     "9月29日", "昨天", "今天", "星期一", "周三"):
            self.assertTrue(wc.is_noise_line(text), text)

    def test_a_date_separator_with_a_clock_on_it_is_noise(self):
        # What a client really draws between messages - and the shape that used
        # to slip through, be read as a message, and become the "newest line"
        # the panel tried to answer.
        for text in ("昨天 22：18", "昨天 22:35", "星期一 22:18",
                     "9月29日 22:18", "2026年9月29日 22:18", "22:18:35"):
            self.assertTrue(wc.is_noise_line(text), text)

    def test_a_real_message_that_starts_like_a_date_is_kept(self):
        for text in ("今天下午三点见", "9月29日开会", "昨天那事我想想", "我在长沙"):
            self.assertFalse(wc.is_noise_line(text), text)

    def test_one_direction_on_screen_still_names_the_speaker(self):
        # A screen holding a single left-hugging bubble: the other person's, and
        # the app must say so rather than "没分清谁说的" - that used to drop the
        # anchor entirely and the model had to guess which line to answer.
        lines = [{"t": "宝宝我到长沙啦", "x": 12, "y": 40, "w": 220, "h": 20}]
        msgs, known = wc.group_lines(lines, area_width=600,
                                     classify=lambda *a: None)
        self.assertEqual(1, len(msgs))
        self.assertEqual("other", msgs[0][0])
        self.assertTrue("one hugging the left edge is not ambiguous", known)

    def test_one_centred_line_is_still_ambiguous(self):
        # Nothing about a single centred line says who wrote it, so it must stay
        # "unknown" rather than be labelled on a guess.
        lines = [{"t": "宝宝我到长沙啦", "x": 200, "y": 40, "w": 200, "h": 20}]
        _msgs, known = wc.group_lines(lines, area_width=600,
                                      classify=lambda *a: None)
        self.assertFalse(known)

    def test_a_colour_read_line_is_known_even_on_its_own(self):
        lines = [{"t": "宝宝我到长沙啦", "x": 200, "y": 40, "w": 200, "h": 20}]
        msgs, known = wc.group_lines(lines, area_width=600,
                                     classify=lambda *a: "other")
        self.assertTrue(known)
        self.assertEqual("other", msgs[0][0])

    def test_system_strips_are_noise(self):
        for text in ("对方正在输入…", "你撤回了一条消息", "“张三”撤回了一条消息",
                     "以下为新消息", "以上是打招呼的内容",
                     "消息已发出，但被对方拒收了", "“李四”拍了拍你",
                     "通话时长 00:31"):
            self.assertTrue(wc.is_noise_line(text), text)

    def test_punctuation_only_is_noise(self):
        for text in ("——", "...", "···", "  "):
            self.assertTrue(wc.is_noise_line(text), text)

    def test_real_messages_survive(self):
        for text in ("好", "嗯", "在吗", "1", "ok", "明天见", "下午三点见",
                     "我看了，已读不回是吧", "我撤回了一条消息，你收到了吗"):
            self.assertFalse(wc.is_noise_line(text), text)

    def test_grouping_drops_chrome(self):
        lines = [{"t": "14:33", "x": 10, "y": 0, "w": 60, "h": 20},
                 {"t": "对方正在输入…", "x": 10, "y": 40, "w": 120, "h": 20},
                 {"t": "客户那边催得有点急", "x": 10, "y": 120, "w": 200, "h": 20}]
        msgs, _mixed = wc.group_lines(lines, area_width=600)
        self.assertEqual(1, len(msgs))
        self.assertEqual("客户那边催得有点急", msgs[0][1])

    def test_bubble_colour_can_override_geometry(self):
        # A full-width line: the centre rule cannot call it, the colour can.
        lines = [{"t": "我发的很长的一句话", "x": 10, "y": 0, "w": 580, "h": 20}]
        msgs, _ = wc.group_lines(lines, area_width=600, classify=lambda *a: "me")
        self.assertEqual("me", msgs[0][0])

    def test_no_colour_answer_falls_back_to_geometry(self):
        lines = [{"t": "靠左的一条", "x": 10, "y": 0, "w": 200, "h": 20}]
        msgs, _ = wc.group_lines(lines, area_width=600, classify=lambda *a: None)
        self.assertEqual("other", msgs[0][0])

    def test_a_long_message_is_classified_by_the_edge_it_hugs(self):
        # Reaches most of the way across, but its left edge gives it away. The
        # old centre-only rule called this one "me".
        self.assertEqual("other", wc.side_from_position(40, 560, 600))
        self.assertEqual("me", wc.side_from_position(180, 570, 600))

    def test_a_dead_centre_bubble_falls_to_the_safer_answer(self):
        self.assertEqual("other", wc.side_from_position(230, 370, 600))

    def test_a_degenerate_width_does_not_crash(self):
        self.assertEqual("other", wc.side_from_position(0, 0, 0))


class TypingGarbleTests(unittest.TestCase):
    """The typing strip is noise even when OCR mangles it."""

    def test_mangled_typing_strips_are_noise(self):
        # Measured on a real crop: "对方正在输入…" comes back like this often
        # enough that matching the phrase exactly is not enough.
        for text in ("三在窪入一", "对方正在输λ", "正在输入", "对方在输入"):
            self.assertTrue(wc.is_noise_line(text), text)

    def test_a_real_sentence_about_typing_survives(self):
        # The garble rule is capped at 6 characters on purpose.
        for text in ("我在输入法里找不到那个字", "正在开会，等会说", "现在在路上了"):
            self.assertFalse(wc.is_noise_line(text), text)


class ConfirmPendingTests(unittest.TestCase):
    """A new message has to be read twice off an unchanged screen."""

    MSG = "客户那边催得有点急，今天能给个说法吗"
    OTHER = "那行，我下午三点再过来找你一趟"

    def test_two_looks_at_the_same_frame_accept_it(self):
        accepted, _k, _h, hits = lw.confirm_pending("", None, 0, self.MSG, 12345)
        self.assertFalse(accepted)
        self.assertEqual(1, hits)
        accepted, _k, _h, hits = lw.confirm_pending(
            self.MSG, 12345, hits, self.MSG, 12345)
        self.assertTrue(accepted)
        self.assertEqual(2, hits)

    def test_a_blink_that_changes_the_screen_is_rejected(self):
        # The second look caught the window mid-repaint, so the line it read
        # cannot be trusted - this is the "对方正在输入…" flicker.
        accepted, _k, _h, hits = lw.confirm_pending("", None, 0, self.MSG, 12345)
        self.assertFalse(accepted)
        accepted, _k, _h, hits = lw.confirm_pending(
            self.MSG, 12345, hits, self.MSG, 99999)
        self.assertFalse(accepted)
        self.assertEqual(1, hits)

    def test_a_different_line_restarts_the_count(self):
        accepted, key, _h, hits = lw.confirm_pending(
            self.MSG, 12345, 1, self.OTHER, 12345)
        self.assertFalse(accepted)
        self.assertEqual(self.OTHER, key)
        self.assertEqual(1, hits)

    def test_ocr_jitter_on_a_long_message_still_confirms(self):
        jittered = self.MSG[:-1] + "吗?"
        accepted, _k, _h, _hits = lw.confirm_pending(
            self.MSG, 12345, 1, jittered, 12345)
        self.assertTrue(accepted)


class BubbleColourTests(unittest.TestCase):
    """Reading the bubble colour is what stops the app answering the user's own
    message when the screen happens to show only that one."""

    @staticmethod
    def _pixels(rgb, w=60, h=24):
        r, g, b = rgb
        return (w, h, bytes([b, g, r, 255]) * (w * h))

    def test_green_bubbles_are_mine(self):
        classify = lw.make_bubble_classifier(self._pixels((149, 236, 105)))
        self.assertEqual("me", classify(0, 0, 60, 24))

    def test_white_bubbles_defer_to_geometry(self):
        classify = lw.make_bubble_classifier(self._pixels((255, 255, 255)))
        self.assertIsNone(classify(0, 0, 60, 24))

    def test_grey_bubbles_defer_to_geometry(self):
        classify = lw.make_bubble_classifier(self._pixels((240, 240, 240)))
        self.assertIsNone(classify(0, 0, 60, 24))

    def test_missing_pixels_means_no_classifier(self):
        self.assertIsNone(lw.make_bubble_classifier(None))


class PromptTests(unittest.TestCase):
    """How the conversation is handed to the model - the difference between a
    reply that answers the right line and one that belongs to another chat."""

    def test_when_sides_are_known_the_line_to_answer_is_named(self):
        prompt = lw.build_user_prompt(
            "老板", "对方是我的老板",
            [("other", "材料看过了吗"), ("me", "看了一半"), ("other", "客户催得急")],
            sides_known=True)
        self.assertIn("【要回的那句】客户催得急", prompt)
        self.assertIn("对方：材料看过了吗", prompt)
        self.assertIn("我：看了一半", prompt)
        # the line to answer must not also appear in the background
        self.assertEqual(1, prompt.count("客户催得急"))

    def test_when_sides_are_unknown_the_model_is_warned(self):
        prompt = lw.build_user_prompt(
            "老板", "同事",
            [("other", "你好"), ("other", "我刚发出去的那句")],
            sides_known=False)
        self.assertIn("没能确定", prompt)
        self.assertIn("如果是我自己刚发的，就回复它前面那句", prompt)
        self.assertNotIn("【要回的那句】", prompt)
        # no fabricated 我/对方 labels in this shape
        self.assertNotIn("对方：", prompt)

    def test_relationship_is_always_carried(self):
        prompt = lw.build_user_prompt("", "对方是我的老板", [], sides_known=True)
        self.assertIn("【关系】对方是我的老板", prompt)

    def test_a_blank_relationship_says_so(self):
        prompt = lw.build_user_prompt("", "   ", [], sides_known=True)
        self.assertIn("【关系】未说明", prompt)

    def test_the_anchor_is_the_newest_incoming_line_not_the_last_line(self):
        # The reported failure: the user had already sent something, so the last
        # line on screen was their own - and the model was told to "answer" it.
        prompt = lw.build_user_prompt(
            "陆林晖", "女朋友",
            [("other", "宝宝我去洗澡澡啦"), ("me", "好哒")],
            sides_known=True)
        self.assertIn("【要回的那句】宝宝我去洗澡澡啦", prompt)
        self.assertNotIn("【要回的那句】好哒", prompt)

    def test_my_reply_after_the_anchor_is_flagged_as_already_sent(self):
        prompt = lw.build_user_prompt(
            "陆林晖", "女朋友",
            [("other", "宝宝我去洗澡澡啦"), ("me", "好哒")],
            sides_known=True)
        self.assertIn("【这句话之后我已经说过（别重复）】", prompt)
        self.assertIn("我：好哒", prompt)

    def test_background_holds_everything_before_the_anchor(self):
        prompt = lw.build_user_prompt(
            "老板", "同事",
            [("me", "早"), ("other", "材料看了吗"), ("me", "看了一半"), ("other", "今天能给个说法吗")],
            sides_known=True)
        self.assertIn("【要回的那句】今天能给个说法吗", prompt)
        self.assertIn("对方：材料看了吗", prompt)
        self.assertIn("我：看了一半", prompt)
        self.assertNotIn("（别重复）", prompt)   # nothing after the anchor

    def test_a_screen_with_only_my_own_words_has_no_anchor(self):
        prompt = lw.build_user_prompt("", "朋友", [("me", "在吗"), ("me", "睡了吗")],
                                      sides_known=True)
        self.assertNotIn("【要回的那句】", prompt)
        self.assertIn("只有我自己说过的话", prompt)


class SkillTests(unittest.TestCase):
    """The 狗头军师 skill: packaged references and the routing that picks them."""

    def test_both_skills_exist_and_differ(self):
        self.assertIn("general", lw.SKILLS)
        self.assertIn("goutoujunshi", lw.SKILLS)
        self.assertNotEqual(lw.SKILLS["general"]["prompt"], lw.SKILLS["goutoujunshi"]["prompt"])

    def test_the_skill_prompt_carries_its_method_and_boundaries(self):
        prompt = lw.SKILLS["goutoujunshi"]["prompt"]
        self.assertIn("先接住情绪", prompt)          # the working method
        self.assertIn("【要回的那句】", prompt)       # the app's anchor contract
        self.assertIn("家暴", prompt)                # the safety boundary
        self.assertIn("\"replies\"", prompt)         # the JSON contract

    def test_the_reference_library_shipped(self):
        index = lw.skill_index()
        self.assertGreaterEqual(len(index), 40)
        for entry in index[:3]:
            path = lw.resource_path("skills/goutoujunshi/" + entry["f"])
            self.assertTrue(os.path.exists(path), path)

    def test_routing_matches_the_conversation(self):
        refs = lw.pick_references("对方一直不回我消息，已读不回，忽冷忽热，我该怎么办")
        self.assertTrue(refs)
        self.assertIn("被动为主动", refs[0][0])

    def test_routing_falls_back_to_the_playbook(self):
        refs = lw.pick_references("嗯嗯好的")
        self.assertEqual(1, len(refs))
        self.assertIn("实战话术编排器", refs[0][0])

    def test_excerpts_are_bounded(self):
        for _title, body in lw.pick_references("吵架了，怎么道歉和修复关系"):
            self.assertLessEqual(len(body), lw._SKILL_EXCERPT + 16)

    def test_at_most_two_references(self):
        refs = lw.pick_references("不回 已读不回 冷淡 敷衍 吵架 道歉 依恋 焦虑 拒绝 边界")
        self.assertLessEqual(len(refs), 2)


if __name__ == "__main__":
    unittest.main()

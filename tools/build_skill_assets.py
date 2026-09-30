# -*- coding: utf-8 -*-
"""Bundle the 狗头军师 skill into both builds.

Runs once at build time and copies the skill's markdown into
`android/app/src/main/assets/skills/goutoujunshi/` and
`windows/skills/goutoujunshi/`, renaming the (Chinese) file names to ASCII
`doc-NN.md` so nothing downstream has to deal with non-ASCII paths.

`index.json` is the routing data: for each document, the original title and a
keyword string. At request time the router scores every document against the
conversation and sends the best one or two - the skill itself says to load 1-3
relevant references rather than the whole knowledge base.

Usage:  python tools/build_skill_assets.py [path-to-goutoujunshi]
"""

import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEFAULT_SRC = r"F:\APP\JAV\goutoujunshi-main\goutoujunshi-main"

TARGETS = [
    os.path.join(ROOT, "android", "app", "src", "main", "assets", "skills", "goutoujunshi"),
    os.path.join(ROOT, "windows", "skills", "goutoujunshi"),
]

# Keywords per document. Written by hand for the ones a chat-reply assistant
# actually needs; everything else is matched on its title.
KEYWORDS = {
    "实战话术编排器：从一句回复到后续分支":
        "怎么回 回复 话术 开场 邀约 邀 约 下一句 接话 说什么 发什么",
    "巧妙接话技巧：让沟通更流畅的实用指南":
        "接话 冷场 不知道说什么 怎么接 尬聊",
    "聊天化被动为主动：引导互动的实用指南":
        "不回 已读不回 冷淡 被动 主动 忽冷忽热 慢回 敷衍",
    "场景感、松弛感与社交校准：从接话到关系推进":
        "松弛 自然 紧张 尬 推进 氛围",
    "废话文学回复指南：轻松应对各类场景":
        "废话 敷衍 不想回 应付 客套",
    "高情商拒绝他人：体面护边界的实用指南":
        "拒绝 边界 不想 不方便 推掉 借钱 借 帮助",
    "化解尴尬：轻松救场的实用指南":
        "尴尬 冷场 说错话 救场 难堪",
    "为他人提供情绪价值：温暖且有效的回应指南":
        "情绪 安慰 难受 生气 不开心 委屈 共情 安慰一下",
    "万能夸人的话术技巧：真诚认可的实用指南":
        "夸 赞美 表扬 夸奖",
    "关系投入失衡：互惠判断、降级投入与退出决策":
        "投入 失衡 付出 单向 冷落 退出 放弃 值不值",
    "主动表达、第一次见面与自然接触":
        "见面 约 见面 表白 主动 表达 第一次",
    "托人办事的高效话术指南":
        "帮忙 办事 托人 求人 找人帮忙",
    "万能吵架技巧：理性冲突处理指南":
        "吵架 吵 争执 冲突 骂",
    "提高气场：从内到外的力量感塑造指南":
        "气场 自信 底气",
    "提升表达逻辑性：从混乱到清晰的实用指南":
        "表达 逻辑 说清楚 沟通不清",
    "有效拓展人脉：从建立到维护的实用指南":
        "人脉 拓人 认识人 社交",
    "获得领导青睐：从价值匹配到信任建立的实用指南":
        "领导 老板 上司 汇报 职场",
    "被孤立如何破局：从自我调适到建立连接的实用指南":
        "孤立 排挤 被孤立 合不来",
    "07-沟通冲突与修复":
        "道歉 吵架 误会 修复 冷战 生气 冲突 和好",
    "03-依恋理论与情绪调节":
        "焦虑 依恋 没安全感 内耗 患得患失 情绪",
    "04-MBTI人格与匹配":
        "mbti intj enfp infp 人格 性格",
    "05-PUA操控与伦理替代":
        "pua 冷读 推拉 服从测试 煤气灯 打压 操控",
    "06-吸引约会与关系启动":
        "吸引 追求 心动 暧昧 好感 追",
    "08-同意边界性与亲密":
        "亲密 同意 边界 身体 越界",
    "09-在线约会与数字关系":
        "网聊 网友 线上 朋友圈 社交软件 截图 隐私 诈骗",
    "11-婚姻家庭与生命周期":
        "结婚 婚姻 家里 父母 催婚 生育",
    "12-金钱家务育儿与双方家庭":
        "钱 家务 育儿 彩礼 房 开销",
    "15-分手背叛与关系修复":
        "分手 复合 背叛 出轨 信任 离开",
    "16-多元关系与反刻板印象":
        "多元 刻板 非传统",
    "17-中国法律安全与危机转介":
        "家暴 威胁 跟踪 强迫 胁迫 勒索 自杀 危险 报警 违法",
    "20-经典社交体系的机制、证据与风险边界":
        "自然流 blueprint mystery 冷读 体系",
    "01-证据分级与内容边界":
        "证据 来源 靠谱吗 依据",
    "长期记忆与关系档案":
        "记忆 档案 记住 长期",
    "ChatLab聊天记录分析适配":
        "chatlab 记录分析 聊天导出",
}


def build(src):
    skill_md = os.path.join(src, "SKILL.md")
    if not os.path.exists(skill_md):
        raise SystemExit("not a goutoujunshi skill: %s" % src)
    refs = []
    for base in ("knowledge", "practical"):
        folder = os.path.join(src, "references", base)
        if not os.path.isdir(folder):
            continue
        for name in sorted(os.listdir(folder)):
            if name.endswith(".md"):
                refs.append((base, name))

    entries = []
    for i, (base, name) in enumerate(refs, start=1):
        title = name[:-3]
        slug = "references/doc-%02d.md" % i
        entries.append({
            "t": title,
            "f": slug,
            "k": KEYWORDS.get(title, "") or " ".join(
                p for p in title.replace("：", " ").replace("-", " ").split() if len(p) > 1),
            "src": os.path.join(src, "references", base, name),
        })

    for target in TARGETS:
        shutil.rmtree(target, ignore_errors=True)
        os.makedirs(os.path.join(target, "references"), exist_ok=True)
        shutil.copyfile(skill_md, os.path.join(target, "SKILL.md"))
        for e in entries:
            shutil.copyfile(e["src"], os.path.join(target, e["f"]))
        index = [{"t": e["t"], "f": e["f"], "k": e["k"]} for e in entries]
        with open(os.path.join(target, "index.json"), "w", encoding="utf-8") as f:
            json.dump(index, f, ensure_ascii=False, indent=1)
        shutil.copyfile(os.path.join(src, "LICENSE"),
                        os.path.join(target, "LICENSE"))
        print("bundled %d docs -> %s" % (len(entries), target))


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC)

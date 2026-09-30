# 验收记录（v1.25）

这里的东西都是打包完成后在真机 / 模拟器上实际跑出来的，不是设计稿。

> **关于截图文件**：v1.14 之前的 PNG 在一次误删工作目录的事故里丢了，
> 文字记录（命令、日志、数字）都还在，下面表格里那些旧文件名点不开。
>
> **按作者要求，画面上带「测试聊天窗口」（那台假微信界面的测试 App）的截图已经全部
> 从仓库里移除** —— 下面正文里还会提到它们（当时的验收记录），但文件名已经点不开了，
> 需要时可以按 commit 从历史里取回。
>
> 目前目录里实际存在的截图是这些（都只含本软件自己的界面）：
> `windows-01/02-v1.24-picker-*.png`、`windows-03-v1.24-installed-app.png`、
> `windows-04-v1.25-installed-app.png`、`windows-12-v1.12-rebuilt-installer.png`、
> `android-29-v1.15-release-home.png`、`android-32-v1.16-release-home.png`、
> `android-35/36/44/46-v1.1x-android16-release-home.png`、
> `android-41-v1.19-home-picker.png`、`android-49/50-v1.24-name-*.png`、
> `android-51/52/53-v1.24-name-*.png`、`android-57/60-v1.25-*.png`。
> 另外还有两张是**在用户真机（小米 24122RKC7C / Android 16 / HyperOS V816）**上拍的：
> `android-47-v1.22-phone-paste.png`、`android-48-v1.22-paste-result.png`。

## 一、自动化测试

```
$ powershell -ExecutionPolicy Bypass -File test_all.ps1

=== 1/2  Windows logic tests ===
Ran 86 tests ... OK

=== 2/2  Android unit tests ===
BUILD SUCCESSFUL

All tests passed.
```

| 套件 | 数量 | 覆盖 |
|---|---|---|
| `tests/test_windows_logic.py` | 86 | 三协议地址拼接、模型输出容错解析、OCR 分行与左右分边、「是不是新消息」判定、**装饰行过滤（含 `昨天 22:18`、`1 5 ： 4 6` 这类时间行）**、**单条气泡也算"分得清谁说的"**、**气泡颜色分左右**、**提示词结构**、**二次确认（同屏两次才认新消息）**、**推理模型的截断与"只有思考没有答案"**、**"关闭思考"参数与回退**、微信版式几何与输入框落点、**列表/聊天区分界线检测（5 个用例）**、**对话人名单（垃圾名不记 + 最多 6 个 + 清空后不留残余）** |
| `.../CaptureGeometryTest` | 7 | **截图的屏幕坐标换算**（v1.16）：整屏截图必须 1:1、不可能落在屏幕外的窗口矩形一律不信、真实内嵌窗口保留偏移、缩放窗口按比例还原 |
| `.../EndpointsTest` | 7 | 地址拼接（安卓侧） |
| `.../SuggestionParserTest` | 12 | JSON / 代码块包裹 / 纯文本兜底 / danger 越界 / **正文里带花括号** / **思考笔记不当回复** / **军师的 stance** / **只留 3 条候选** |
| `.../ConversationSessionTest` | 9 | 换会话后旧结果与延迟写入必须失效 |
| `.../MessageKeysTest` | 27 | 「是不是新消息」判定 + 装饰行过滤（与电脑端同规则，含 OCR 乱码版"正在输入"、**带时钟的日期行**）+ **只有标题为「微信」时才判为微信主界面** + **姓名判定（两个字必须完全一致，`老刘/老李` 不能算同一人）** + **会话名判定 `plausibleName`（`供布)`、`姓你生活?` 不记，`张三`/`Alice` 照记）** |
| `.../OcrScaleTest` | 4 | 截屏裁剪与缩放（内嵌窗口的偏移/缩放不能算错） |
| `.../PromptBuilderTest` | 9 | 提示词结构：要回的那句 / 我 / 对方 的标注 |
| `.../WeChatSideTest` | 5 | 微信气泡左右判定 |
| `.../BubbleColourTest` | 14 | **按气泡颜色分左右**、**这是聊天界面还是会话列表** |
| `.../WeChatBandTest` | 12 | 消息区裁剪：状态栏 / 导航栏 / **铺满屏幕与内嵌窗口都不能算错** |
| `.../SkillsTest` | 9 | 技能路由 + **只有军师才要 stance** |
| `.../OverlayLivenessTest` | 7 | **什么时候该把浮窗放回来**（v1.25）：在屏幕上就不动它、被系统摘掉就补回来、用户关掉助手或主动隐藏时不补、权限没了不补、每秒最多试一次、用户点「重新显示」时两把刹车都让路 |

合计 **208 个测试，0 失败**（Windows 86 + 安卓 122）。

## 二、v1.16 ~ v1.20：手机端识别与悬浮窗（Android 14 + Android 16 实测）

反馈：手机端开倒车——微信认不出来、不自动生成、别的软件也识别不准、还慢。
在 Android 14 模拟器（1080x2400, density 2.625）+ 同版式测试 App 上跑起来，
`adb logcat -s LITECHAT` 抓到的三次对照如下。

### 1. 截图偏移（本次倒车的直接原因）

```
修前： ocr[testchat] shot=1080x2400 scale=1.0,1.1183597 origin=666,0 region=Rect(0, 254 - 1080, 2133)
       ocr[testchat] read=me:昨天那份材料你看 | me:客户那边催得有点 | me:行，那我 发你
       ocr[testchat] decide(v3) ... latest=me
       ocr[testchat] skipped: no new incoming message      ← 永远不再生成

修后： ocr[testchat] shot=1080x2400 scale=1.0,1.0 origin=0,0 region=Rect(0, 254 - 1080, 2133)
       ocr[testchat] read=other:昨天那份材料你看过了吗? | other:客户那边催得有点急,今天
            | me:我早上看了一半，下午给你 | me:行，那我三点再来问你，我
       ocr[testchat] decide(v3) ... latest=me auto=true
       answered in 4535 ms skill=general noThinking=true
```

`origin=666,0` 来自"拍之前读的窗口坐标"撞上切窗口的滑入动画；`scale=1.1183597` 来自
拿整屏画面（2400px）除以显示可用高度（2146px）。两个都让消息区被裁到错误的位置。
修法：整屏截图回到第一顺位并强制 1:1；窗口截图只在整屏被拒时才用，且必须通过
"矩形落在屏幕内且确实更小"的检查。规则抽成纯函数 `resolveCaptureGeometry`，
`CaptureGeometryTest` 7 个用例把它钉住（含 x=666 那组真实数据）。

### 2. 浮窗文字被拍进截图（"牛头不对马嘴"的来源之一）

三条"藏浮窗"的办法全试过，`adb logcat` 证据：

```
ocr[testchat] taking a screenshot
overlay hidden=true attached=false parent=null     ← 窗口真的从 WindowManager 摘掉了
overlay hidden=false attached=false parent=ViewRootImpl@...
ocr[testchat] shot=1080x2400 ...
ocr[testchat] read=other:轻聊 | other:轻聊分祈 更新中.. 0 | other:技能 调用军师 取消军师
     | other:对话人 自动跟随 | me:分祈当前对话 | other:客戶那边催得有点急,今天 | ...
```

窗口摘除 293ms 之后，截图里浮窗仍然在（系统把最后一帧又给了一次）。改成
**不藏浮窗、按浮窗真实屏幕位置剔除 OCR 行**：

```
ocr[testchat] cover=Rect(-15, 486 - 194, 695) trail=4 boxes=[60,576][118,606]! ...
ocr[testchat] read=other:昨天那份材料你看过了吗? | other:客戶那边催得有点急,今天 | ...
```

（`!` = 落在浮窗范围内被丢掉的那行。）顺带把"每秒闪一下"也去掉了。

### 3. 微信主界面被当成会话

标题栏读到"微信 / 通讯录 / 发现 / 我 / 朋友圈"（含"微信(3)"这种带角标的写法）时不分析，
面板提示"这一屏是微信主界面，打开一个聊天再读"。只做完全匹配，
`MessageKeysTest` 里 3 个用例覆盖（"微信团队""朋友圈代购"必须照常当会话）。

### 4. 提速

标题栏 + 消息区由两次 OCR 合并为一次（两段上下紧贴，合成一个矩形读一遍再按 y 拆开），
每次回复省一次完整往返。识别这一段稳定在 **0.6-1.3 秒**（截图 → 出结果）；
之后是模型自己的耗时。

### 5. 新消息实测（同一个模拟器，真实 LongCat-2.0）

点一下测试 App 的输入栏，让它发出一条新消息，日志：

```
ocr[testchat] read=me:行，那我三点再来问你，我 | other:材料我明天上午给你
ocr[testchat] decide(v3) screenRead=true autoAnalyze=true latest=other auto=true
answered in 6409 ms skill=general noThinking=true
```

面板截图 `android-31-v1.16-longcat-analysis.png`：意图「老板要发材料」、
建议「等明天上午收材料」、候选「好的，那我明天上午等你发过来」「收到，我明天上午等你」。
正式签名包启动截图 `android-32-v1.16-release-home.png`（`versionCode 17 / versionName 1.16`）。

### 6. Android 16（API 36）复测——v1.17

因为用户的手机是安卓 16 以上，改到 Android 16 模拟器（`xingmu-android16`，
1080×2400、420dpi、API 36）从头再跑一遍，又抓到一处真问题。

**面板挡住聊天内容，把对方的消息一起读丢了。**

Android 16 上面板更高（1344px），遮罩方案直接暴露了它的代价：

```
shot=1080x2400 scale=1.0,1.0 origin=0,0 ...
read 21 line(s), 19 under the panel
msgs=2 manual=false
read=me:我早上看了一半,下午给你 | me:行,那我三点再来问你,我      ← 对方的气泡全被丢掉了
```

改成**只截聊天窗口本身**（`takeScreenshotOfWindow`，API 34+）——浮窗属于另一个窗口，
不可能出现在画面里——并在截图前后各读一次窗口边界，两次不一致就作废、退回整屏截图：

```
shot=1080x2400 scale=1.0,1.0 origin=0,0 windowScoped=true
read=other:昨天那份材料你看过了吗? | other:客户那边催得有点急,今天
     | me:我早上看了一半,下午给你 | me:行,那我三点再来问你,我      ← 四条全读到，左右都对
answered in 2758 ms skill=general noThinking=true
```

新消息实测（点测试 App 的输入栏让它发一条）：

```
read=... | other:材料我明天上午给你
decide(v3) screenRead=true autoAnalyze=true latest=other auto=true
answered in 2758 ms skill=general noThinking=true
```

| 截图 | 内容 |
|---|---|
| `android-33-v1.17-android16-analysis.png` | 安卓 16 上面板正常出候选（意图「催进度要结果」、候选「我马上把结论整理出来，三点前发你」等） |
| `android-34-v1.17-android16-newmsg.png` | 安卓 16 上新收到的「材料我明天上午给你」→ 意图「老板承诺明天上午给材料」+ 2 条候选 |
| `android-35-v1.17-android16-release-home.png` | 签名正式包在安卓 16 上安装启动，`versionCode 18 / versionName 1.17` |

另外修了测试 App 自己的一处：安卓 15+ 强制边到边，它的输入栏落在导航栏下面
（`[0,2259][1080,2400]`，导航栏是 `[0,2274]` 起），adb 点不到；补上 `systemBars`
内边距后输入栏回到 `[0,2133][1080,2274]`，与读取器算出来的消息区下边界（2132）刚好吻合。
这是测试工具的问题，与正式包无关。

**仍未验到**：真机上的微信本体（模拟器装不了微信）。

### 7. Android 16 复测第二轮——v1.18（换人、悬浮窗、速度）

用户的三条反馈：「换到下一个联系人，回复还是上一个对话人的」「手动选择对话人时悬浮窗
闪退（消失）」「生成回复很慢」。三条都在同一台 Android 16 模拟器上复现并修掉。

**① 换人不认账 —— 姓名比对太松（真 bug，可单测）**

旧规则：两个名字相似度 ≥ 50% 就算同一个人。两个字的中文名只要共享一个字就是 50%，
于是 `老刘/老李`、`老王/老张`、`张三/张四` 全被当成同一人，切换**根本没被发现**，
面板继续留着上一个人的候选。新的规则：两字名必须完全一致；三字以上允许一个字误差，
但首字（姓氏）必须一致。`MessageKeysTest` 新增 4 个用例（含上面这些反例，
以及 `李沅汐/李沅沙`、`陆林晖/陆林辉` 这类真 OCR 误差必须仍然算同一人）。

修完的现场证据（切换的瞬间，面板先清空并写明正在读谁）：

```
05:08:52.602 conversation changed in the same window (2 chars -> 3 chars)
→ 面板显示：已切到「李沅汐」 / 正在读这段对话，读完就出候选。
05:08:55.834 answered in 2826 ms skill=general noThinking=true
```

锁定别人时同样把上一个人的候选清掉：
`pinned to another conversation: skipped (read=老刘)` + 面板写明
「当前是「老刘」，你锁定的是「李沅汐」，所以不分析。想跟随当前聊天，点上面的「对话人」
改成自动跟随。」——截图 `android-38-v1.18-pinned-elsewhere.png`。

**② 点「对话人」悬浮窗消失 —— 三个原因叠加**

* **名单被后台刷新冲掉**：读屏每秒一次，每次读完都重画面板；名单刚打开约 1 秒就被覆盖。
  现在名单打开期间是模态的：`showIdle` / `showLoading` / `showSuggestion` / `showError`
  全部让路，后台结果存下来，点「返回」再画。截图 `android-39-v1.18-picker-holds.png`
  是打开 8 秒后仍然完整的名单；`android-40-v1.18-picker-back.png` 是返回后恢复的候选。
* **自己的窗口被当成"离开聊天"**：`onAccessibilityEvent` 里
  `fg == packageName` 会走到 `overlay?.hide()`（整个浮窗移除）。部分 ROM 一碰浮窗就把
  当前窗口报成我们自己的包名，于是"点一下就没了"。现在这种情况直接 return。
  日志：`event from our own window; panel kept`。
* **在触摸派发里重建/移除视图**：选项与"隐藏助手"的回调改成 `post { }`，
  名单构建套了 try/catch（出错只提示，不带走服务）。
* **顺带发现的真 bug**：`toggle()` 先翻 `expanded` 再检查窗口。第一次失败
  （权限刚授予、窗口还没建）就把标记卡在"已展开"，之后 `if (!expanded) toggle()`
  全部跳过 —— **面板再也打不开**。实测日志：
  `showLoading pickerOpen=false expanded=false` → `overlay: canDrawOverlays=false`
  → 之后 `expanded=true` 但没有面板。现在先 `ensureRoot()` 再翻标记。

**③ 慢 —— 被节流丢掉的请求改成到点重试**

截屏有 1 秒的最小间隔，落在间隔里的请求原来是**直接丢掉**，要等下一个屏幕事件再试
（最多再等约 1 秒）。现在用 `ScreenCapture.nextAttemptInMs()` 算好剩余时间，
到点自动重试。识别段（截图 → 出结果）稳定在 0.6-1.3 秒，之后是模型自身耗时。

本轮回归：Windows 77 + 安卓 111 单测全绿；8 套电脑端设备脚本全 PASS（延迟中位 0.52 秒）；
安装启动截图 `android-36-v1.18-android16-release-home.png`
（`versionCode 19 / versionName 1.18`，安卓 16）。

### 8. Android 16 复测第三轮——v1.19（跟不上 / 按钮看不见）

反馈：「悬浮窗直接失灵」「手机端还是没有对话人的选择按钮」「换人还是答上一个人」。
先把"浮窗绘制位置和触摸位置是否一致"量了一遍：面板开启时
`dumpsys input` 的触摸区是 `[16,464][846,1808]`，而截图里卡片白色区域是
`x 20..842 / y 611..1808`（面板在窗口内下移 147px = 56dp，和布局一致）——**两者是对得上的**，
所以点不动不是坐标偏移，而是下面三件事：

**① 读屏完全依赖聊天软件发事件 —— 它不发，程序就一直不重读**

这是"换人跟不上"的真正兜底缺口。现在除了事件驱动，还有一条**固定 2 秒的自轮询**：

```
06:26:37.753 ocr[testchat] taking a screenshot
06:26:39.758 ocr[testchat] taking a screenshot
06:26:41.763 ocr[testchat] taking a screenshot
06:26:43.771 ocr[testchat] taking a screenshot
```

（全程没有任何操作，界面也没变化。）换人实测：点击标题切换联系人后 3 秒内

```
06:27:32.576 conversation changed in the same window (3 chars -> 2 chars)
→ 面板：「已切到「老刘」/ 正在读这段对话，读完就出候选。」（截图 android-43-v1.19-switch-3s.png）
```

**② 对话人按钮：浮窗里放大 + App 主界面新增一份**

| 截图 | 内容 |
|---|---|
| `android-42-v1.19-panel-button.png` | 浮窗面板里改成整行大字按钮「对话人：自动跟随 ▾」（原来是小字 + 24dp 小胶囊） |
| `android-41-v1.19-home-picker.png` | **App 主界面新增「对话人 / 正在回答谁」选择区**：普通窗口，触摸永远有效，浮窗被系统限制也能选人 |

**③ 速度**：中位 0.50 秒（电脑端设备脚本同口径）。

**本轮回归**：Windows 77 + 安卓 111 单测全绿；8 套设备脚本全 PASS；
`versionCode 20 / versionName 1.19` 在安卓 16 上安装启动正常
（`android-44-v1.19-android16-release-home.png`）。

### 9. 安卓 16 复测第四轮——v1.20（只有微信读不到）

反馈「微信无法读取屏幕内容，其它软件都正常」。这个组合指向微信特有的那条路：
其它软件有文字节点，微信的正文对无障碍隐藏、**只能截屏读**，而截屏这条路被一个开关挡住。

**根因**：`ChatCaptureService.maybeCapture` 里

```
if (prefs.ocrFallback) { …截屏识别… }
```

`ocrFallback` 对应设置项「读不到文字时用截屏识别」。它一旦关掉（或以前关过），微信就彻底
读不到，而所有有文字节点的软件完全不受影响——与反馈完全一致。

**修法**：按"这条链路本身是不是只能截屏"判断，而不是按开关：

```
if (prefs.ocrFallback || snapshot.screenRead) { …截屏识别… }
```

`screenRead` 由适配器自己声明（微信适配器为 true、通用识别路线为 true；QQ/飞书/X 有文字
节点时为 false）。微信的开关是设置里的「读取微信」，两者不再互相踩。

**实测（关键：故意把 ocrFallback 关掉）**

```
$ adb shell run-as com.litechat.app cat shared_prefs/litechat_assistant.xml | grep ocr_fallback
    <boolean name="ocr_fallback" value="false" />

06:59:13.700 ocr[testchat] read=other:昨天那份材料你看过了吗? | other:客户那边催得有点急,今天
     | me:我早上看了一半，下午给你 | me:行，那我三点再来问你， | other:材料我明天上午给你
     | other:那下午的会改到四点了，你 | other:那个客户又打电话过来了
06:59:13.700 ocr[testchat] decide(v3) screenRead=true autoAnalyze=true latest=other auto=true
06:59:18.240 answered in 4135 ms skill=general noThinking=true
```

面板截图 `android-45-v1.20-wechat-ocr-off.png`：意图「客户催进度」、建议「先稳住客户再同步
老板」、两条候选。（安卓模拟器装不了微信本体，用的是同版式的测试 App，它走的是与微信
完全相同的适配器与截屏链路。）

**另外两处只可能误伤微信的地方也收紧了**：

1. 微信的截图固定用**整屏画面**（`ScreenCapture.capture(preferWindow = pkg != "com.tencent.mm")`）。
   微信聊天页由它自己绘制，只截窗口可能拿不到那部分内容或拿到空白——"只有微信读不到"的
   另一种可能。整屏画面的浮窗污染由已有的遮罩逻辑处理。
2. 「微信主界面不分析」只保留标题 **「微信」**（原来是 微信/通讯录/发现/我/朋友圈）。
   每多一个词就多一次把真会话挡掉的机会；`MessageKeysTest` 里对应的用例已更新
   （新增 1 个：这四个标题现在按会话读取）。
3. 新增**空白画面识别**（`ScreenCapture.isBlank`）：截到纯色时面板直接说
   「截到的是空白画面（内容被系统或该应用挡住了截图）」，而不是含糊的「没认出文字」；
   微信连续两屏读不到字时给一句可操作提示。

**本轮回归**：Windows 77 + 安卓 112 单测全绿；8 套设备脚本全 PASS（中位 0.55 秒）；
`versionCode 21 / versionName 1.20` 在安卓 16 上安装启动正常
（`android-46-v1.20-android16-release-home.png`）。

### 10. 用户真机复测——v1.21 / v1.22（微信窗口带 SECURE）

用户接上手机（小米 24122RKC7C、Android 16 / API 36、HyperOS V816、1440×3200 / 600dpi、
微信 8.0.78）后，在真机上取证。

**现象**：只有微信读不到，其它软件正常。真机日志：

```
ocr[com.tencent.mm] taking a screenshot (region=true rects=0)
ocr[com.tencent.mm] msgs=0 manual=false        ← 截图成功，但区域里一个字都没有
```

**根因**：微信的窗口带系统 `SECURE`（防截屏）标志，系统给它的截图必然全黑。

```
$ dumpsys window windows | (微信窗口)
Window #16 Window{... com.tencent.mm/com.tencent.mm.ui.LauncherUI}:
  fl=LAYOUT_IN_SCREEN SECURE LAYOUT_INSET_DECOR SPLIT_TOUCH HARDWARE_ACCELERATED ...

$ screencap -p   → 全黑（1440×3200 图里 0 个非黑采样点）
$ uiautomator dump（微信）→ text nodes: 0（无障碍树也是空的）

# 排除本程序/GKD 的嫌疑：把所有无障碍服务清空后重开微信，标志仍在
fl=LAYOUT_IN_SCREEN SECURE ...

# 排除改版微信/root：腾讯官方签名、bootloader 锁定、未 root、无 hook 框架
Signer #1 certificate DN: CN=Tencent, OU=Tencent Guangzhou R&D Center, ...
ro.boot.verifiedbootstate=green  ro.boot.flash.locked=1  ro.debuggable=0
```

（WeChat 8.0.78 的 APK 里也搜不到任何"防截屏"设置字符串，所以不是微信自带的开关。
小米的「应用锁」是已知会给被锁应用加这个标志的机制。）

**v1.21 的改动**：`ScreenCapture.isBlank` / `ChatCaptureService.isFlat` —— 截到纯色画面时
明确报出来（`ocr: screenshot failed code=-5`，面板显示
「截到的是空白画面（内容被系统或该应用挡住了截图）」），真机上已验证会打这条日志。

**v1.22 的改动**：主界面新增「微信读不到时 → 粘一段聊天记录也能出回复」。
真机实测（`android-48-v1.22-paste-result.png`）：

```
输入  : Boss: please send the report by 3pm
输出  : answered in 5436 ms skill=goutoujunshi noThinking=true
        军师判断「工作场景，对方在催报告」/ 对方意图「要你按时交报告」
        建议「先确认能按时交，不行就提前说。」
        1. 收到，三点前发你。(45%)  2. 正在收尾，三点准时到。(30%)
        3. 还差一点，三点前一定发你。(30%)   每条都有「复制」
```

用户侧的恢复办法（已在报告里写明）：用手机截图键对微信截图，若也是黑的，说明防截屏
开着；到「设置 → 应用设置 → 应用锁」（或隐私保护里相关项）关掉即可，自动读取会立刻恢复。

**说明**：真机上还发现用户同时启用了另一个同类助手（`com.jev.probe`，无障碍里显示
"Jev助手"）与自动化工具 `li.songe.gkd`。测试期间为定位问题曾临时改动
`enabled_accessibility_services`，**已按原样恢复**（四个服务全部还原）。

### 11. 电脑端复测——v1.23（识别窗口读到了会话列表）

用户发来电脑端截图：面板「对方最近说」显示的是 `1 子！好久不.."`、`0 湖南省 月神中秋 | 祝大..`、
`□ 小鲸智 ℃ T` —— 那是微信**左侧会话列表**里的联系人名与消息预览，不是右边聊天内容。

**根因 1：消息区左边界写死。** `Layout("wechat4", …, left=222, …)` 是一个固定常量，
而微信的会话列表宽度可以拖。列表比常量宽时，裁剪从列表中间开始，整列联系人进了提示词。

修法：`winchat.detect_content_left()` —— 在消息带里扫描每一列，找出"仍然是列表面板色"的
最后一列（列表是一整块纯色面板，聊天区是壁纸），作为消息区左边界；找不到就回落到常量。

```
真实微信 4.x 窗口(client 585x686)：常量 222 → 检测 228
假聊天窗口(列表 380px)：        常量 280 → 检测 387
假聊天窗口(列表 280px)：        常量 280 → 检测 287
NVIDIA 覆盖层 / ChatGPT / 资源管理器等无关窗口：检测 = 常量（无假阳性）
```

端到端对照（同一台机器、同一个假窗口、列表 380px，`tools/fake_chat_window.py --list-width 380`）：

```
# LITECHAT_NO_LEFT_DETECT=1（模拟修复前）
【要回的那句】，别老蘸菩手机 行，那我三点再来问你
【上文】… 对方：这边 # 老是：斤不 客户那边催得有点急，今天个说法吗 …

# 默认（修复后）
【要回的那句】行，那我三点再来问你
【上文】对方：昨天那份材料你看过了吗？ / 对方：客户那边催得有点急，今天个说法吗 /
        我：我早上看了一半，下牛给你答复
```

**根因 2：输入框那一小条被读成"最新消息"。** 在用户的真机（PC）微信窗口上：

```
【要回的那句】0                  ← 输入框里的光标/草稿被 OCR 成 0
【上文】… 对方：1 5 ： 46 …       ← 带空格的时间戳漏过了过滤
```

实测该窗口输入框顶边在客户区底部往上 131px，而布局里写的是 87px。
`wechat4` 的 `bottom` 改为 130（`tests/test_windows_logic.py` 里对应的"点输入框"断言同步更新）。
带空格时间戳的过滤在 `winchat._NOISE_LINE` 与 `MessageKeys.NOISE_LINE` 两端同步放宽。

**保留原样**：标题栏不做边界检测——把检测用于标题时，会话名会贴到图片边缘，
Windows 识别器会把整行丢掉（实测 left=287 丢、288 读，差 1 像素）。
标题本来就读得对，因此 `capture_regions(..., refine_left=[True, False])` 只对消息区生效。

**本轮回归**：Windows 83 + 安卓 113 单测全绿；8 套设备脚本全 PASS（中位 0.51 秒）；
v1.23 安装包在 Windows 上安装完成（注册表 DisplayVersion=1.23）。

### 12. 手机端「对话人」名单太长——v1.24（模拟器 + 用户真机截图）

用户截图里，手机端「正在回答谁」一栏堆了十几个条目，全是识别跑偏时被当成名字记下来的
消息预览片段（`供布)`、`姓你生活?`、`进生活7`、`仟始1五伦林役还 2方大同s`）。
名单原本没有上限、也没有清理入口。

改动（两端同规则）：`MessageKeys.plausibleName` / `plausible_conversation_name` 过滤
（空 / >12 字 / 含空格 / 含数字 / 含句读标点 / 中英混排一律不记），上限 12 → **6**
（读写两端都截断），选人页与主界面新增**「清空这个名单」**。

**手机端复现与验证**（模拟器 xingmu-android16 / API 36，debug 包，与发布包同一份代码）：

```bash
# 往配置里灌 12 个垃圾名字（真实代码路径不记这些，这里直接写 prefs 复现"旧名单"状态）
adb shell run-as com.litechat.app cp /data/local/tmp/litechat_assistant.xml \
    shared_prefs/litechat_assistant.xml
adb shell am force-stop com.litechat.app
adb shell am start -n com.litechat.app/.MainActivity
```

结果：界面提示「只保留最近读到的 **6** 个（最多 6 个）」，列表只列 6 条 +
「清空这个名单」（`android-51` / `android-52`）。点「清空这个名单」后，列表只剩
「自动跟随」+ 提示「还没读到过会话名：打开一个聊天，程序读一次就会出现在这里。」
（`android-53`），`run-as` 读回 `<string name="known_conversations"></string>` —— 空的。

**电脑端**（同一份 `litechat_win.py`，真机上打开「选择对话人」对话框）：

```
dialog buttons: ['✓ 自动跟随', '张三', '李沅汐', '材料评审群', '清空这个名单', '关闭']
clear button present: True
after clear, known = []
after clear, buttons = ['✓ 自动跟随', '张三', '关闭']
clear button gone: True
```

截图 `windows-01-v1.24-picker-clear.png`（含「清空这个名单」与「只保留最近读到的 3 个
（最多 6 个）」。）、`windows-02-v1.24-picker-cleared.png`（清空后按钮消失、提示改口）。
清空后仍会列出**当前正在看的那个人**（`张三`）—— 那是"这一屏在回答谁"的显示，不是历史名单。

**本轮回归**：Windows **86** + 安卓 **115** 单测全绿；8 套设备脚本全 PASS（中位 **0.52 秒**）；
v1.24 安装包 `/S` 静默安装 → 注册表 `DisplayVersion=1.24`，启动正常
（`windows-03-v1.24-installed-app.png`，页脚版本号 `v1.24`）。

### 13. 手机端悬浮窗不见了 / 点不开——v1.25（Android 14 + Android 16 模拟器）

用户反馈：「手机端的悬浮窗很不稳定，悬浮窗按钮总是不见，还经常点不开」。
查下来是三个独立原因，前两个直接造成这个现象，第三个是同一轮里被测出来的两个真 bug。

**① 窗口被 ROM 摘掉后，程序自己不知道。** 程序用 `root != null` 当作"浮窗在屏幕上"，
而 MIUI/HyperOS 之类随时会摘掉 `TYPE_APPLICATION_OVERLAY` 窗口。窗口没了、标志还在，
于是所有"没浮窗才补一个"的检查全被跳过 —— 从那一刻起浮窗再也不会出现（按钮不见），
而它其实不在屏幕上（点不开）。修法：`RootView.onDetachedFromWindow` 自报被摘 →
0.4 秒后重挂；再加 2 秒一次的 `ensureVisible` 兜住静默移除；同一秒内反复被摘 3 次
就退避 30 秒并提示允许「后台弹出界面」。

**② 「隐藏助手（本次）」是单向门**：删窗口，没有任何路径会再加回来。改成
「隐藏助手 10 分钟」+ 主界面「悬浮窗不见了？→ 重新显示」按钮（计数值写进 SharedPreferences，
服务的 `OnSharedPreferenceChangeListener` 收到就 `respawn()`）。

**③ 顺带两个"识别不到"的真 bug**（同一轮模拟器测试里抓到的）：
* `isFlat` 用 4×4 网格取样判断"这一屏是不是纯黑/被防截屏"。在测试会话里，四行采样点
  正好落在两个绿气泡之间的壁纸空隙上，于是一屏完全能读的聊天被判成"受保护窗口"，
  直接放弃识别（日志：`the message area came back flat - window is protected`，
  每 2 秒一次，永远不读）。改成 15×15 网格后同一屏读到 21 行。
* 面板收起后，它上一次的矩形仍被算作遮挡区，识别结果被大量丢弃：
  `read 21 line(s), 17 under the panel` → 改成只在面板展开时算遮挡后是 `1 under the panel`。

**工装**：`tools/verify_overlay_selfheal.py`（对着 window manager 查窗口在不在、
点一下会不会开、以及**把窗口从程序底下抽走**后它自己回不回来）。抽窗口用的是 debug 包
里的测试钩子 `com.litechat.app.DEBUG_DETACH_OVERLAY`（release 不注册）。
另有 `com.litechat.app.DEBUG_DUMP_SHOT`：把识别用到的**那张图**存到
`files/ocr_shot.png`，用来确认"读到的画面"到底是什么（`android-62-v1.25-reader-capture.png`
就是它导出来的，里面是完整聊天内容 —— 所以空白判定出错在取样，不在截屏）。

```
# Android 14 模拟器
$ python tools/verify_overlay_selfheal.py
bubble on screen: 137x137 at (21,522)
panel opened in 0.0 s (830x1242)
collapsed back to 137x137
window torn away, app noticed after 0.1 s
came back on its own after 0.1 s (137x137)
overlay self-heal: PASS

# Android 16 模拟器（同一脚本，同一份代码）
bubble on screen: 137x137 at (21,522)
panel opened in 0.1 s (830x1344)
collapsed back to 137x137
window torn away, app noticed after 0.1 s
came back on its own after 0.5 s (137x137)
overlay self-heal: PASS
```

「隐藏 10 分钟」也实测过：点完窗口立刻消失，7 秒后仍然不在（自愈不会把用户主动隐藏的
浮窗硬塞回来），回主界面按「重新显示」3 秒内窗口回来（`android-59` / `android-60`）。

**本轮回归**：Windows **86** + 安卓 **122** 单测全绿；8 套设备脚本全 PASS（中位 **0.54 秒**）；
v1.25 安装包 `/S` 静默安装 → 注册表 `DisplayVersion=1.25`。

**没解决的**：手机上别的悬浮窗应用（这台机器上是 `com.jev.probe` 和 GKD）抢触摸时，
本程序无法阻止对方截走点按 —— 那是两个 App 之间的冲突，只能卸载/停用其中之一。

## 三、v1.15：装饰行的两个真 bug + 手机端一条坏设置

反馈：「没分清谁说的 / 一直识别不出来也无法自动生成回复内容」，
截图里面板写着「最近消息（没分清谁说的）」，内容只有两行时间戳。

### 电脑端：复现与修复（`tools/fake_chat_window.py --variant d`）

`--variant d` 画的就是那一屏：`昨天 22:18` / `宝宝我到长沙啦` / `昨天 22:35`。
`LITECHAT_DEBUG_POLL=1` 跑出来的两次轮询是：

```
[poll] msgs=1 key='宝宝我到长沙啦' same=False hits=0
[poll] msgs=1 key='宝宝我到长沙啦' same=False hits=1   -> 认下这条，发请求
```

`msgs=1` 是关键：两行时间戳都被当装饰行丢掉了，**修之前这里是 `msgs=3`，
而且 key 是 `昨天 22:35`** —— 最新"消息"是个时间戳，模型自然写不出回复。

| 截图 | 打勾的内容 |
|---|---|
| `windows-15-v1.15-installed-longcat.png` | **已安装的正式安装包** + 真实 LongCat-2.0：`会话：陆林晖 · 读到 1 条消息`、对方最近说「宝宝我到长沙啦」、推荐「到了就好，注意休息」/ 备选「到了先安顿好，工作不急」「好的，到了就好」 |
| `live-a-short-multi-steward-v1.14.png` | 短消息连发（`在吗` → 0.8 秒后 `忙不忙`）+ 调用军师：推荐「忙的，我手上正在赶那份材料，三点前一定给您完整答复」，军师判断与参考都出来 |
| `live-d-longcat-v1.14.png` | 同一屏用 v1.14 源码跑的前后对照（时间戳已过滤、单条气泡也算分得清方向） |

### 手机端：先崩了，再修好

同样这版手机端跑一次就暴露了第四个 bug。安装 debug 版 + 真实 LongCat-2.0
（`adb logcat`，tag `LITECHAT`）：

```
W LITECHAT: overlay: canDrawOverlays=false
I LITECHAT: ocr[com.litechat.testchat] msgs=4 manual=false
I LITECHAT: ocr[com.litechat.testchat] read=other:昨天那份材料你看过了吗? | ...
I LITECHAT: answered in 6997 ms skill=general noThinking=true
```

修之前，同一段流程死在设置读取上，面板只显示
`出错了 / java.lang.String cannot be cast to java.lang.Boolean`——
因为配置文件里 `no_thinking` 是字符串，而读它的代码按布尔读。
现在读取一律容错，`tools/set_android_prefs.ps1` 也按类型写布尔。

| 截图 | 打勾的内容 |
|---|---|
| `android-30-v1.15-longcat-analysis.png` | 分析卡片完整：`技能 调用军师 / 取消军师`、`对话人 自动跟随`、紧张度 `6/9 要小心`、意图「催我赶紧给说法」、建议「三点前务必给到明确答复」、候选「三点之前一定给你明确答复」「下午三点前我整理好客户要的材料发你」；`answered in 6997 ms` |
| `android-29-v1.15-release-home.png` | 签名正式包（`versionCode 16 / versionName 1.15`）在设备上安装并启动正常，底部三项权限状态与 MIT 署名都在 |

### 八套设备脚本（电脑端，全部指向本地假接口）

```
verify_prompt_anchor        PASS   （提示词锚在【要回的那句】上）
verify_no_repeat_calls      PASS   （空闲 20 秒 / 窗口挪 1px 都不多发请求）
verify_no_flicker_restart   PASS   （闪烁 24 秒 0 请求，真消息仍答）
verify_latency              PASS   （中位 0.57 秒，最慢 0.73 秒）
verify_double_message       PASS   （两条一起到 / 两条短消息隔 0.8 秒）
verify_inflight_messages    PASS   （请求途中来新消息 / 途中换会话）
verify_thinking_fallback    PASS   （接口不认"关闭思考"就回退并记住）
verify_conversation_switch  PASS   （换窗口 / 同窗口换人 / 锁定 / 锁定后切人）
```

## 四、v1.13：手机端空面板也有「对话人」

反馈：「对话人选项怎么没了」。手机端那一行原来只画在**有候选回复**的界面上，
刚打开面板（「分析当前对话」那一屏）既看不到也点不到；电脑端一直在，所以两边不一致。

修复后实测（模拟器 + 微信版式测试 App + 真实 LongCat-2.0）：

| 截图 | 打勾的内容 |
|---|---|
| `android-27-v1.13-partner-row-on-empty-panel.png` | 面板刚打开、还没生成回复时就有 `对话人 · 自动跟随` 一行；同屏还有 `技能 调用军师/取消军师` |
| `android-28-v1.13-partner-picker.png` | 点「对话人」后的名单：`✓ 自动跟随`、`李沅汐`、`本品为`（"李沅汐"的一次 OCR 误读），每项写着「锁定后只分析 TA」，还有「返回」 |
| `windows-13-v1.13-longcat.png` | 电脑端装出来的 v1.13：`对话人 自动跟随`、军师判断 + 参考、紧张度 4/9、三条候选，锚在对方最新那句 |

同一版用真实 LongCat-2.0 复测（关思考开着）：电脑端通用 3.8 / 2.5 秒、军师 3.5 / 2.7 秒；
安卓 `answered in 3526 ms`。

## 五、v1.12：连发多条消息不再卡死

### 先复现（`tools/verify_inflight_messages.py`，接口故意 6 秒才回）

```
model answers after 6000 ms on purpose
requests after startup      : 1
--- message 1 ---
requests while it is thinking: 1 (should still be 1)
--- message 2 arrives mid-request ---
anchor of the follow-up     : (none)          ← 复现：第二条永远不再被问
--- and it still answers the next one ---
anchor of the third         : (none)          ← 之后全都不动

message arriving mid-request handled: FAIL
```

原因：第 1 条的请求在途，第 2 条到达 → 程序判定"在途的答案已过期"并丢弃，
**但没有松开"正在请求"这个开关**。此后每次生成都被"上一次还在跑"挡住：
电脑端要等看门狗（50 秒），手机端没有看门狗，会一直卡到下次换会话。

手机端还有一处同类问题：微信弹出表情面板/贴纸选择器时**窗口句柄会变**，
原来的判断把这也算成"换了会话"，于是在途答案被丢掉——然后又踩上面那个坑。

### 修完后同一套测试

```
--- message 2 arrives mid-request ---
anchor of the follow-up     : 【要回的那句】客户那边已经等了两天了
--- and it still answers the next one ---
anchor of the third         : 【要回的那句】那就今天下前吧
--- switching conversation mid-request ---
anchor after the switch     : 【要回的那句】行，我这就发你

message arriving mid-request handled: PASS
```

### 连发消息的两种时序（`tools/verify_double_message.py`）

```
--- two messages arrive back to back ---
anchor after the pair       : 【要回的那句】客户那边已经等了两天了
--- one more message ---
anchor after the next one   : 【要回的那句】那就今天下班前吧
--- two SHORT messages, 0.8s apart ---
anchor after the short pair : 【要回的那句】忙不忙

double message handled: PASS
```

### 手机端（模拟器 + 微信版式测试 App + 6 秒慢接口，连点两下输入栏）

```
I LITECHAT: answered in 6313 ms skill=general noThinking=true
I LITECHAT: ocr[com.litechat.testchat] read=… | other:材料我明天上午给你 | other:那下车的会改到四点了,你
I LITECHAT: answered in 6019 ms
I LITECHAT: answered in 6026 ms
（mock 共收到 4 次 POST；末次请求的锚点＝屏幕最新那条消息）
```

单测：`ConversationSessionTest` 新增两条——"窗口句柄变了还是同一个会话"、
"来了新消息仍然让在途答案作废"（安卓 91 条 + Windows 72 条全绿）。

## 六、v1.11：换人不再回答上一个人

反馈：「多窗口对话时，切到另一个人，生成的还是上一个窗口的内容」。

### 电脑端：两个假会话，四个场景（`tools/verify_conversation_switch.py`）

```
conversation A anchor : 【要回的那句】行，那我三点再来问你
--- bringing conversation B to the front ---
after the switch      : 【要回的那句】行，我这就发你
--- switching person INSIDE that window (李四 -> 刘工) ---
after the contact switch: 【要回的那句】那你先发我一份大纲

--- pinning 李四 (the picker's 锁定) ---
while 张三 (window A) is in front: 0 request(s) (pinned to 李四)
bringing 李四's window forward again
raised=True foreground now=轻聊第二会话
pinned conversation     : 【要回的那句】行，我这就发你
switching that window to 刘工 while pinned to 李四
requests for the other person: (none)

switch follows the new conversation: PASS
```

四个场景：① 切窗口跟到新会话；② 同一窗口内换联系人跟到新人；
③ 锁定的人不在屏幕上 → 0 次请求；④ 锁定的人出现才继续答。

### 手机端：模拟器 + 微信版式测试 App（`adb logcat`）

```
I LITECHAT: conversation changed in the same window (3 chars -> 2 chars)   ← 点标题栏换人
I LITECHAT: ocr[com.litechat.testchat] read=other:明天的机票订好了吗 | other:我这边一共三个人去,行季 | …
（mock 收到 1 次请求）

锁定「李沅汐」之后再换到「老刘」：
I LITECHAT: conversation changed in the same window (3 chars -> 2 chars)
I LITECHAT: pinned to another conversation: skipped (read=老刘)            ← 0 次请求

删掉锁定再换回：
（mock 收到 1 次请求）
```

「对话人」列表用的是程序**读到过**的名字，实测记录：
`known_conversations = 老刘 / 李沅汐 / 本沅火`（最后一个是"李沅汐"的一次 OCR 误读——
列表里显示的是它读到的那个，所以选中它仍然能锁定成功）。

截图：`android-25-v1.11-conversation-picker.png`（选择对话人界面）、
`android-26-v1.11-conversation-row.png`（面板上的「对话人 · 自动跟随」一行）。

### 这一版还修掉的两个测试工装 bug（都会让测试假装通过/失败）

1. 假聊天窗口重绘时把闪烁的"时间戳/正在输入"也一起销毁了，下一帧碰到已销毁的控件就
   抛异常，**整个刷新循环静默停掉**——于是"换人"这个动作根本没发生。
2. 假窗口的版式和程序实际使用的裁剪参数对不上，**标题那一行从来没被裁进去**，
   所以"换人检测"这条路径以前根本没被测到。

## 七、v1.10：生成速度（军师 15-25 秒 → 3-5 秒）

### 先把"慢在哪"量出来

同一句话、同一个模型（LongCat-2.0）、同一套程序提示词：

| 变量 | 耗时 | 说明 |
|---|---|---|
| 程序侧：消息出现 → 请求发出 | 0.47-0.88 s | 本来就不慢 |
| 参考文档 1500 字 | 14.0 / 16.0 s | 现状 |
| 参考文档 400 字 | 15.1 s | 没差 |
| **完全不带参考文档**（提示词 133 字） | **25.0 s** | 更慢，纯噪声 |
| 追加"不要输出思考过程" | 19.3 / 20.7 / 21.2 s | 没用 |
| `max_tokens=600` | 思考吃掉 599 token，**无答案** | 压预算只会截断 |
| `max_tokens=900` | 思考吃掉 801 / 899，**无答案** | 同上 |
| 服务端 `reasoning_tokens` | 125 → 约 10 s；905 → 约 15 s | **真正的因** |

### 再对症下药：让模型别思考

```
{"thinking": {"type": "disabled"}}                  → 3.4 s（思考 0）
{"chat_template_kwargs": {"enable_thinking": false}} → 3.6 s（思考 0）
{"reasoning": {"effort": "none"}}                    → 18.8 s（被忽略，仍在想）
```

程序默认发前两个，并带自动回退。**双端真实接口实测**：

| 平台 | 关之前 | 关之后 | 证据 |
|---|---|---|---|
| Windows（本机真实微信窗口） | 14-25 s | **3.5 / 5.2 s** | `windows-10-v1.10-installed-fast-agent.png` |
| 安卓（模拟器 + 微信版式 App） | 15-40 s，常超时 | **2907 / 3082 / 3231 / 6586 ms** | `adb logcat`：`answered in 3082 ms skill=goutoujunshi noThinking=true` |

安卓截图 `android-24-v1.10-fast-agent.png`：屏幕最后一条是「材料我明天上午给你」，
面板给出的首选回复是「我明天上午十点前把完整材料发您，今天先把手上的整理完发过去」——
答的就是那条。

### 不认识的接口不会被打坏

`tools/verify_thinking_fallback.py` 起一个**只接受已知字段**的假网关（多一个字段就 400）：

```
1) an endpoint that rejects the hint still answers
   replies: ['甲', '乙', '丙']
   hint rejected: True
   attempted with hint: [['chat_template_kwargs', 'thinking']] then without: [[]]
2) the host is remembered, so the hint is not tried again
   with hint: [] | without hint: [[]]
3) turning the setting off sends nothing extra
   with hint: [] | without hint: [[]]

strict-gateway fallback: PASS
```

### 这一版的全量回归

```
Ran 72 tests ... OK            ← Windows 单测
BUILD SUCCESSFUL               ← 安卓单测（89 个）
All tests passed.

flicker ignored + real message still answered: PASS
idle / repaint produce no extra model calls: PASS
prompt anchored on the right message: PASS
messages answered within   : 0.52 s (median)
```

## 八、v1.9：用真实 LongCat-2.0 抓到的四个根因

### 1. 推理模型的输出预算（"牛头不对马嘴 + 乱码"的真凶）

`tools/live_prompt_probe.py` 拿程序**自己拼的提示词**去打真实接口，把原始回复和
解析结果一起打出来。第一次跑就露馅了：

```
--- envelope (no message.content) ---
{"choices":[{"finish_reason":"length",
  "message":{"role":"assistant",
             "reasoning_content":"\n1. **分析输入：**\n * **会话：** 轻聊测试会话\n …"},
  "usage":{"completion_tokens":320,
           "completion_tokens_details":{"reasoning_tokens":319}}}]}
```

320 个 token 里 **319 个是思考**，`content` 字段不存在。旧代码把 `reasoning_content`
当答案 → 候选回复就是模型的自言自语。同一句话、同一个模型，给足预算后：

```
intent : 催结果给客户交代
advice : 三点前给结论带上方向
reply 1: 能，我手上那份刚弄完，马上接着看，三点前肯定给你个说法
reply 2: 三点准时来找你，就算定不下来也给个方向先安抚客户
reply 3: 三点见，我抓紧弄
```

三处修复都有单测钉住：预算 320→1600、解析器扫描成对 `{...}`（取最后一个）、
以及"只有思考没有答案"时**不把思考当回复**。

### 2. 手机端真实接长这样（截图 + 日志）

| 截图 | 打勾的内容 |
|---|---|
| `android-23-v1.9-longcat-agent.png` | 手机端点测试聊天底部输入栏追加一条对方消息后，用**真实 LongCat-2.0** 出结果：军师模式 · 判断：时间变动，尽量配合老板 · 参考：获得领导青睐…；紧张度 2/9；对方意图「确认你是否能参会」；候选 1「能来，四点我准时到。」候选 2「能来，我下午把材料整理好带过去。」——**回答的就是最后那条"那下午的会改到四点了，你能来吗"** |
| `windows-08-v1.9-longcat-agent.png` | 电脑端同一句话、同样真实接口：军师模式 · 判断：催促升级，需带方略稳住客户；推荐回复「我先拖住他，说材料正在核对马上出结果，你这边定下来我立刻对接」 |
| `windows-09-v1.9-installed-agent-line.png` | **从 v1.9 安装包装出来**的程序，对着本地假接口跑：军师的 `stance` 和参考文档名都显示在面板上 |

### 3. OCR 放大 A/B（1 倍 vs 2 倍，同一屏同一台机器）

| 倍率 | 读到的内容 |
|---|---|
| 1 倍 | `昨天那份材料你看过了吗?` / `客户那边催得有点急` / `我早上看了一半,下午给你` |
| 2 倍 | `昨无那份材料你看过了吗?` / `容户那边催得有点急` / `我旱上看了一半`，另有一帧把对方气泡判成"我" |

结论：放大反而更差，默认改回 1 倍。

### 4. 裁剪几何：内嵌窗口与铺满屏幕两种都要对

```
I LITECHAT: wechat band=Rect(0, 254 - 1080, 2133) rootBottom=2400 display=1080x2146 density=2.625
```

`display=1080x2146` 是服务拿到的"可用区"高度，`rootBottom=2400` 是窗口自己的底边。
新规则取 `min(窗口底边, 屏幕高 − 导航栏) − 输入栏`：铺满屏幕时 = 2133（对），
内嵌窗口时 = 2133（同样对，不会把导航栏减两次）。

### 5. 这一版的回归

```
Ran 67 tests ... OK                     ← Windows 单测
BUILD SUCCESSFUL                        ← 安卓单测（88 个）
All tests passed.

flicker ignored + real message still answered: PASS
idle / repaint produce no extra model calls: PASS
prompt anchored on the right message: PASS
messages answered within   : 0.60 s (median)
```

过程中还抓到并修掉一个自己引入的显示 bug：解析结果从 4 元组变成 5 元组后，
面板代码仍按 4 元组解包 → 模型一回答面板就**卡在"更新中…"**。

## 九、v1.8：狗头军师技能 + 手机端三个真 bug

### 技能按钮（手机端，模拟器上实际点的）

| 截图 | 打勾的内容 |
|---|---|
| `android-20-skill-on.png` | 技能行两个按钮：`调用军师 ✓`（绿色高亮）/ `取消军师`；面板同时给出紧张度 3/9、对方意图、建议和 3 条候选 |
| `android-21-skill-off.png` | 点 `取消军师` 之后：高亮换到 `取消军师 ✓`，`调用军师` 变回普通样式；偏好值也变回 `general` |
| `android-22-new-messages-detected.png` | 点测试聊天底部输入栏追加两条对方消息（白底左对齐），面板跟着更新 |

### 电脑端（装出来的正式安装包，不是源码运行）

| 截图 | 打勾的内容 |
|---|---|
| `windows-07-v1.8-installed-skill.png` | 从 `LiteChat-Setup-Windows-v1.8.exe` 装出来的程序：控制栏第二行是 `技能 [调用军师 ✓] [取消军师]`（`调用军师` 绿色高亮，没有截断）；下面读到 6 条消息、给出意图/紧张度/建议和 3 条候选；右下角是安装完成提示 |

同一份安装包对着本地假接口跑，程序实际发出去的请求里：

```
【要回的那句】i: 那个客户又打电话过来了
【参考：获得领导青睐：从价值匹配到信任建立的实用指南】   ← 军师的参考文档确实带上了
```

日志（同一台模拟器）：

```
I LITECHAT: skill=goutoujunshi refs=[获得领导青睐：从价值匹配到信任建立的实用指南]
I LITECHAT: wechat band=Rect(0, 254 - 1080, 2133) rootBottom=2400 display=1080x2146 density=2.625
I LITECHAT: ocr[com.litechat.testchat] read=other:昨天那份材料你看过了吗? | other:客户那边催得有点急,今天 | me:我早上看了一半,下午给你 | me:行,那我三点再来问你,我 | other:材料我明天上午给你
I LITECHAT: ocr[com.litechat.testchat] decide(v3) screenRead=true autoAnalyze=true latest=other auto=true
[mock] POST /v1/chat/completions       ← 新消息真的触发了一次模型调用
```

（`read=` 这一行只在 debug 包里打，正式包不输出聊天内容。）

### 修的三个真 bug

1. **切换技能时浮窗闪一下。** 技能按钮会弹一条提示，而提示窗口属于本应用自己的包名，
   无障碍服务把它当成"用户打开了轻聊助手"，撤掉浮窗、下一次事件再建回来。
   现在自己的提示窗口在 2 秒内被忽略。实测：点按钮后连拍 8 帧，**8/8 帧浮窗都在**
   （修之前 1/8，那一帧就是 `docs/verification` 里删掉的那张"什么都没有"的画面）。
2. **最新那条消息读不到。** 服务的 `displayMetrics` 给的是**可用区高度**（这台机器 2146），
   截图却是**整屏** 2400。混用之后裁剪区底部落在 1879，而新消息在 1830–2063 ——
   程序看得见对话，却永远看不见要回的那句（日志里 `msgs=4` 但新消息不在其中）。
   改用窗口节点真实底边后，裁剪区变成 2133，新消息被读到。
3. **OCR 没放大。** 电脑端一直在用 3 倍放大，手机端是原图。现在手机端放大 2 倍
   （带 12M 像素上限）。同一屏 `昨无` → `昨天`，短句 `材料我明天上午给你` 整句读对。

另外：OCR 把"对方正在输入…"读成 `三在窪入一` 这种乱码时，短语过滤抓不到，
每次闪烁都会被当成一条新消息（就是"候选一直跳"）。现在**6 字以内且含两个不同
"在/入/输/正"** 的行按装饰行丢掉，`现在在路上了` 这类真回复不受影响。

电脑端同一套规则 + "同屏两次才认新消息"的二次确认：

```
requests after startup           : 1
requests after 24s of flickering : 1  (delta 0)
requests after a real message    : 2  (delta 1)
flicker ignored + real message still answered: PASS
```

## 十、v1.6：壁纸背景下的分析 + 不再闪烁

反馈：「悬浮窗老是一闪一闪的，而且还无法分析出自动回复内容」。

### 壁纸场景（复现用户的实际设置）

把测试聊天界面的背景从微信默认灰换成**壁纸色**，完整跑通并出候选：

| 截图 | 打勾的内容 |
|---|---|
| `android-19-wallpaper-chat-works.png` | 壁纸背景 + 绿色自己的气泡 + 白色对方气泡；面板显示「微信隐藏了消息文字，这里用截屏识别」、紧张度 3/9、对方意图、建议、3 条候选（带复制/填入）；四条气泡全部识别正确 |

日志：

```
decide(v3) screenRead=true autoAnalyze=true latest=other auto=true    ← 分析
decide(v3) screenRead=true autoAnalyze=true latest=me    auto=true    ← 不再误杀
skipped: no new incoming message                                      ← 没有新消息时正确跳过
[mock] POST /v1/chat/completions
```

修掉的两个 bug：

1. **一闪一闪**：检测不到活动窗口时会把浮窗整个隐藏，而微信的表情面板、贴纸选择器、
   "+"菜单都是独立窗口，每次弹出都会藏一次、下一个事件又显示回来。
   现在这个检查纯粹只做判断、没有副作用；并且"窗口句柄变了"不再算"换会话"，
   只有 App 或会话标题变了才算。
2. **分析不出来**：聊天界面判断是靠"背景是灰色"认的，用户换了聊天壁纸 →
   背景不是灰的 → 直接跳过分析。这条判断整个删掉了（误判成"不是聊天"会让程序
   什么都不做，代价远大于多问一次模型）。
3. 连带修掉：不再要求"最后一条必须是对方"，只比对"最新的对方消息有没有变过"。

## 十一、v1.5：手机微信的截屏读聊天（设备上验证）

微信装不进模拟器，所以写了一个**只画微信版式的测试 App**
（`android/testchat/`，只在 debug 包里注册，永不进正式包）：灰底、白色对方气泡、
绿色自己的气泡、同样的标题栏与输入栏高度。

把两个包都装进 Android 14 模拟器、用 adb 打开无障碍与悬浮窗权限后，日志：

```
I LITECHAT: capture service connected
I LITECHAT: ocr[com.litechat.testchat] taking a screenshot (region=true rects=0)
I LITECHAT: ocr[com.litechat.testchat] msgs=4 manual=false
[mock] POST /v1/chat/completions          ← 真的调用了模型
```

面板（`android-17-screenshot-chat-reading.png`）显示：
「微信隐藏了消息文字，这里用截屏识别」、紧张度 3/9、对方意图、建议，
以及 3 条带「复制 / 填入」的候选回复。四条气泡全部识别正确，
绿色那条也被正确认成"我"。

**这是安卓采集链路第一次在设备上端到端跑通** —— 之前只验证到"装了、能开"。

顺带修掉的两个真 bug（都是这次才暴露出来的）：

- 微信没有气泡几何信息时，"OCR 指纹"是个常量 → **第一次之后就永远不再截屏**；
- 判断"这是截屏读的"用了一个会被下一个无障碍事件覆盖的成员变量 →
  **读到了内容却不触发分析**（日志里 `msgs=4` 之后什么都没有）。

## 十二、v1.4：回复不再答非所问

反馈：「自动生成的回复内容感觉都牛头不对马嘴」。

### 直接抓程序实际发出的请求来验证（`tools/verify_prompt_anchor.py`）

```
--- what the app asked the model ---
【会话】未命名会话
【关系】对方是我的老板
【要回的那句】i: 材料我明天上牛给你
【上文，越靠下越新】
对方：阝份材料你看过了吗？
对方：；那边催得有点急，今天骨个说氵去吗
我：我早上看了一半，下午给你答复
对方：那我三点再来问你
------------------------------------
prompt anchored on the right message: PASS
```

（"上牛""骨个"是测试窗口小字号下的 OCR 错字，不是程序缺陷；真实微信字号更大。）

要点：**要回的那句是对方最新那条**，**我自己的话被标成「我」** ——
靠的是读气泡颜色（微信自己发的消息是绿底）。旧版本在只看到一条消息时
会把它当成"对方说的"，于是模型开始回复用户自己。

## 十三、v1.4：手机版微信适配

| 截图 | 打勾的内容 |
|---|---|
| `android-15-v1.4-release-home.png` | v1.4 正式签名包：首页文案已更新为「微信、QQ、X、飞书都支持」 |
| `android-16-v1.4-wechat-toggle-and-roundtrip.png` | 设置页里 **「读取微信」开关存在且默认打开**（连同「自动识别其它聊天软件」「自动把推荐回复填进输入框」）；底部状态行 **「连接成功，模型回复：收到」** |

设置页那一屏同时验证了：新开关布局正常、底部固定保存栏仍可用、
指向本地假接口的连通测试成功（服务端日志同步记录到 `POST /v1/chat/completions`）。

## 十四、v1.3：候选回复不会被"跳"掉

反馈：「明明已经自动生成了回复内容，但他来回给我跳让我选不了」。

### 1. 闪烁的装饰行不再触发重新生成（`tools/verify_no_flicker_restart.py`）

让假聊天窗口连续 24 秒闪烁时间戳 + 「对方正在输入…」，然后发一条真消息：

```
requests after startup           : 1
blinking 时间戳 + 对方正在输入… for 24s ...
requests after 24s of flickering : 1  (delta 0)
requests after a real message    : 2  (delta 1)

flicker ignored + real message still answered: PASS
```

### 2. 刷新时不清空已有候选

`windows-05-keeps-suggestions-while-loading.png`：把假接口调慢到 6 秒，
在刷新进行中截图 —— 头部显示 **「更新中… 1.7 秒」**，
而**三条候选卡片和各自的「复制 / 填入聊天框」按钮原地没动**，
「对方最近说」已经更新成新消息。
以前的版本在这一刻会把卡片全部清空并显示「正在想…」，手伸过去就没了。

### 3. 速度复测（`tools/verify_latency.py`）

```
messages answered within   : 0.58 s (median)
worst case                 : 1.10 s
```

## 十五、v1.2 的速度验证

### 1. 先确认瓶颈不在模型

用真实的 DeepSeek 官方接口打一次（配置文件里的 key，只发了一次）：

```
NON-STREAMING call: 0.85 s, output chars=180
   first 60 chars: {"intent":"催要材料处理结果","danger":4,"advice":"马上回复进度和给出时间","repl
```

**0.85 秒**。所以慢是程序自己的问题。

### 2. 改造前后对比（`tools/bench_pipeline.py`）

```
=== per-poll cost (the capture loop) ===
  PrintWindow + crop            18.5 ms
  OCR of the message area      110.9 ms
  header capture                18.0 ms
  header OCR (title)            14.7 ms
  TOTAL per poll (current)     172.8 ms
  -> polling every 2s burns        9% of one core
  two consecutive captures byte-identical: True
```

因为"没变化时两次截图逐字节相同"，v1.2 加了像素指纹短路：**一模一样就不跑 OCR**。
空闲每轮从 172.8 ms 降到约 18 ms。

### 3. 不会重复问模型（`tools/verify_no_repeat_calls.py`）

```
requests after startup          : 1
requests after 20s idle         : 1  (delta 0)
moved the chat window by 1px (pixels changed, text did not)
requests after a 1px move       : 1  (delta 0)
idle / repaint produce no extra model calls: PASS
```

- 空闲 20 秒：**0 次多余请求**。
- 把窗口挪 1 像素（画面变了、文字没变）：**仍然 0 次**。

这一条正是「永远停在正在想…」的根因回归测试。

### 4. 从消息出现到发出请求（`tools/verify_latency.py`）

```
baseline requests after startup : 1
  message 1: request left after 1.13 s
  message 2: request left after 0.48 s
  message 3: request left after 0.52 s

messages answered within   : 0.52 s (median)
worst case                 : 1.13 s
```

上一版光轮询间隔就是 2 秒，而且每一轮还要多跑一次标题 OCR。

## 十六、电脑版验收

| 截图 | 打勾的内容 |
|---|---|
| `windows-01-desktop-ui.png` | 桌面端界面渲染正常 |
| `windows-02-ocr-sample.png` | Windows 自带 OCR 的中文识别样例 |
| `windows-03-installed-auto-detect.png` | v1.1：已安装版本自动锁定窗口并出候选 |
| `windows-04-v1.2-installed.png` | **v1.2 已安装版**：自动锁定「轻聊测试会话」、读到 5 条消息、列出「对方最近说」、给出建议与紧张度、3 条候选回复各带「复制 / 填入聊天框」，状态行为「建议已更新（用时 0.0 秒）」 |
| `windows-05-keeps-suggestions-while-loading.png` | **刷新进行中**：标题右侧「更新中… 1.7 秒」，候选卡片原地保留 |
| `windows-06-v1.3-installed.png` | **v1.3 已安装版**：自动锁定窗口、读到 4 条消息、给出建议与 3 条候选 |

命令验证过、没有截图的部分：

- **静默安装 / 卸载**：`LiteChat-Setup-Windows-v1.2.exe /S` → 退出码 0，目录、桌面快捷方式、
  开始菜单、注册表项齐全；已安装程序窗口标题为「轻聊助手」；卸载器 `/S` → 退出码 0，
  **目录不留残余**、快捷方式与注册表项都已清除。
- **「填入聊天框」真的生效**：点按钮后读回目标窗口的输入框内容，
  里面出现了推荐回复原文 —— 不是只写了剪贴板。
- **自动分左右**：对同版式测试窗口的 OCR 结果分边为 `[other] [other] [me] [other]`，
  与画面里谁说的完全一致。

### 关于真实微信窗口

窗口发现规则、版式坐标（左侧栏 230px、标题条 38px、会话头 47px、输入区 95px）、
PrintWindow 抓图和 DeepSeek 真实调用，都是在**真实的微信电脑版 4.x 窗口**
（类名 `Qt51514QWindowIcon`）+ 真实 key 上验证过的。
做到后半段那个微信窗口被关掉了，所以最终 v1.2 的整链路验收换成同版式的测试窗口
（`tools/fake_chat_window.py`）。**没有在你的微信上跑过最终版** ——
第一次用如果切歪了，点「手动框选」兜一下。

## 十七、手机版验收（Android 14 模拟器）

安装的是**和发布包同一份 release 配置**、额外带上 x86_64 的构建（发布包只含 arm64）。

| 截图 | 打勾的内容 |
|---|---|
| `android-12-v1.2-release-settings.png` | v1.2 正式签名包：设置页正常；「确认保存」固定在屏幕底部；「回复长度上限」显示新默认值 **320** |
| `android-13-v1.2-api-roundtrip.png` | v1.2：端到端打通模型调用 |
| `android-14-v1.3-api-roundtrip.png` | **v1.3 正式签名包**：指向本地假接口点「保存并测试连接」→ **「连接成功，模型回复：收到」** |
| `android-17-screenshot-chat-reading.png` | **v1.5 截屏读聊天整链路**：面板显示「微信隐藏了消息文字，这里用截屏识别」+ 意图 + 紧张度 + 3 条候选 |
| `android-18-v1.5-release-home.png` | v1.5 正式签名包在设备上正常启动 |
| `android-19-wallpaper-chat-works.png` | **v1.6 壁纸背景**：面板显示意图/紧张度/建议/3 条候选 |
| `android-08` · `android-09` · `android-10` | v1.1 的首页、底部固定保存栏、新增开关 |
| `android-03` · `android-04` · `android-06` | 20 个服务商预设；选预设自动填地址/模型；保存后重进配置仍在 |

APK 通过 `apksigner verify`（v2 签名），包名 `com.litechat.app`，
**versionCode 26 / versionName 1.25**，minSdk 30 / targetSdk 35，
native-code `arm64-v8a` + `armeabi-v7a`（x86_64 只进模拟器测试包，不进发布包）。
证据：`android-44-v1.19-android16-release-home.png`（**安卓 16** 上正式包启动，
主界面「对话人 / 正在回答谁」一栏可见）、`android-41-v1.19-home-picker.png`（同上，放大）、
`android-43-v1.19-switch-3s.png`（安卓 16 + 真实 LongCat-2.0 换人 3 秒内跟上）。
**说明**：正式包不可调（release 的 `run-as` 写不进配置），所以"出候选"那一步是在
同一份代码的 debug 包上跑的（只多一个测试聊天 App 的适配器）；正式包只验到
"安装 + 启动 + 版本号"。

## 十八、没验到的

- **手机端没有在装了 QQ / X / 飞书 / 钉钉的真机上跑过**（没有真机，模拟器装不了这些软件）。
  QQ / X / 飞书是上游实测过的适配；通用聊天识别是启发式判断，
  请以你手机上的实际表现反馈。
- **手机版微信用的是同版式的测试 App 验证的，不是微信本体**（模拟器装不了微信）。
  截屏 → 裁消息区 → OCR → 气泡颜色分左右 → 出候选这条链路已经确认能跑通；
  剩下的未知只有两个，都只有真机能回答：
  1. 微信会不会给聊天界面加**防截屏保护**。加了就截不到图，面板会显示
     「窗口不可见或受保护」，不会静默失败；
  2. 微信的**版式**（标题栏 / 输入栏高度）与测试界面是否一致。不一致时消息区会
     切偏一点，面板上「对方最近说」会立刻显示出来，可据此反馈。
- **提示词从 v1.9 起一律用 LongCat-2.0 验证**，不再动你自己的那把 DeepSeek key。
  解析器对 JSON / 代码块包裹 / 纯文本都能兜底，且都有单测覆盖。
- **安卓签名密钥是恢复工程时重新生成的**。装过更早的包的话，
  **要先卸载旧版再装 v1.15**，否则系统报"签名不一致"。卸载只删 App，不动微信。
- **Claude / Gemini 两条协议分支没有对着官方接口实测**。
- **高 DPI / 多显示器**：电脑端只在 100% 缩放的 1920×1080 上验证过。

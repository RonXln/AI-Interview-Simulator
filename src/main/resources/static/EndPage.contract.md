# EndPage 接口文档

本文件描述 [EndPage.html](EndPage.html) 渲染所需的全部输入。EndPage 是一个**纯前端页面**，不直接向后端发请求；所有数据都从浏览器的 `sessionStorage` 读取，由前一个页面（[zoomMeetingMockPage.html](zoomMeetingMockPage.html)）写入。

---

## 1. 数据来源总览

| 来源 | 存储位置 | 写入者 | 读取者 | 是否必需 |
|---|---|---|---|---|
| 面试对话记录 + 节奏统计 | `sessionStorage.interviewTranscript` | zoomMeetingMockPage 收到 `[TRANSCRIPT]:` 帧时 | EndPage `renderTranscript()` | **必需** |
| 反馈结果 | `sessionStorage.interviewFeedback` | zoomMeetingMockPage 收到 `[FEEDBACK]:` 帧时 | EndPage `renderFeedback()` | 可选（缺失会进入轮询） |

数据流向：

```
后端 AudioHandler
   │
   ├── [TRANSCRIPT]:<json>   ──┐
   │                            ├──► zoomMeetingMockPage 写 sessionStorage
   └── [FEEDBACK]:<json>     ──┘                │
                                                ▼
                                          跳转到 EndPage.html
                                                │
                                                ▼
                                  EndPage 读 sessionStorage 渲染
```

---

## 2. `sessionStorage.interviewTranscript`

**类型**：JSON 字符串（解析后是对象）。
**生成者**：后端 [`TranscriptService.snapshotAsJson()`](../../../java/com/example/demo/service/TranscriptService.java)。

### Schema

```jsonc
{
  "sessionId":      "string，后端 WebSocket session id",
  "totalDurationMs": 1234567,         // 整场面试时长（毫秒）

  "entries": [                        // 按时间顺序排列的对话条目
    {
      "relativeMs": 0,                // 相对会议开始的毫秒数
      "speaker":    "USER" | "ProfA" | "ProfB" | "Alice" | "Bob" | ...,
      "role":       "USER" | "INTERVIEWER" | "PEER_CANDIDATE" | "SYSTEM",
      "content":    "实际说的内容",
      "type":       "SPEECH" | "SYSTEM_EVENT"
    }
  ],

  "userStats": {                      // 仅候选人（role=USER）的简单统计
    "totalUtterances":     12,        // 用户发言次数
    "totalWords":          340,       // 总词数（按空白分词的近似值）
    "speakingTimeMs":      85000,     // 用户实际说话的累计毫秒数
    "wordsPerMinute":      152,       // 平均语速
    "paceLabel":           "TOO_FAST" | "NORMAL" | "TOO_SLOW" | "UNKNOWN",
    "avgThinkingDelayMs":  1850       // 从"被问到"到"开始回答"的平均延迟
  }
}
```

### 字段说明

| 字段 | 用途（EndPage 哪个 UI 元素消费） |
|---|---|
| `totalDurationMs` | 顶部 `#metaDuration` —— 「⏱ 总时长 32:15」 |
| `entries[].relativeMs` | 左侧时间线条目的时间戳 + 改进卡片定位锚点（DOM id = `tl-<relativeMs>`） |
| `entries[].speaker` | 时间线条目里的"谁"，role≠USER/SYSTEM 时直接显示原值 |
| `entries[].role` | 决定时间线条目左侧圆点颜色（USER=紫 / INTERVIEWER=蓝 / PEER_CANDIDATE=灰 / SYSTEM=浅灰斜体） |
| `entries[].content` | 时间线条目正文 |
| `entries[].type` | `SYSTEM_EVENT` 类型走灰色斜体样式；`SPEECH` 是正常发言 |
| `userStats.totalUtterances` | 顶部 `#metaUtterances` |
| `userStats.wordsPerMinute` | 顶部 `#metaPace` |
| 其他 `userStats` 字段 | 不直接渲染，但**会作为 LLM 反馈生成的输入**，间接体现在 `feedback.languagePerformance` 里 |

### 各角色 `role` 值的映射规则（后端 `TranscriptService` 内部）

| `speaker` 例子 | `role` |
|---|---|
| `USER` | `USER` |
| `ProfA`, `ProfB` | `INTERVIEWER` |
| `Alice`, `Bob`, `Charlie`, `David`, `Emma` | `PEER_CANDIDATE` |
| `SYSTEM`（用于 INTERVIEW_STARTED / INTERVIEW_ENDED 等事件标签） | `SYSTEM` |

### 异常情况

| 场景 | 行为 |
|---|---|
| sessionStorage 中无此字段（用户直接打开 EndPage） | 时间线显示「未找到面试记录，可能是直接打开了本页面。」 |
| `entries` 数组为空 | 时间线显示「这场面试还没有任何对话记录。」 |
| JSON 解析失败 | `safeParse` 返回 `null`，等同于上面"无此字段" |

---

## 3. `sessionStorage.interviewFeedback`

**类型**：JSON 字符串（解析后是对象）。
**生成者**：后端 [`FeedbackService.generate()`](../../../java/com/example/demo/service/FeedbackService.java)（异步调用 DeepSeek LLM 产出）。
**到达时机**：通常在 `[TRANSCRIPT]:` 之后 5~15 秒到达。EndPage 必须容忍它**最初不存在**，并自动轮询。

### Schema

```jsonc
{
  "recap": "整场面试复盘的叙述性文字（简体中文）",

  "overallPerformance": {
    "score":     78,                    // 0-100 整数
    "summary":   "一句话总评（简体中文）",
    "strengths": ["亮点1", "亮点2", ...],   // 字符串数组
    "weaknesses": ["短板1", "短板2", ...]   // 字符串数组
  },

  "languagePerformance": {
    "language":    "对用词、语法、表达精确度的点评（简体中文）",
    "pace":        "基于 wpm 的语速分析（简体中文）",
    "rhythm":      "基于停顿和思考延迟的节奏分析（简体中文）",
    "fillerWords": "口头禅观察（简体中文，无则写'未发现明显口头禅'）"
  },

  "improvements": [                     // 长度 3-6，由 LLM 决定
    {
      "stage":          "面试阶段（如 '开场自我介绍'、'项目深挖'）",
      "atMs":           12345,          // 必须能在 transcript.entries 中找到对应（或最接近的）relativeMs
      "trigger":        "谁说了什么（≤30 词，通常英文原文）",
      "yourReply":      "候选人的回答节选（≤30 词，通常英文原文）",
      "betterApproach": "建议的更好回答（简体中文，可直接照说的程度）",
      "rationale":      "为什么这样更好（简体中文，1-2 句）"
    }
  ]
}
```

### 字段说明

| 字段 | EndPage UI 元素 |
|---|---|
| `recap` | 右栏「📝 整场复盘」段落 (`#recap`) |
| `overallPerformance.score` | 顶部环形分数 `#scoreRing`（conic-gradient 填充）+ `#scoreVal` 数字。颜色按区间：≥80 绿、≥60 紫、≥40 黄、<40 红 |
| `overallPerformance.summary` | Hero 区 `#heroSummary` |
| `overallPerformance.strengths[0..1]` | Hero badges（绿色，前两条），全量列在 `#strengthsList` |
| `overallPerformance.weaknesses[0..1]` | Hero badges（红色，前两条），全量列在 `#weaknessesList` |
| `languagePerformance.language` | `#langLanguage` |
| `languagePerformance.pace` | `#langPace` |
| `languagePerformance.rhythm` | `#langRhythm` |
| `languagePerformance.fillerWords` | `#langFiller` |
| `improvements[]` | 右栏「💡 改进建议」卡片列表；**点击卡片**会调用 `jumpToTimeline(it.atMs)`，找到 transcript 里最接近的条目并滚动 + 高亮 3 秒 |

### 改进卡片字段映射到样式（颜色 tag）

| 字段 | UI tag 文字 | tag 颜色 |
|---|---|---|
| `stage` | 显示为右上角胶囊（紫底白字） | accent |
| `atMs` | 显示为时间戳（如 ⏱ 12:34） | mute |
| `trigger` | tag 文字「触发」 | brand 蓝 |
| `yourReply` | tag 文字「你的回答」 | bad 红 |
| `betterApproach` | tag 文字「建议」 | good 绿 |
| `rationale` | tag 文字「为什么」 | mute 灰 |

### 容错路径（**重要**）

EndPage 必须按下面的顺序处理：

1. **页面加载**：立刻调 `renderFeedback()`。
2. 如果 `feedback` 为 `null` → 进入 `startFeedbackPolling()`：
   - 每 **2 秒**重新读 `sessionStorage.interviewFeedback` 一次。
   - 一旦读到非空就停止轮询并 `renderFeedback()`。
   - 累计 **30 秒**（15 次）仍未读到 → `showFeedbackFailure()`：
     - Hero 标题改为「反馈暂时不可用」
     - 右栏所有 placeholder 替换成「反馈生成超时」
     - 左侧时间线不受影响，用户仍能看到完整对话
3. 若 LLM 返回 JSON 形式错误 → 后端 `FeedbackService.fallback()` 会替换为兜底 JSON（仍合法 schema），EndPage 不需要特别处理。

---

## 4. EndPage 不消费但仍存在的 sessionStorage 字段

下面几项由 Setup 阶段写入，EndPage 仅在「再来一次」按钮的 `goHome()` 里负责**清理**它们：

| Key | 含义 |
|---|---|
| `interviewType` | 套餐类型（`1v1` / `2v1` / `2v6`） |
| `personalStatement` | 个人文书原文 |
| `activityList` | 活动列表原文 |
| `micAck` | 用户在 Setup 页勾选过麦克风知情同意（值固定为 `"true"`） |

> ⚠️ **隐私承诺联动**：因为 Setup 页面对用户承诺「面试结束后立即清除个人信息」，所以 `goHome()` 必须把这些字段一起 `removeItem`。当前实现已包含这一逻辑。

---

## 5. EndPage 中所有 DOM id 一览（便于二次定制）

### Hero 区
| id | 作用 |
|---|---|
| `scoreRing` | 环形分数容器（背景靠 inline style 动态设 conic-gradient） |
| `scoreVal` | 分数数字 + 小字「综合评分」 |
| `heroTitle` | "你的面试反馈已生成" / "正在生成..." / "反馈暂时不可用" 三态 |
| `heroSummary` | 一句话总评 |
| `heroBadges` | 亮点/短板 badge 容器（最多 2+2 共 4 个） |

### 顶部品牌条
| id | 作用 |
|---|---|
| `metaDuration` | ⏱ 总时长 |
| `metaUtterances` | 💬 你发言次数 |
| `metaPace` | 🎙 语速 WPM |

### 左侧时间线
| id | 作用 |
|---|---|
| `timeline` | 时间线容器（条目动态生成） |
| `tlCount` | 顶部条目数 badge |
| `tl-<relativeMs>` | 单条目（动态 id，用于 `jumpToTimeline` 滚动锚） |

### 右侧反馈
| id | 作用 |
|---|---|
| `recap` | 整场复盘文字容器 |
| `overallLists` / `overallPlaceholder` | 整体表现列表 / 加载占位（互斥显示） |
| `strengthsList` / `weaknessesList` | 亮点/短板 `<ul>` |
| `langGrid` / `langPlaceholder` | 语言表现 4 格 / 加载占位（互斥显示） |
| `langLanguage`, `langPace`, `langRhythm`, `langFiller` | 语言表现 4 项内容 |
| `improvements` | 改进建议卡片容器 |

### 底部
| id | 作用 |
|---|---|
| `footerTip` | 底部隐私小字 |

---

## 6. 极小的合法输入样例（手工测试用）

把下面两段贴进浏览器 DevTools 控制台执行，再刷新 EndPage 就能看到效果——不需要跑完整面试：

```js
sessionStorage.setItem("interviewTranscript", JSON.stringify({
  sessionId: "demo",
  totalDurationMs: 245000,
  entries: [
    { relativeMs: 0,      speaker: "SYSTEM", role: "SYSTEM",      content: "INTERVIEW_STARTED",                       type: "SYSTEM_EVENT" },
    { relativeMs: 1200,   speaker: "ProfA",  role: "INTERVIEWER", content: "Welcome. Tell me about yourself.",        type: "SPEECH" },
    { relativeMs: 4500,   speaker: "USER",   role: "USER",        content: "Sure, I am a senior majoring in CS.",     type: "SPEECH" },
    { relativeMs: 9000,   speaker: "ProfB",  role: "INTERVIEWER", content: "Can you give a concrete example?",        type: "SPEECH" },
    { relativeMs: 12300,  speaker: "USER",   role: "USER",        content: "Yes, I built a recommender system.",      type: "SPEECH" },
    { relativeMs: 18000,  speaker: "Alice",  role: "PEER_CANDIDATE", content: "But that's a common project, right?", type: "SPEECH" },
    { relativeMs: 240000, speaker: "SYSTEM", role: "SYSTEM",      content: "INTERVIEW_ENDED:USER_ENDED",              type: "SYSTEM_EVENT" }
  ],
  userStats: {
    totalUtterances: 2, totalWords: 12, speakingTimeMs: 8000,
    wordsPerMinute: 90, paceLabel: "TOO_SLOW", avgThinkingDelayMs: 2200
  }
}));

sessionStorage.setItem("interviewFeedback", JSON.stringify({
  recap: "整场面试由 ProfA 主持开场，候选人快速进入自我介绍后，ProfB 追问了具体例子。候选人能给出项目，但被同辈考生 Alice 质疑同质化时未能有力回应。",
  overallPerformance: {
    score: 72,
    summary: "整体表现稳健，内容真实，但缺少差异化与情境化的深入展开。",
    strengths: ["背景清晰", "项目真实", "语速稳定"],
    weaknesses: ["回答过于简短", "面对追问缺少例证", "未主动突出差异化"]
  },
  languagePerformance: {
    language: "用词准确但偏简单，未充分展示专业术语。",
    pace: "约 90 WPM，偏慢，可能让面试官觉得犹豫。",
    rhythm: "思考延迟平均 2.2 秒，属合理范围，但回答本身长度偏短。",
    fillerWords: "未发现明显口头禅"
  },
  improvements: [
    {
      stage: "开场自我介绍",
      atMs: 4500,
      trigger: "ProfA: Tell me about yourself.",
      yourReply: "Sure, I am a senior majoring in CS.",
      betterApproach: "用 30 秒结构化介绍：身份 → 一个亮眼成就 → 申请动机。比如 'I'm a CS senior whose work on a recommender system was deployed to 5k users, which is why I'm applying to your AI program.'",
      rationale: "开场决定了考官的预期。先抛锚点能让后续问题都围绕你的优势展开。"
    },
    {
      stage: "项目深挖",
      atMs: 12300,
      trigger: "ProfB: Can you give a concrete example?",
      yourReply: "Yes, I built a recommender system.",
      betterApproach: "用 STAR 框架：背景（why）+ 你的具体动作 + 量化结果 + 个人反思。把 '我做了' 变成 '我面对 X 挑战时，做了 Y，得到了 Z'。",
      rationale: "教授追问 concrete example 时是在判断你是否真正经历过，量化和细节是唯一让回答可信的方式。"
    },
    {
      stage: "群面冲突",
      atMs: 18000,
      trigger: "Alice: But that's a common project, right?",
      yourReply: "（未回应）",
      betterApproach: "正面接招：'Common, yes — but the bottleneck was X, and my contribution was solving it by Y, which is what makes it non-trivial.' 然后顺势把同伴的质疑转化为展示深度的机会。",
      rationale: "群面里被质疑不回应等于默认。把质疑当机会反而比顺利更能拿分。"
    }
  ]
}));
location.reload();
```

---

## 7. 与后端的契约提醒（升级 schema 时同步改这几处）

| 改了什么 | 同步改的地方 |
|---|---|
| `TranscriptEntry` 字段 | `TranscriptService.snapshotAsJson` 序列化逻辑 + 本文档第 2 节 + `EndPage.renderTranscript` |
| `Feedback` 输出形状 | `FeedbackService.buildSystemPrompt` + `FeedbackService.fallback` + 本文档第 3 节 + `EndPage.renderFeedback` |
| WebSocket 帧前缀 `[TRANSCRIPT]:` / `[FEEDBACK]:` | `AudioHandler.sendTranscript` / `triggerFeedbackAsync` + `zoomMeetingMockPage` 的 `socket.onmessage` |

**任何一项改动都必须同时更新这三方，否则 EndPage 会静默渲染空白或报错。**

## Why

打标签仍然完全靠人工看视频判断。已有的"作者高频标签"预勾选（`author_tag_frequency` 缓存表）只能覆盖"这个作者以前打过什么标签"，对新作者、或作者本身标签历史很少的情况完全帮不上忙——本质是纯统计，不看视频内容本身。给标签编辑弹窗接入一个基于视觉的 LLM，用视频封面/关键帧 + 文案 + 作者历史高频标签 + 现有标签词表去生成建议，能把"减少手动打标签"这条主线从"复用作者自己的历史"扩展到"看得懂新内容"，覆盖面更广。同时把用户对建议的采纳/修改结果记下来，攒够样例后作为 few-shot 示例喂回后续 prompt——不训练模型，靠 prompt 里累积的真实样例换取"越用越准"的效果，实现成本可控。

## What Changes

- 新增一个 LLM 调用层，仿照现有 `api/` 包的 Retrofit/OkHttp 组织方式，对接一个支持视觉输入的 LLM 提供方（用户自备 API Key，设置页配置，默认关闭/需要显式开启——涉及把视频封面/关键帧、文案发给第三方，不能默认开启）。
- 单条记录标签编辑弹窗（`TagEditDialogFragment`）新增「AI 建议」按钮：点击后取该视频封面 + 用 `MediaMetadataRetriever`（复用 `MediaOrientationProbe` 已经在用的同一套 API）从本地已下载的 mp4 抽 2-4 张关键帧，连同 `desc`、该作者的高频标签（`VideoTagRepository.getHighFrequencyTagsForAuthor`）、完整标签词表（`getAvailableTags`）一起发给 LLM，只允许模型从现有标签词表里选（不允许模型自造新标签名），返回结果作为预勾选灌进已有的 `TagCheckGrid`/`rememberCheckedTags` 机制，用户确认/调整后照常走 `setTagsAsUserEdit` 写库——这一步和现在的手动打标签是同一条写入路径，AI 只提供预勾选建议，不直接落库。
- 记录每次「AI 建议 → 用户最终确认」的对照（建议了什么、最终保存了什么），新增一张 Room 表持久化。
- Prompt 组装时从这份记录里抽一小批最近样例（同作者优先，不足则全局样例补齐）作为 few-shot 示例一并发送——这是"经验积累"的具体实现，不涉及任何模型训练/微调。
- 批量打标签弹窗（`BatchTagDialogFragment`）本次**不接入** AI 建议：多选场景一次要分析多条视频、成本和延迟都线性放大，且多选记录可能来自不同视频内容差异很大，建议先在单条场景验证有效后再决定要不要扩展。
- 不做"下载完自动触发"：所有 LLM 调用都由用户显式点击「AI 建议」触发，不在后台/下载完成时自动跑（成本 + 隐私）。

## Capabilities

### New Capabilities
- `ai-tag-suggestions`：标签编辑弹窗的 AI 建议标签能力——包括触发方式、输入数据的组装（封面/关键帧/文案/作者历史/标签词表）、LLM 调用与结果解析（只能返回现有标签词表内的标签）、建议结果如何进入现有预勾选 UI、用户确认后如何记录反馈样例、以及反馈样例如何被后续调用当作 few-shot 上下文使用。

### Modified Capabilities
（无——不改动任何既有 spec 描述的行为；批量打标签弹窗、高频标签快捷筛选块等既有功能均不受影响。）

## Impact

- **新增依赖面**：一个 LLM 供应商的网络调用（HTTP 层复用项目已有的 OkHttp/Retrofit/Gson，无需引入新网络库）。需要用户自备 API Key，设置页新增配置项与开关。
- **新增本地存储**：一张 Room 表记录「建议 vs 确认」样例（`AppDatabase` 版本号 +1，需要显式迁移，遵循项目现有的迁移规范，不依赖 `fallbackToDestructiveMigration`）。
- **改动文件面**（后续 design/tasks 阶段细化，这里只列受影响的既有模块）：
  - `dialog/TagEditDialogFragment.kt`（新增「AI 建议」入口与加载态）
  - `data/VideoTagRepository.kt` 或新增专门的 Repository（读作者高频标签、写反馈样例）
  - `config/AppSettings.kt`（API Key、功能开关等运行时偏好）
  - `data/db/AppDatabase.kt`（新表 + 迁移）
  - 新增 `llm/` 包（网络客户端、请求/响应模型、prompt 组装）
- **隐私影响**：视频封面/关键帧与文案会被发送给第三方 LLM 服务，仅在用户显式开启并点击「AI 建议」时发生，不涉及批量/自动场景；需要在设置页与 CLAUDE.md 里明确记录这一点（参考项目现有"日志不打印 Cookie/msToken"这类隐私敏感信息的处理基调）。
- **成本影响**：调用产生的费用记在用户自己的 API Key 账下，项目不代付、不做用量限制之外的计费逻辑。
- **不影响**：批量打标签弹窗、高频标签快捷筛选块、`author_tag_frequency` 缓存表的既有读写逻辑——本次只新增一个读取入口（读该作者的高频标签作为 prompt 输入），不改动其写入/重算路径。

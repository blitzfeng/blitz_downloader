# 跨设备继续开发（Cursor）

本目录用于在**另一台电脑**上延续本仓库的批量下载（F2 思路）与相关改造。

## 主计划文件位置

| 文件 | 说明 |
|------|------|
| [.cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md](plans/f2-style-batch-prereqs_d02563bb.plan.md) | **单一事实来源**：正文为 Kotlin 模仿 F2 的前置条件与架构说明；**文件头部 YAML** 中含 `todos` 列表与 `status`。 |

同步方式建议：将本仓库 **commit 并 push**，或打包整个项目目录（包含 `.cursor`）。

---

## Todo 与 Agent 接续提示

在 Cursor 中打开本仓库后，可将下方 **「Agent 提示」** 整段复制到 Agent 对话框，作为该任务的上下文（Cursor 会话内的 Todo 不会随仓库同步，以本文件 + plan  frontmatter 为准）。

**约定**：`status` 以 plan 文件 frontmatter 为准；若已修改代码但未更新 YAML，以实际代码/grep TODO 为准。

| Todo ID | Status（快照） | 简述 | Agent 提示（复制使用） |
|---------|----------------|------|------------------------|
| `scope-login` | completed | 批量场景：公开页 vs 登录 | `阅读 .cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md 第1节，确认 App 当前列表场景是否仍需仅公开或已支持登录；若需补充，列出需改的界面与存储。` |
| `id-parse` | completed | 链接解析层 | `检查 UrlParser/短链展开与 aweme_id、sec_user_id、mix_id 提取是否与 plan 第2节一致；补充缺失的 URL 形态与单元测试思路。` |
| `cookie-bootstrap` | completed | Cookie 来源 | `核对 DouyinCookieStore/WebView 预热与 Cookie 注入路径；文档化用户操作步骤与失效重试。` |
| `signing` | completed | X-Bogus / a_bogus | `核对签名实现与 UA 绑定、与 F2 算法版本差异；记录升级签名时的修改点。` |
| `todo-1774228790025-wm1fjn4ai` | completed | WebView 抖音首页 PC | `确认列表/登录页 WebView UA 为桌面端；若有混合场景列出所有改 userAgent 的位置。` |
| `todo-1774229150728-k3asalfct` | completed | 登录后 Cookie toast | `检查登录检测与 Cookie 提示逻辑；避免泄露敏感 Cookie 到日志。` |
| `list-api` | completed | 列表接口+翻页+设置页 | `对照 F2 列表接口与当前 Kotlin 分页/cursor；核对设置页 max_tasks、page_counts、timeout 默认值与文件名「用户名+desc」规则。` |
| `download-queue` | completed | 批量下载队列 | `检查批量下载并发、重试间隔、保存路径 Download/bDouyin 与失败重试是否与 F2 思路一致。` |
| `todo-1773993115022-vug7ep086` | completed | 代码内 TODO 盘点 | `在仓库内 rg "TODO" app/，汇总清单并区分已实现/待办。` |
| `todo-1774236805111-v38fte3d7` | completed | WebView 独立页+Cookie 缓存 | `梳理页面 A 与列表页 Cookie 共享、导航栏 URL、从剪贴板写入缓存的流程；画简短数据流。` |
| `todo-1774345874390-c3xf6rv8b` | in_progress | 接口返回 20 条只显示十几条 | `排查列表分页/去重/过滤逻辑：对比接口返回条数与 UI 条数；打印或断点 mapper 与 RecyclerView submitList；修复过滤过严或空项被丢弃问题。` |
| `todo-1774417152515-h6m1fww5p` | completed | 已下载数据库+角标 | `核对 Room 表字段、按 aweme_id 匹配列表项、已下载不可选与角标 UI。` |
| `todo-1774576151581-ysu5crwz7` | completed | UI 美化滚动与 FAB | `确认 Coordinator/NestedScroll 或单滚动父布局；FAB 固定右下角。` |
| `todo-1774579808236-mbzedhy8j` | completed | MainActivity ActionBar 管理 | `核对「管理」菜单入口与导航。` |
| `todo-1774583783274-o4qqdqgva` | completed | 图集/图片下载 | `对照 F2 与 photos.json；确认图集分支下载与命名。` |
| `todo-1774680538308-szauz5vxu` | completed | 数据库扩展 desc/likeType/collection_type | `核对迁移脚本与写入路径；列表展示是否用 collection_type。` |

---

## 另台电脑上的推荐步骤

1. **Clone / 解压** 本仓库（确保含 `.cursor`）。
2. 用 Cursor 打开根目录 `BlitzDownloader`。
3. 打开 [.cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md](plans/f2-style-batch-prereqs_d02563bb.plan.md)，看正文架构与 YAML 里 `todos`。
4. 优先处理 `status: in_progress` 的项（当前快照为 `todo-1774345874390-c3xf6rv8b`），把上表对应 **Agent 提示** 贴给 Agent。
5. 完成后更新 plan 文件 frontmatter 中对应 todo 的 `status`，并 commit。

---

## Cursor「Agent」说明

- **Agent 信息**在本仓库中体现为：每个 todo 一行 **可复制的提示词**，用于在新会话中恢复上下文（Cursor 不会在 repo 里保存 Agent 会话 ID）。
- 若你使用 **Cursor Rules**，可把「批量下载必读：先读 .cursor/CONTINUATION.md」写入 `.cursor/rules`（可选，需你本地添加）。

---

## 许可证与合规

实现参考 [F2](https://github.com/Johnserf-Seed/f2) 时遵守其 **Apache-2.0** 与版权要求；批量抓取须符合平台服务条款与当地法律。

---

*本文件由工具生成/维护，用于跨设备同步；与 plan 正文冲突时以 plan + 代码为准。*

---

## 机器可读：Todo ID 与 Agent 提示（JSON）

可将下列内容另存为 `todos-handoff.json`（若你需要 JSON 文件，在 **Agent 模式**下创建即可）。

```json
{
  "planFile": ".cursor/plans/f2-style-batch-prereqs_d02563bb.plan.md",
  "reference": "https://github.com/Johnserf-Seed/f2",
  "continuationDoc": ".cursor/CONTINUATION.md",
  "todos": [
    {"id": "scope-login", "agentPrompt": "阅读 plan 第1节，确认列表场景与 Cookie 方案。"},
    {"id": "id-parse", "agentPrompt": "检查链接解析层：短链、video/mix/user → aweme_id / sec_user_id / mix_id。"},
    {"id": "cookie-bootstrap", "agentPrompt": "核对 Cookie/WebView 预热与持久化及失效重试。"},
    {"id": "signing", "agentPrompt": "核对 X-Bogus/a_bogus 与 UA 绑定及与 F2 版本差异。"},
    {"id": "todo-1774228790025-wm1fjn4ai", "agentPrompt": "确认 WebView 桌面 UA 与抖音首页加载。"},
    {"id": "todo-1774229150728-k3asalfct", "agentPrompt": "检查登录后 Cookie 检测与 Toast，避免日志泄露。"},
    {"id": "list-api", "agentPrompt": "对照 F2 列表接口、翻页、设置页与文件名规则。"},
    {"id": "download-queue", "agentPrompt": "检查批量下载并发、重试、路径 Download/bDouyin。"},
    {"id": "todo-1773993115022-vug7ep086", "agentPrompt": "rg TODO 于 app/，列出清单。"},
    {"id": "todo-1774236805111-v38fte3d7", "agentPrompt": "梳理独立 WebView 页与 Cookie 缓存。"},
    {"id": "todo-1774345874390-c3xf6rv8b", "agentPrompt": "排查接口 20 条只显示部分：分页、去重、mapper。"},
    {"id": "todo-1774417152515-h6m1fww5p", "agentPrompt": "核对已下载数据库与角标。"},
    {"id": "todo-1774576151581-ysu5crwz7", "agentPrompt": "UI 滚动与 Material FAB。"},
    {"id": "todo-1774579808236-mbzedhy8j", "agentPrompt": "MainActivity ActionBar 管理菜单。"},
    {"id": "todo-1774583783274-o4qqdqgva", "agentPrompt": "图集/图片下载与 photos.json。"},
    {"id": "todo-1774680538308-szauz5vxu", "agentPrompt": "数据库 desc/likeType/collection_type 迁移与写入。"}
  ]
}
```

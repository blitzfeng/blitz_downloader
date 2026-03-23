# 抖音批量下载 - API 实现方案

## 架构设计

基于 **f2 原理**，使用纯 API 调用方式（而非 WebView 拦截），核心是模拟抖音 Web API 请求。

```
┌─────────────────────────────────────────────────────┐
│  ListDownloadFragment (UI 层)                       │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────▼───────────────────────────────────┐
│  DouyinParser (业务逻辑层)                          │
│  - fetchUserVideos()  获取视频列表                  │
│  - fetchVideoDetail() 获取视频详情                  │
│  - extractDownloadUrl() 提取下载链接                │
└─────────────────┬───────────────────────────────────┘
                  │
       ┌──────────┼──────────┐
       │                     │
┌──────▼──────┐      ┌──────▼──────────────────────────┐
│ DouyinApi   │      │ DouyinSignatureGenerator        │
│ Client      │      │ (JS 签名生成)                   │
│             │      │                                  │
│ - OkHttp    │◄─────┤ - generateXBogus()              │
│ - Retrofit  │      │ - generateSignature()            │
│ - Cookie    │      │ - WebView 执行 JS                │
└─────────────┘      └──────────────────────────────────┘
```

## 已实现的文件

### 1. **DouyinApiService.kt**
Retrofit 接口定义，对应抖音的 HTTP API：
- `getUserVideos()` - 获取用户主页视频列表
- `getVideoDetail()` - 获取单个视频详情

### 2. **DouyinApiModels.kt**
数据模型，对应 API 返回的 JSON 结构：
- `DouyinUserVideosResponse` - 视频列表响应
- `AwemeItem` - 视频数据
- `Video` / `PlayAddr` - 视频下载地址

### 3. **DouyinApiClient.kt**
HTTP 客户端，负责：
- 构建 OkHttp + Retrofit
- 添加请求头（User-Agent、Referer、Cookie）
- 日志拦截

### 4. **DouyinParser.kt**
业务逻辑层，封装 API 调用：
- `fetchUserVideos()` - 分页获取所有视频
- `fetchVideoDetail()` - 获取详情
- `extractDownloadUrl()` - 提取下载链接

### 5. **DouyinSignatureGenerator.kt**
签名生成器（核心难点）：
- 使用 WebView 执行抖音的 JS 算法
- 生成 `X-Bogus` 参数（防爬签名）
- 对应 f2 的 `execjs`/`playwright` 功能

## 实施步骤

### Phase 1：基础验证（当前阶段）
✅ 已创建 API 框架代码
⬜ 测试能否直接调用 API（无签名）
⬜ 从 WebView 提取 Cookie 注入到 API Client

```kotlin
// 在 ListDownloadFragment 的 WebView 加载完成后
webView.evaluateJavascript("document.cookie") { cookie ->
    DouyinApiClient.globalCookie = cookie.trim('"')
}
```

### Phase 2：签名算法集成
⬜ 从抖音页面提取签名 JS 或参考 f2 的实现
⬜ 注入到 `DouyinSignatureGenerator`
⬜ 测试生成的 `X-Bogus` 是否有效

### Phase 3：完整功能
⬜ 实现短链接解析（sec_user_id 提取）
⬜ 实现分页加载
⬜ 集成到 UI（显示视频列表）
⬜ 批量下载功能

## 核心 API 说明

### 1. 用户视频列表 API
```
GET https://www.douyin.com/aweme/v1/web/aweme/post/
参数：
  - sec_user_id: 用户的唯一 ID
  - max_cursor: 分页游标
  - count: 每页数量（建议 18）
  - X-Bogus: 签名参数（关键）
  - msToken: 从 Cookie 获取
```

返回示例：
```json
{
  "status_code": 0,
  "aweme_list": [
    {
      "aweme_id": "7xxx",
      "desc": "视频标题",
      "video": {
        "play_addr": {
          "url_list": ["https://xxx.mp4"]
        }
      }
    }
  ],
  "has_more": 1,
  "max_cursor": 1234567890
}
```

## 与 f2 的对比

| 功能 | f2 (Python) | 本项目 (Kotlin) |
|------|-------------|-----------------|
| API 调用 | aiohttp | OkHttp + Retrofit |
| JS 执行 | execjs/playwright | WebView |
| 签名生成 | 独立 JS 文件 | WebView 注入 |
| Cookie 管理 | aiohttp cookies | CookieManager |
| 异步处理 | asyncio | Coroutines |

## 关键难点

### 1. **X-Bogus 签名算法**
- 抖音用于防爬的核心参数
- 算法会定期更新（混淆、加密）
- **解决方案**：
  - 从抖音官网提取最新 JS
  - 或参考 f2 的算法文件
  - 或使用 AST 工具解混淆

### 2. **Cookie 时效性**
- msToken、webid 等参数有过期时间
- **解决方案**：
  - 监听 WebView 的 Cookie 变化
  - 失效时重新加载页面获取新 Cookie

### 3. **请求频率限制**
- 抖音有反爬限制，请求过快会被封
- **解决方案**：
  - 每次请求间隔 1-2 秒
  - 使用真实 Cookie（登录后更稳定）

## 使用示例

```kotlin
// 1. 初始化签名生成器
val signatureGenerator = DouyinSignatureGenerator(context)
signatureGenerator.initialize()

// 2. 设置 Cookie（从 WebView 获取）
DouyinApiClient.globalCookie = "your_cookie_here"
DouyinApiClient.msToken = "your_mstoken_here"

// 3. 解析视频
val parser = DouyinParser()
val videos = parser.fetchUserVideos(secUserId = "MS4wLjABAAAA...")

// 4. 提取下载链接
videos.forEach { video ->
    val downloadUrl = parser.extractDownloadUrl(video)
    println("标题: ${video.desc}")
    println("下载: $downloadUrl")
}
```

## 下一步行动

1. **测试 API 可用性**：先不加签名，看是否能返回数据
2. **提取签名 JS**：从抖音页面或 f2 项目获取算法
3. **集成到 UI**：在 `ListDownloadFragment` 中调用 `DouyinParser`

## 参考资源

- f2 项目：https://github.com/Johnserf-Seed/f2
- f2 文档：https://f2.wiki/guide/apps/douyin/overview
- 抖音 API 分析：需要抓包工具（Charles/Fiddler）


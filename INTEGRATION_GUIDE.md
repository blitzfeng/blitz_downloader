# 集成指南：从 WebView 迁移到 API 调用

## 当前状态

你已经有了完整的 API 框架代码：

✅ `DouyinApiService.kt` - Retrofit API 接口
✅ `DouyinApiModels.kt` - 数据模型
✅ `DouyinApiClient.kt` - HTTP 客户端
✅ `DouyinParser.kt` - 业务逻辑层
✅ `DouyinSignatureGenerator.kt` - 签名生成器

## 集成步骤

### 方案 A：混合方案（推荐首先尝试）

**原理**：使用 WebView 获取 Cookie，然后用纯 API 调用获取视频列表

#### 步骤 1：从 WebView 提取 Cookie

在 `ListDownloadFragment.kt` 的 `onPageFinished` 中添加：

```kotlin
override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    
    // 提取 Cookie
    view?.evaluateJavascript("document.cookie") { cookie ->
        val cleaned = cookie.trim('"')
        DouyinApiClient.globalCookie = cleaned
        Log.d("ListDownload", "Cookie: $cleaned")
        
        // 提取 msToken
        val msTokenRegex = """msToken=([^;]+)""".toRegex()
        msTokenRegex.find(cleaned)?.let {
            DouyinApiClient.msToken = it.groupValues[1]
        }
    }
    
    // 如果是用户主页，提取 sec_user_id
    if (url?.contains("user/") == true) {
        extractSecUserIdAndFetchVideos(url)
    }
}
```

#### 步骤 2：提取 sec_user_id 并调用 API

```kotlin
private fun extractSecUserIdAndFetchVideos(url: String) {
    // 从 URL 提取 sec_user_id
    // 例如：https://www.douyin.com/user/MS4wLjABAAAA...
    val secUserIdRegex = """/user/([^/?]+)""".toRegex()
    val secUserId = secUserIdRegex.find(url)?.groupValues?.get(1)
    
    if (secUserId != null) {
        lifecycleScope.launch {
            fetchVideosViaApi(secUserId)
        }
    }
}

private suspend fun fetchVideosViaApi(secUserId: String) {
    try {
        val parser = DouyinParser()
        val videos = parser.fetchUserVideos(secUserId, maxCount = 50)
        
        // 转换为 UI 模型
        val uiModels = videos.map { aweme ->
            VideoItemUiModel(
                id = aweme.awemeId,
                coverUrl = parser.extractCoverUrl(aweme) ?: "",
                title = aweme.desc ?: "无标题",
                downloadUrl = parser.extractDownloadUrl(aweme),
                isSelected = false
            )
        }
        
        // 更新 UI
        withContext(Dispatchers.Main) {
            videoItems.clear()
            videoItems.addAll(uiModels)
            videoAdapter.submitList(videoItems.toList())
            Toast.makeText(requireContext(), "成功获取 ${videos.size} 个视频", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("ListDownload", "API 调用失败", e)
        withContext(Dispatchers.Main) {
            Toast.makeText(requireContext(), "解析失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
```

### 方案 B：纯 WebView 方案（备用）

如果 API 调用失败（签名问题），可以回退到 WebView 提取：

```kotlin
override fun onPageFinished(view: WebView?, url: String?) {
    super.onPageFinished(view, url)
    
    // 执行 JS 提取页面中的视频数据
    view?.evaluateJavascript("""
        (function() {
            try {
                // 从页面的 __RENDER_DATA__ 或其他全局变量提取数据
                if (window._SSR_HYDRATED_DATA) {
                    return JSON.stringify(window._SSR_HYDRATED_DATA);
                }
                return null;
            } catch(e) {
                return 'error:' + e.toString();
            }
        })()
    """) { json ->
        if (!json.isNullOrBlank() && !json.contains("error")) {
            parseVideosFromJson(json)
        }
    }
}

private fun parseVideosFromJson(json: String) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            // 使用 Gson 解析 JSON
            val data = Gson().fromJson(json.trim('"'), JsonObject::class.java)
            // 根据实际结构提取视频列表
            // ...
        } catch (e: Exception) {
            Log.e("ListDownload", "JSON 解析失败", e)
        }
    }
}
```

## 关键问题解决

### 问题 1：签名生成失败

**现象**：API 返回 403 或空数据

**解决方案**：
1. 检查 Cookie 是否有效
2. 使用 `DouyinSignatureGenerator` 生成 X-Bogus
3. 或者直接使用 WebView 方案

### 问题 2：短链接处理

**现象**：用户输入 `v.douyin.com/xxx` 短链接

**解决方案**：
```kotlin
private suspend fun resolveShortUrl(shortUrl: String): String? {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .followRedirects(false) // 不自动跟随重定向
                .build()
            val request = Request.Builder().url(shortUrl).build()
            val response = client.newCall(request).execute()
            response.header("Location") // 真实 URL
        } catch (e: Exception) {
            null
        }
    }
}
```

### 问题 3：无法获取视频列表

**检查清单**：
- [ ] Cookie 是否正确提取？
- [ ] sec_user_id 是否正确？
- [ ] 网络是否可达？
- [ ] API 响应状态码是多少？

**调试方法**：
```kotlin
// 在 DouyinParser 中添加详细日志
Log.d(TAG, "请求 URL: ${request.url}")
Log.d(TAG, "Cookie: ${DouyinApiClient.globalCookie}")
Log.d(TAG, "响应码: ${response.code}")
Log.d(TAG, "响应体: ${response.body?.string()}")
```

## 测试步骤

### 1. 基础测试
```kotlin
// 在 btnParse 点击事件中
view.findViewById<Button>(R.id.btnParse).setOnClickListener {
    val testUrl = "https://www.douyin.com/user/MS4wLjABAAAA..." // 测试用户主页
    loadUrl(testUrl)
}
```

### 2. 验证 Cookie 提取
加载页面后，检查 Logcat 是否输出 Cookie

### 3. 验证 API 调用
手动调用 API 测试：
```kotlin
lifecycleScope.launch {
    val parser = DouyinParser()
    val videos = parser.fetchUserVideos("MS4wLjABAAAA...")
    Log.d("Test", "获取到 ${videos.size} 个视频")
}
```

## 下一步计划

1. **Phase 1**（当前）：实现 Cookie 提取 + API 调用
2. **Phase 2**：集成签名生成器
3. **Phase 3**：优化 UI 和下载功能

## 完整示例

参考 `API_IMPLEMENTATION.md` 中的完整代码示例。

## 注意事项

⚠️ **签名算法更新**：抖音会定期更新签名算法，需要持续维护
⚠️ **请求频率**：避免频繁请求导致被限流
⚠️ **Cookie 时效**：Cookie 可能过期，需要重新获取


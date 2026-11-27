package com.blitz.downloader.llm

/** LLM 业务接口。Gemini Developer API 与 Vertex AI 复用 GeminiProvider 的协议实现。 */
interface LlmProvider {

    /** 供应商标识，写入 `video_ai_analysis.provider`，如 `"gemini"`。 */
    val providerId: String

    /** 具体模型版本号，写入 `video_ai_analysis.model`，供比较模型升级前后的准确率。 */
    val modelId: String

    /** 发起一次 AI 建议标签请求。失败（网络异常、解析失败、幻觉过滤后为空等）不抛异常，走 [Result.failure]。 */
    suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse>

    suspend fun learnPreference(request: PreferenceLearningRequest): Result<PreferenceLearningResponse> =
        Result.failure(UnsupportedOperationException("服务不支持偏好学习"))

    /** 生成一版个人偏好摘要（纯文本输入输出，不带图片）。 */
    suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String>

    /**
     * 轻量连通性测试：只验证「API Key 已配置 + 网络可达 + 模型 id 有效」，不带图片、不落库、
     * 不计入建议/反馈闭环。成功时返回一句人可读的确认信息（含模型名），失败时 [Result.failure]
     * 携带可展示给用户的错误信息（HTTP 状态码/异常消息）。
     */
    suspend fun testConnection(): Result<String>
}

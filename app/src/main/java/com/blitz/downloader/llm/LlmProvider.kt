package com.blitz.downloader.llm

/**
 * LLM 供应商的可插拔接口。V1 只有一个真实实现（[com.blitz.downloader.llm.providers.GeminiProvider]）——
 * 供应商已经因为现实原因（Claude/OpenAI 支付渠道受阻）实际更换过一次，保留这层薄接口隔离
 * "业务逻辑"与"具体厂商协议细节"，不为假设的第三个供应商预先设计任何东西。
 */
interface LlmProvider {

    /** 供应商标识，写入 `video_ai_analysis.provider`，如 `"gemini"`。 */
    val providerId: String

    /** 具体模型版本号，写入 `video_ai_analysis.model`，供比较模型升级前后的准确率。 */
    val modelId: String

    /** 发起一次 AI 建议标签请求。失败（网络异常、解析失败、幻觉过滤后为空等）不抛异常，走 [Result.failure]。 */
    suspend fun generateTagSuggestion(request: TagSuggestionRequest): Result<TagSuggestionResponse>

    /** 生成一版个人偏好摘要（纯文本输入输出，不带图片）。 */
    suspend fun summarizePreference(request: PreferenceSummaryRequest): Result<String>

    /**
     * 轻量连通性测试：只验证「API Key 已配置 + 网络可达 + 模型 id 有效」，不带图片、不落库、
     * 不计入建议/反馈闭环。成功时返回一句人可读的确认信息（含模型名），失败时 [Result.failure]
     * 携带可展示给用户的错误信息（HTTP 状态码/异常消息）。
     */
    suspend fun testConnection(): Result<String>
}

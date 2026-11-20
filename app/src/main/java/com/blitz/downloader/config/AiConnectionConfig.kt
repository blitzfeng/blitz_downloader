package com.blitz.downloader.config

/** 服务标识保持稳定，既用于偏好存储，也用于分析记录。 */
enum class AiService(val id: String, val displayName: String) {
    GEMINI("gemini", "Gemini Developer API"),
    VERTEX_AI("vertex_ai", "Vertex AI"),
    OPENAI("openai", "OpenAI");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI

        fun priority(saved: String?, legacy: String?): List<AiService> {
            val preferred = saved?.split(',')?.mapNotNull { id -> entries.firstOrNull { it.id == id } }
                ?: listOf(fromId(legacy))
            return (preferred + entries).distinct()
        }
    }
}

/** 单次操作的配置快照；不使用 data class，避免自动 toString 暴露密钥。 */
class AiConnectionConfig(
    val service: AiService,
    val apiKey: String,
    val model: String = if (service == AiService.OPENAI) DEFAULT_OPENAI_MODEL else DEFAULT_MODEL,
) {
    val validationError: String?
        get() = when {
            apiKey.isBlank() -> "${service.displayName} API Key 未配置"
            apiKey.any { it.code !in 33..126 } -> "API Key 包含空白或非法字符"
            !isSupportedModel(model, service) -> "请填写支持图片分析的模型 ID"
            else -> null
        }

    val generateContentUrl: String
        get() = when (service) {
            AiService.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            AiService.VERTEX_AI -> "https://aiplatform.googleapis.com/v1/publishers/google/models/$model:generateContent"
            AiService.OPENAI -> "https://api.openai.com/v1/responses"
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_OPENAI_MODEL = "gpt-6-astra"
        fun isSupportedModel(model: String, service: AiService = AiService.GEMINI): Boolean =
            when (service) {
                AiService.OPENAI -> Regex("gpt-[a-zA-Z0-9][.a-zA-Z0-9_-]*").matches(model)
                else -> Regex("gemini-3[.a-zA-Z0-9_-]*").matches(model)
            }
    }
}

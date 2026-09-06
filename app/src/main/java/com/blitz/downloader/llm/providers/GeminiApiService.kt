package com.blitz.downloader.llm.providers

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface GeminiApiService {

    /**
     * 用 `retrofit2.Response<T>` 包一层而不是直接返回 body：需要在非 2xx 时读 [Response.errorBody]
     * 拼进失败信息里，而不是让 Retrofit 直接抛不带上下文的 `HttpException`。
     */
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body body: GeminiGenerateContentRequest,
    ): Response<GeminiGenerateContentResponse>
}

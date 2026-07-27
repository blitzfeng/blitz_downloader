package com.blitz.downloader.api

/**
 * 表示「登录态 / Cookie 很可能已失效」的接口失败，用于把这类错误与普通网络/解析错误区分开，
 * 让 UI 层弹出「重新登录 / 同步 Cookie」引导，而不是只丢一条 Toast。
 *
 * 触发场景（见 [DouyinListApi]）：
 * - HTTP 401 / 403 / 419（鉴权失败 / 需要验证）。
 * - HTTP 200 但响应体为空——本工程里最隐蔽的失效表现（风控空包 / Cookie 未登录或过期）。
 *
 * @param httpCode 若由 HTTP 状态触发则带上状态码；空包场景为 null。
 */
class DouyinAuthException(
    message: String,
    val httpCode: Int? = null,
) : Exception(message)

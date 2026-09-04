package com.blitz.downloader.config

/**
 * 视频下载画质偏好（设置页可调，默认 [HIGHEST]）。
 *
 * 作用于 [com.blitz.downloader.api.AwemeMapper] 从 `Video.bitRate[]` 挑选下载直链的逻辑：
 * [targetResolution] 为 null 时沿用原有「尽量选最高」策略（1080 优先，否则整体最高，
 * 同分辨率再比码率）；非 null 时优先选**不超过该分辨率的最高档**，一档都没有则退而选
 * 现有最低档（画质封顶而非画质下限）。
 *
 * 分辨率档位来自真实抓包样本（`apiData/like.json`）观察到的 `gear_name`（如 `normal_1080_0`）,
 * 只有登录态列表接口（喜欢/收藏夹）才会下发完整梯队；`post`（主页作品）等接口常只有单一档位，
 * 此时该设置形同虚设（没有更多档可选，仍返回仅有的一档）。
 */
enum class VideoQualityPreference(val targetResolution: Int?) {
    HIGHEST(null),
    P1080(1080),
    P720(720),
    P540(540),
}

# Design

## Context
现有 GeminiProvider 持有共享客户端，设置和批量配置检查只读取 Gemini Key。复用请求结构与响应解析器。

## Goals / Non-Goals
目标：新增独立 Vertex AI 配置、服务选择与一致路由。不引入服务账号 JSON、OAuth、数据库迁移或远端代理。

## Decisions
- 新增不可变 AI 连接配置，repository 每次操作捕获一次快照并绑定 provider；共享长生命周期客户端，避免逐请求创建连接池。
- 保持 Gemini 默认与既有 Key；Vertex Key、模型单独存储，默认 gemini-3.8-flash，与现有分析兼容。设置提供 Compose Material 3 配置弹窗和当前服务选择。
- 授权 Key 走 https://aiplatform.googleapis.com/v1/publishers/google/models/{model}:generateContent，x-goog-api-key 请求头；Gemini 继续 v1beta/models 路径。
- 保留结构化输出与证据图片策略；Vertex 请求使用支持的安全类别，不发送 已弃用的 CIVIC_INTEGRITY。
- Key 遵循现有本机偏好存储方式，日志和返回错误统一脱敏；取消协程不能被当作普通连接失败吞掉。

## Risks / Trade-offs
- 用户 Key 与模型权限需真实请求确认 → 无真实凭据时通过拦截器测试路由，交付明确告知尚未实测云端。
- 不同模型的 thinking/mediaResolution 支持不同 → Vertex 模型限定 Gemini 3 系列，UI 说明并校验。

## Migration Plan
默认仍为 Gemini，选择 Vertex 后生效，切回 Gemini 即可恢复。无需更改存量数据库。

## References
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/start?usertype=apikey
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/express-mode/api-reference

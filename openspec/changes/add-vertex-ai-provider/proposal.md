# Proposal

## Why
当前应用只连接 Gemini Developer API，用户已取得 Vertex AI 授权 API Key，需要独立配置并让测试连接和分析一起切换。

## What Changes
- 保留 Gemini，新增 Vertex AI 配置与服务选择，独立保存 Key 和 Vertex 模型。
- 测试连接、单条/批量分析、偏好摘要使用所选服务；默认保持 Gemini。
- 使用 Vertex AI API Key 全局接口，不要求项目 ID、区域或服务账号文件。

## Capabilities
### New Capabilities
无。
### Modified Capabilities
- `ai-tag-suggestions`: 新增 AI 服务配置与路由要求。

## Impact
影响 AppSettings、GeminiProvider、设置页、AI repository 与批量入口检查；复用 Retrofit/OkHttp 和结构化响应模型，无数据库迁移。

## Why

用户希望在自用 App 中使用 OpenAI，并为多种已配置的 AI 服务指定调用顺序。

## What Changes

- 增加 OpenAI API Key 和模型 ID 设置。
- 提供服务优先级设置，用于标签分析和偏好摘要。

## Capabilities

### Modified Capabilities

- `ai-tag-suggestions`：配置 OpenAI 并按已保存的优先级调用服务。

## Impact

影响设置页、AI 服务配置和标签分析请求。OpenAI 接入使用 Responses API。

## ADDED Requirements

### Requirement: 配置 OpenAI 和 AI 服务优先级
系统 SHALL 允许用户设置 OpenAI 的 API Key 和模型 ID，并调整多项已配置服务的调用顺序。

#### Scenario: 保存并按顺序调用
- **WHEN** 用户配置多个服务并保存优先级
- **THEN** 系统优先调用排序最靠前的有效服务，并在暂时不可用时尝试下一项

#### Scenario: 首选服务拒绝内容
- **WHEN** 首选服务因内容策略拒绝请求
- **THEN** 系统报告本次失败，不转发给其他服务

#### Scenario: 清空服务密钥
- **WHEN** 用户清空某服务的密钥
- **THEN** 后续请求跳过该服务，其他服务的相对顺序保持不变

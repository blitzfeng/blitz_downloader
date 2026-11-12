## ADDED Requirements

### Requirement: AI 服务独立配置与选择
系统 SHALL 提供 Gemini Developer API 和 Vertex AI 两种服务，独立保存密钥；已有安装默认继续使用 Gemini。Vertex AI SHALL 支持授权 API Key 与可编辑模型，保存后无需重启。取消编辑 SHALL 不修改配置。

#### Scenario: 配置并切换 Vertex AI
- **WHEN** 用户保存 Vertex AI Key、模型并选择 Vertex AI
- **THEN** 后续测试、单条及批量分析、偏好摘要使用 Vertex AI，Gemini Key 保持不变

#### Scenario: 缺少当前服务密钥
- **WHEN** 所选服务的 Key 为空，即使另一个服务已配置
- **THEN** 测试明确提示所选服务未配置，批量入口不启动，不自动回退或发送网络请求

#### Scenario: 重启与取消
- **WHEN** 用户保存配置后重启，随后打开编辑并取消
- **THEN** 已保存的服务、密钥与模型保持不变

### Requirement: 连接测试和分析路由一致
系统 SHALL 使用当前服务的端点、Key 和模型测试连接，成功显示服务及模型，失败显示可读原因；测试 SHALL 不写入标签分析记录。单次分析 SHALL 固定配置快照，使保存的服务与模型和请求一致。Key SHALL 不出现在 URL、日志或错误提示中。

#### Scenario: 测试当前服务
- **WHEN** 用户点击测试连接
- **THEN** 发起所选服务的轻量文本请求，成功显示服务和模型，HTTP 错误显示状态及原因

#### Scenario: 请求期间切换服务
- **WHEN** 分析请求进行中用户修改服务配置
- **THEN** 当前分析继续使用原配置并记录原服务和模型，下一次请求使用新配置

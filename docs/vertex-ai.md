# Vertex AI 配置

1. 安装新版 App，进入「设置」的 AI 建议标签区域。
2. 点击「Vertex AI 与服务选择」，将当前 AI 服务选为 Vertex AI。
3. 填写授权 API Key。模型默认 `gemini-3.8-flash`，也可填写有权限访问且支持图片分析的 Gemini 3 系列模型 ID。
4. 点击确定，再点击设置页「测试连接」。下方说明会显示当前服务和模型，成功提示也包含它们。
5. 开启「AI 建议标签」，使用单条 AI 建议或批量分析。

Gemini Developer API 与 Vertex AI 的 Key 分开保存。切回 Gemini 时，仍使用原 Gemini Key。空 Key 可以保存以重置配置，但当前服务没有 Key 时无法测试或启动批量分析。取消弹窗不会保存，旋转屏幕保留尚未确认的输入。

Vertex 使用授权 API Key 全局生成接口，无需项目 ID、区域或服务账号 JSON。Key 保存在 App 本机偏好中，不提交仓库；请求只通过认证头发送。已有在途分析固定使用发起时的配置，后续请求使用新配置。

## 测试连接的边界

连接测试只发送轻量文本请求，验证所选 Key、网络、模型及 thinking 配置，不上传图片、不产生标签分析记录。模型图像能力、结构化输出、配额及账号权限仍需用真实图片分析确认。

常见错误：403 检查 Key 绑定服务账号的权限、组织政策及 API 限制；404 检查模型 ID 与可用性；429 检查配额。测试失败不会切换到另一个服务。

## 本次验证

- Debug 构建及 JVM 回归测试；测试使用虚构密钥与本地拦截响应，不访问 Google。
- 当前没有连接的 Android 设备或可用模拟器，未执行设置页实机交互验证。
- 未持有用户真实 Key，因此未做真实 Vertex AI 连通性或图片分析验证；由用户填入 Key 后运行。

## 官方接口参考

- https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/start?usertype=apikey
- https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/express-mode/api-reference

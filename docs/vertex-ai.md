# Vertex AI 配置

1. 安装新版 App，进入「设置」的 AI 建议标签区域。
2. 点击「Vertex AI API Key」。
3. 填写授权 API Key。模型默认 `gemini-3.8-flash`，也可填写有权限访问且支持图片分析的 Gemini 3 系列模型 ID。
4. 点击确定。如果还配置了其他 AI 服务，可在「AI 服务优先级」中把 Vertex AI 移到第一位。点击「测试连接」，下方说明与成功提示会显示被测试的服务和模型。
5. 开启「AI 建议标签」，使用单条 AI 建议或批量分析。

Gemini Developer API、Vertex AI 和 OpenAI 各自保存 Key。清空并保存可以停用对应服务。取消弹窗不会保存改动。

Vertex 使用授权 API Key 全局生成接口，无需项目 ID、区域或服务账号 JSON。Key 保存在 App 本机偏好中，通过请求头发送。每条分析开始时读取服务顺序和配置。

## OpenAI 与服务优先级

在同一区域打开「OpenAI API Key」，输入自己的项目 Key 和支持图片及结构化输出的 GPT 模型 ID，默认是 `gpt-6-astra`。自用 App 将 Key 保存在私有偏好中并排除系统备份，通过 HTTPS 直连 OpenAI。发布给他人使用的版本应由后端保管密钥。

多个服务可通过「上移」「下移」排序。单条分析、批量分析及偏好摘要按优先级发起请求，网络异常、超时、限流或服务端故障时尝试下一项。分析可能把当前图片和参考案例发送到后续配置的服务。内容拦截和其他错误直接结束该次请求。测试连接只测试列表第一项。

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

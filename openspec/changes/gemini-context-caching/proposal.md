## Why

`ai-tag-suggestions` 每次调用 Gemini「AI 建议标签」时,请求体里固定不变的部分(通用指令文本 + 完整标签词表 JSON)都要按标准输入单价重新计费一次;标签词表会随用户使用越攒越大,这部分重复开销只会越来越贵。`CLAUDE.md` 已把"上下文缓存"记录为待办。

与用户就实现路径做了进一步核实后确认:显式缓存(`cachedContents` 资源的创建/管理)存在一个尚未证实的关键参数——`gemini-3.8-flash` 的显式缓存最小可缓存 token 门槛,一份 Google Cloud Enterprise 官方文档写的是 4096,另一份未经一手来源核实的说法称 Generative Language API 路由已调整到 32768;两者互相矛盾,且都可能因 Gemini 3 系列密集迭代而滞后于当前真实行为。在这个前提不明确的情况下直接实现显式缓存的完整生命周期管理(创建/续期/清理),风险是自己先按错误门槛写死代码、真机跑不通。

Gemini 同时提供**隐式缓存**:只要请求里跨调用不变的内容以稳定顺序作为前缀发送,服务端会自动识别并打折,不需要客户端管理任何缓存资源,也没有新的失败面。这部分零风险、零新增 API 面,可以立即做并观察是否已经生效。

因此本次变更按用户确认的顺序分两步走,**这次变更只交付第一步**:

1. **本次交付**:调整 prompt 结构让"通用指令 + 标签词表"这段稳定内容作为请求前缀,为隐式缓存创造命中条件;通过现有日志(`GeminiProvider.LoggingInterceptor` 已完整打印响应体)观察 `usageMetadata.cachedContentTokenCount` 是否出现、命中多少,不需要新增任何解析代码。
2. **后续独立变更(不在本次范围内)**:只有在真机验证显示隐式缓存命中不足以覆盖典型使用场景(例如用户点击间隔超过服务端隐式缓存的有效窗口)时,才评估是否上线显式缓存;届时门槛值(4096、32768,或其他)必须用一次真实的 `cachedContents` 创建请求现场验证后再定,不能照抄任何一份未经证实的文档结论。

## What Changes

- 调整 `GeminiProvider` 内 AI 建议标签的 prompt 构建顺序:把当前夹在指令说明和标签词表之间的 `desc`(逐视频文案)挪到标签词表之后,让"通用指令文本 + 完整标签词表 JSON"成为一段连续、跨请求内容一致的前缀,`desc`/作者历史先验/个人偏好摘要/few-shot 样例/图片等逐视频变化内容跟在这段前缀之后发送。
- 不新增任何 `cachedContents` 相关的创建/获取/更新/删除 API 调用,不新增缓存元数据存储——隐式缓存完全由 Gemini 服务端自动识别与计费,客户端无需感知、管理或持久化任何缓存状态。
- 信息内容与今天完全一致(不删减、不新增指令语义),只是发送顺序调整;`generateTagSuggestion` 的输入输出契约、失败处理、结果过滤逻辑均不变。

## Capabilities

(本次变更不改变任何用户可见行为——AI 建议标签的触发方式、输入内容语义、输出结果与失败提示均与变更前一致,只是发给 Gemini 的请求内容顺序调整以配合服务端隐式缓存,属于纯粹的后端成本优化。不新增/修改任何 spec 能力,详见 `.openspec.yaml` 的 `skip_specs: true`。)

## Impact

- `app/src/main/java/com/blitz/downloader/llm/providers/GeminiProvider.kt`:`buildTagSuggestionPrompt` 内部重排 `desc` 与标签词表的相对顺序。
- 不涉及 `GeminiApiService.kt`、`GeminiModels.kt`、`AppSettings.kt`、`TagSuggestionRequestBuilder.kt` 的字段/接口改动(此前版本的方案里这几处的显式缓存改动全部移出本次变更,推迟到后续独立变更)。
- 不引入新的第三方依赖或权限,不影响下载、导出、Room 数据库等其他链路。
- 验证方式是真机人工核对:通过 `LoggingInterceptor` 已有的完整请求/响应日志,观察响应 `usageMetadata` 里是否出现 `cachedContentTokenCount` 及其数值随重复请求的变化。

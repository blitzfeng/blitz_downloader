## 1. 核心算法与单元测试

- [x] 1.1 在 `com.blitz.downloader.model.TagHierarchy` 中实现 `pruneAuthorHighFreqTags` 纯函数，支持父子/祖先标签剔除及后续候选池顺延增选（跳过祖先标签，上限至多 4 个），验证：代码编译通过
- [x] 1.2 在 `TagHierarchyTest` 中编写针对 `pruneAuthorHighFreqTags` 的详尽单元测试，覆盖基础前 4 截断、父子并存（如颜值+纯欲）剔除父标签并顺延增选、多层嵌套祖先剔除、兄弟标签共存、候选池耗尽不足 4 个等边界，验证：`./gradlew testDebugUnitTest --tests "com.blitz.downloader.model.TagHierarchyTest"` 全量通过

## 2. 仓储层对接与完整构建验证

- [x] 2.1 在 `VideoTagRepository.getAuthorProfileForAi` 中调用 `pruneAuthorHighFreqTags` 过滤 `AuthorTagRatioRow` 列表，保证传入 AI 建议服务的 AuthorProfile 只包含至多 4 个具象标签，验证：`VideoTagRepository` 编译通过且 `getHighFrequencyTagsForAuthor` 保持不受影响
- [x] 2.2 运行全量工程单元测试与 debug APK 编译构建，验证：`./gradlew testDebugUnitTest assembleDebug` 构建通过

package com.blitz.downloader.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.blitz.downloader.R
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.blitz.downloader.data.PreferenceReview
import com.blitz.downloader.data.PreferenceSourceReview
import com.blitz.downloader.model.PreferenceJudgment
import coil.compose.AsyncImage
import java.io.File
import com.blitz.downloader.model.PreferenceLearningPolicy
import com.blitz.downloader.model.PreferenceRule
import com.blitz.downloader.model.PreferenceRuleOverride
import com.blitz.downloader.model.PreferenceSource
import com.blitz.downloader.ui.theme.BlitzTheme
import com.blitz.downloader.viewmodel.PreferenceLearningViewModel

class PreferenceLearningActivity : AppCompatActivity() {
    private val viewModel by viewModels<PreferenceLearningViewModel>()
    private var openedRun by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity 的边到边辅助方法会添加装饰层，本项目的全局 View 背景会让它盖住内容。
        WindowCompat.enableEdgeToEdge(window)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.BLACK
        }
        openedRun = intent.getStringExtra(RUN)
        setContent { BlitzTheme { LearningScreen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedRun = intent.getStringExtra(RUN)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LearningScreen() {
        val state by viewModel.state.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val runs by viewModel.runs.collectAsState()
        var runId by rememberSaveable { mutableStateOf(openedRun) }
        var version by rememberSaveable { mutableStateOf<Long?>(null) }
        var confirmRetry by rememberSaveable { mutableStateOf(false) }
        val run = runs.firstOrNull { it.runId == runId }
        val review = state.review?.takeIf { it.profile.version == version }
        val back = {
            when {
                version != null -> version = null
                runId != null -> { runId = null; confirmRetry = false }
                state.preview != null -> viewModel.closePlan()
                else -> finish()
            }
        }
        LaunchedEffect(openedRun) { if (openedRun != null) { runId = openedRun; version = null } }
        LaunchedEffect(version, state.busy) {
            val selected = version
            if (selected != null && !state.busy && review == null && state.message == null) {
                viewModel.review(selected)
            }
        }
        BackHandler { back() }
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Text(when {
                        version != null -> "规则审核"
                        runId != null -> "学习任务"
                        state.preview != null -> "确认学习计划"
                        else -> "学习我的偏好"
                    })
                },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(painterResource(R.drawable.ic_back_arrow), contentDescription = "返回")
                    }
                },
            )
        }) { insets ->
            Box(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.message?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error) }
                    }
                    when {
                        version != null -> if (review != null) {
                            item {
                                Text("版本 ${review.profile.version} · ${statusText(review.profile.status)}")
                                Button(enabled = !state.busy && review.profile.status in setOf("DRAFT", "ARCHIVED"),
                                    onClick = { viewModel.activate(review.profile.version) }) {
                                    Text(if (review.profile.status == "ARCHIVED") "回滚到此版本" else "应用此草稿")
                                }
                            }
                            items(review.rules, key = { it.scopeKey }) { rule ->
                                RuleCard(review, rule, state.sourceDescriptions, state.busy)
                            }
                        }
                        runId != null -> if (run != null) {
                            item {
                                Text(statusText(run.status), style = MaterialTheme.typography.titleMedium)
                                Text("已处理 ${run.processedCaseCount} 个作品，节点 ${run.completedNodeCount}/${run.plannedNodeCount}")
                                run.lastError?.let { Text(it) }
                                if (run.status in setOf("RUNNING", "MERGING")) {
                                    Text("切到后台或息屏后，学习仍会尝试继续。可从系统通知查看进度。")
                                    TextButton(onClick = viewModel::cancel) { Text("取消学习") }
                                }
                                if (run.status in setOf("FAILED", "CANCELLED", "INTERRUPTED")) {
                                    if (confirmRetry) Text("未确认完成的请求可能已计费。重试可能产生额外费用。")
                                    Button(enabled = !state.busy, onClick = {
                                        viewModel.start(
                                            run.runId, confirmRetry,
                                            onRetryConfirmationRequired = { confirmRetry = true },
                                        ) { confirmRetry = false }
                                    }) { Text(if (confirmRetry) "确认重试并继续" else "继续未完成任务") }
                                }
                                profiles.filter { it.runId == run.runId }.forEach { profile ->
                                    TextButton(onClick = { version = profile.version; viewModel.clearMessage() }) { Text("查看规则版本 ${profile.version}") }
                                }
                            }
                        }
                        state.preview != null -> state.preview?.let { plan ->
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    LearningCard("学习范围") {
                                        Text("${plan.cases} 个作品，计划最多发送 ${plan.images} 张低分辨率证据图")
                                        Text("${plan.leaves} 次提炼，预计 0–${plan.mergeEstimate} 次合并。降级时单节点最多尝试 ${plan.providers.size} 个服务。")
                                    }
                                    LearningCard("服务顺序") {
                                        plan.providers.forEachIndexed { index, provider ->
                                            Text("${index + 1}. ${provider.providerId} · ${provider.modelId}")
                                        }
                                    }
                                    Text("将发送计划内的文案、标签、逐标签判断、已有视觉特征与证据图。确认后才会调用服务。")
                                    Text("开始后可以切到后台或息屏。允许通知后，可以从通知查看进度。")
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.busy,
                                        onClick = { viewModel.start(plan.id, false) { runId = plan.id } },
                                    ) { Text("确认开始") }
                                    TextButton(onClick = viewModel::closePlan, modifier = Modifier.fillMaxWidth()) { Text("返回概览") }
                                }
                            }
                        }
                        else -> {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    state.overview?.let { overview ->
                                        LearningCard("学习资料") {
                                            Text("可用案例 ${overview.cases} · 有证据图 ${overview.photos}")
                                            Text("人工正例 ${overview.positive} · 人工负例 ${overview.negative}")
                                            overview.sources.forEach { (source, count) -> Text("${sourceText(source)} $count") }
                                            if (overview.cases == 0) Text("审核 AI 建议或手动增删标签可积累学习案例。")
                                        }
                                        LearningCard("规则版本") {
                                            Text("启用版本 ${overview.activeVersion ?: "无"} · 待更新 ${overview.pending} 个作品")
                                            if (overview.pending >= PreferenceLearningPolicy.UPDATE_THRESHOLD) {
                                                Text("已有至少 20 个作品新增或更新了判断。")
                                            }
                                        }
                                    }
                                    if (!state.configured) Text("请在设置中启用 AI 建议并配置可用服务。")
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.busy && state.configured && (state.overview?.cases ?: 0) > 0 &&
                                            runs.none { it.status in setOf("RUNNING", "MERGING") },
                                        onClick = viewModel::plan,
                                    ) { Text("预览全量学习计划") }
                                    TextButton(onClick = viewModel::refresh, enabled = !state.busy,
                                        modifier = Modifier.fillMaxWidth()) { Text("刷新概览") }
                                }
                            }
                            items(runs.filter { it.status != "PLANNED" }, key = { it.runId }) {
                                TextButton(onClick = { runId = it.runId; viewModel.clearMessage() }) {
                                    Text("任务 · ${statusText(it.status)} · ${it.processedCaseCount} 个作品")
                                }
                            }
                            items(profiles, key = { it.version }) {
                                TextButton(onClick = { version = it.version; viewModel.clearMessage() }) {
                                    Text("版本 ${it.version} · ${statusText(it.status)}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun LearningCard(title: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun RuleCard(review: PreferenceReview, rule: PreferenceRule, descriptions: Map<String, PreferenceSourceReview>, busy: Boolean) {
        val edit = review.overrides[rule.scopeKey] ?: PreferenceRuleOverride()
        val coverage = review.coverage[rule.scopeKey]
        val draft = review.profile.status == "DRAFT"
        var expanded by rememberSaveable { mutableStateOf(false) }
        var text by rememberSaveable(edit.text) { mutableStateOf(edit.text ?: "") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rule.tagIds.joinToString("、") { review.tagNames[it] ?: "#$it" }.ifBlank { "全局" },
                    style = MaterialTheme.typography.titleMedium)
                Text(rule.criteria)
                val conditions = listOf("支持" to rule.positiveSignals, "反例" to rule.negativeSignals,
                    "替换条件" to rule.replacementConditions, "共存条件" to rule.coexistenceConditions)
                conditions.filter { it.second.isNotEmpty() }.forEach { (name, details) ->
                    Text("$name · ${details.joinToString("、")}")
                }
                if (rule.insufficientEvidence) Text(if (edit.reviewed) "资料不足 · 已人工审核" else "资料不足 · 待核实")
                Text("正例 ${coverage?.positiveIds?.size ?: 0} · 反例 ${coverage?.negativeIds?.size ?: 0} · 未知 ${coverage?.unknownIds?.size ?: 0} · 图证 ${coverage?.evidenceIds?.size ?: 0}")
                TextButton(enabled = !busy, onClick = {
                    expanded = !expanded
                    if (expanded) viewModel.sources(rule.representativeCaseIds)
                }) { Text(if (expanded) "收起来源" else "查看来源") }
                if (expanded) rule.representativeCaseIds.forEach { id ->
                    val type = listOfNotNull(
                        "正例".takeIf { id in coverage?.positiveIds.orEmpty() },
                        "反例".takeIf { id in coverage?.negativeIds.orEmpty() },
                        "未知来源".takeIf { id in coverage?.unknownIds.orEmpty() },
                        "含图证".takeIf { id in coverage?.evidenceIds.orEmpty() },
                    ).joinToString(" · ")
                    val source = descriptions[id]
                    Text("$id $type ${source?.desc.orEmpty()}")
                    source?.decisions?.filter { rule.scope == "global" || it.tagId in rule.tagIds }?.forEach { decision ->
                        val label = review.tagNames[decision.tagId] ?: "#${decision.tagId}"
                        val judgment = when (decision.judgment) {
                            PreferenceJudgment.POSITIVE -> "支持"
                            PreferenceJudgment.NEGATIVE -> "反对"
                            PreferenceJudgment.UNKNOWN -> "未知"
                        }
                        Text("$label · $judgment · ${sourceText(decision.source)}")
                    }
                    source?.imagePath?.takeIf { id in coverage?.evidenceIds.orEmpty() }?.let { path ->
                        AsyncImage(model = File(path), contentDescription = "案例证据图",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp))
                    }
                }
                if (draft) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(edit.enabled, enabled = !busy, onCheckedChange = {
                            viewModel.edit(review.profile.version, rule.scopeKey, edit.copy(enabled = it))
                        })
                        Text("启用规则")
                    }
                    if (rule.insufficientEvidence && !edit.reviewed) {
                        TextButton(enabled = !busy, onClick = {
                            viewModel.edit(review.profile.version, rule.scopeKey, edit.copy(reviewed = true))
                        }) { Text("已核查，确认使用") }
                    }
                    OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("人工覆写") },
                        supportingText = { Text("最多 512 字，留空恢复模型版本") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !busy, onClick = {
                        viewModel.edit(review.profile.version, rule.scopeKey, edit.copy(text = text))
                    }) { Text("保存修改") }
                } else if (edit.text != null) Text("人工覆写 · ${edit.text}")
            }
        }
    }

    companion object {
        private const val RUN = "run"
        fun intent(context: Context, runId: String? = null) = Intent(context, PreferenceLearningActivity::class.java)
            .putExtra(RUN, runId)
    }
}

private fun statusText(status: String): String = when (status) {
    "PLANNED" -> "等待开始"
    "RUNNING" -> "正在提炼"
    "MERGING" -> "正在合并"
    "READY_FOR_REVIEW" -> "等待审核规则"
    "FAILED" -> "失败"
    "INTERRUPTED" -> "已中断"
    "CANCELLED" -> "已取消"
    "STALE" -> "计划已过期，请重新预览"
    "DRAFT" -> "草稿"
    "ACTIVE" -> "已启用"
    "ARCHIVED" -> "已归档"
    "INVALID" -> "版本已失效"
    else -> status
}

private fun sourceText(source: PreferenceSource): String = when (source) {
    PreferenceSource.HUMAN_CONFIRM -> "人工确认"
    PreferenceSource.HUMAN_ADD -> "人工新增"
    PreferenceSource.HUMAN_REMOVE -> "人工删除或拒绝"
    PreferenceSource.HUMAN_REPLACE -> "人工接受替换"
    PreferenceSource.HUMAN_COEXIST -> "人工接受共存"
    PreferenceSource.AUTO_EXISTING -> "已有标签自动处理"
    PreferenceSource.SKIPPED -> "跳过或撤销"
    PreferenceSource.LEGACY_UNKNOWN -> "历史来源未知"
}

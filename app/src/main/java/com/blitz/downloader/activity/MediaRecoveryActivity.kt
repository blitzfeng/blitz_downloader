package com.blitz.downloader.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.blitz.downloader.data.db.*
import com.blitz.downloader.model.RecoveryDetail
import com.blitz.downloader.service.MediaRecoveryService
import com.blitz.downloader.service.MediaRecoveryService.Action
import com.blitz.downloader.ui.theme.BlitzTheme
import com.blitz.downloader.viewmodel.MediaRecoveryViewModel

class MediaRecoveryActivity : AppCompatActivity() {
    private val vm by viewModels<MediaRecoveryViewModel>()
    private val readPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.error.value = "可以改用系统目录选择器授权 bDouyin"
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setGrant) }
    private val report = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::report) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContent { BlitzTheme { RecoveryScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun RecoveryScreen() {
        val latest by vm.session.collectAsState()
        val past by vm.history.collectAsState(emptyList())
        val downloads by vm.downloads.collectAsState(0)
        val selected by vm.selected.collectAsState()
        val preview by vm.preview.collectAsState()
        val busy by MediaRecoveryService.running.collectAsState()
        val preparing by vm.localBusy.collectAsState()
        val message by MediaRecoveryService.message.collectAsState()
        val error by vm.error.collectAsState()
        val enabled = !busy && !preparing
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var page by rememberSaveable { mutableIntStateOf(0) }
        var showHistory by rememberSaveable { mutableStateOf(false) }
        var usePrevious by rememberSaveable { mutableStateOf(false) }
        val previous by produceState<RecoverySession?>(null,latest) { value = vm.lastScan() }
        val session = if (usePrevious) previous else latest
        val id = session?.sessionId.orEmpty()
        val counts by remember(id) { vm.counts(id) }.collectAsState(listOf(0,0,0))
        val groups = listOf(listOf("READY","CONFIRMED","APPLIED"),listOf("REVIEW","STALE","EXCLUDED"),
            listOf("REMOTE_ONLY"),listOf("REMOTE_UNAVAILABLE","AUTH_REQUIRED","RATE_LIMITED","INVALID_RESPONSE","NETWORK_ERROR","ID_MISMATCH"))
        val rows by remember(id,tab,page) { vm.page(id,groups[tab],page*50) }.collectAsState(emptyList())
        val files by remember(id,page) { vm.unbound(id,page*50) }.collectAsState(emptyList())
        val failures by remember(id,page) { vm.errors(id,page*50) }.collectAsState(emptyList())
        var detail by remember { mutableStateOf<RecoveryCandidate?>(null) }
        var bind by remember { mutableStateOf<RecoveryFile?>(null) }
        var historyBatch by remember { mutableStateOf<RecoveryBatch?>(null) }
        var bulk by remember { mutableStateOf<List<RecoveryCandidate>>(emptyList()) }
        LaunchedEffect(id) { page = 0; vm.selected.value = emptyList(); vm.preview.value = null }
        Scaffold(topBar = { TopAppBar(title = { Text(if (showHistory) "恢复历史" else "恢复失联记录") },
            navigationIcon = { TextButton(onClick = { if (showHistory) showHistory = false else finish() }) { Text("返回") } },
            actions = { TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "概况" else "历史") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showHistory) {
                    item { OutlinedButton(onClick = { report.launch("media-recovery.json") }) { Text("导出恢复报告") } }
                    if (past.isEmpty()) item { Text("尚无写回批次") }
                    items(past, key = { it.batchId }) { b -> OutlinedButton(onClick = { historyBatch = b }) {
                        Text("子批次 ${b.part} · ${label(b.state)} · 新增 ${b.inserted} · 跳过 ${b.skipped}") } }
                } else {
                    item { Text("当前下载记录 $downloads 条 · 已恢复 ${past.sumOf { it.inserted }} 条",style = MaterialTheme.typography.titleMedium) }
                    item { Text("恢复保护已开启，自动备份与过期清理已暂停。") }
                    item { Text("已扫描 ${session?.fileCount ?: 0} 个文件 · ${counts[0]} 个作品 ID · ${counts[1]} 个有效详情 · ${counts[2]} 个已确认关联") }
                    session?.let { item { Text("第 ${it.generation} 次扫描 · ${label(it.state)}  ${it.errorCode}") } }
                    if (session?.stage == "SCAN" && previous != null && !busy) item {
                        TextButton(onClick = { usePrevious = true }) { Text("查看上一次完整扫描") }
                    }
                    item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (android.os.Build.VERSION.SDK_INT < 29 && !vm.repo.access.canUseFile()) {
                            OutlinedButton(enabled = enabled,onClick = {
                                readPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                            }) { Text("允许读取媒体") }
                        }
                        OutlinedButton(enabled = enabled,onClick = { folder.launch(null) }) { Text("授权 bDouyin 目录") }
                        OutlinedButton(enabled = enabled,onClick = { usePrevious = false; vm.start(Action.SCAN,id) }) { Text("扫描本地文件") }
                    } }
                    if (id.isNotEmpty() && session?.stage != "SCAN") item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = enabled,onClick = { vm.start(Action.QUERY,id) }) { Text("查询 / 继续") }
                        TextButton(enabled = enabled,onClick = { vm.start(Action.MATCH,id) }) { Text("匹配已有详情") }
                    } }
                    if (busy || preparing) item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (busy) TextButton(onClick = vm::pause) { Text("暂停任务") }
                    }
                    if (message.isNotBlank() || error.isNotBlank()) item { Text(error.ifBlank { message },color = MaterialTheme.colorScheme.error) }
                    if (id.isNotEmpty() && session?.stage != "SCAN") {
                        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("可确认","需复核","未匹配","请求异常").forEachIndexed { i,title ->
                                FilterChip(selected = tab == i,onClick = { tab = i; page = 0 },label = { Text(title) })
                            }
                        } }
                        if (tab == 0) item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = enabled && rows.any { it.state == "READY" },onClick = { bulk = rows.filter { it.state == "READY" } }) { Text("确认本页建议") }
                            Button(enabled = enabled && selected.isNotEmpty(),onClick = vm::prepare) { Text("预览写回（${selected.size}）") }
                        } }
                        items(rows,key = { it.awemeId + "|" + it.groupKey }) { c ->
                            val name = remember(c) { vm.inputs(c)?.main?.substringAfterLast('/') ?: c.awemeId }
                            ListItem(headlineContent = { Text(name,maxLines = 2,overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                                supportingContent = { Text("${label(c.state)} · ${c.reason}") },
                                trailingContent = { Row {
                                    if (c.state == "CONFIRMED") Checkbox(c in selected,{ vm.toggle(c) },modifier = Modifier.testTag("write-select-" + c.awemeId),enabled = enabled)
                                    TextButton(onClick = { detail = c }, modifier = Modifier.testTag("view-" + c.awemeId)) { Text("查看") }
                                } })
                        }
                        if (tab == 2) items(files,key = { it.logicalPath }) { f ->
                            TextButton(enabled = enabled,onClick = { bind = f }) { Text("补充作品链接 · ${f.logicalPath.substringAfterLast('/')}") }
                        }
                        if (tab == 3) items(failures,key = { it.awemeId }) { w -> Text("${w.awemeId} · ${label(w.state)} · HTTP ${w.httpCode} · ${w.elapsedMs} ms") }
                        item { Row {
                            TextButton(enabled = page > 0,onClick = { page-- }) { Text("上一页") }
                            Text("第 ${page+1} 页")
                            TextButton(enabled = rows.size == 50 || tab == 2 && files.size == 50 || tab == 3 && failures.size == 50,onClick = { page++ }) { Text("下一页") }
                        } }
                    }
                }
            }
        }
        detail?.let { c -> CandidateDialog(c,enabled,{ detail = null }) }
        bind?.let { file ->
            var input by remember(file) { mutableStateOf("") }
            AlertDialog(onDismissRequest = { bind = null },title = { Text("补充作品") },text = {
                Column { Text(file.logicalPath); OutlinedTextField(input,{input=it},label={Text("作品 ID 或抖音作品链接")}) }
            },confirmButton = { TextButton(onClick = { vm.attach(id,file.logicalPath,input); bind=null }) { Text("关联并等待查询") } },
                dismissButton = { TextButton(onClick = { bind=null }) { Text("取消") } })
        }
        if (bulk.isNotEmpty()) AlertDialog(onDismissRequest = { bulk=emptyList() },title = { Text("确认本页 ${bulk.size} 个对应关系？") },
            text = { Text(bulk.joinToString("\n") { it.groupKey.substringAfterLast('/') },Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.confirmReady(bulk);bulk=emptyList() }) { Text("确认关联") } },
            dismissButton = { TextButton(onClick = { bulk=emptyList() }) { Text("取消") } })
        preview?.let { p ->
            AlertDialog(onDismissRequest = { vm.preview.value=null },title = { Text("确认备份并写回？") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("预计新增 ${p.expectedNew}，已存在 ${p.rows.size-p.expectedNew}。共 ${p.parts.size} 批，每批数据库约 ${p.dbBytes/1024} KiB。")
                    Text("全部子批次预计最多约 ${p.dbBytes * p.parts.size / 1024} KiB 备份空间。")
                    Text("独立备份：Download/bDouyin/recovery-backups/${p.operation}")
                    Text("原下载时间、观看、导出及标签编辑历史未知。")
                    p.rows.forEach { row -> val video = vm.repo.gson.fromJson(row.payloadJson,DownloadedVideoEntity::class.java)
                        Text("${row.awemeId}\n${video.filePath}\n封面：${video.coverPath.ifBlank { "无" }}")
                    }
                } },
                confirmButton={ Button(onClick={vm.start(Action.WRITE,id)}) {Text("备份并新增")} },
                dismissButton={TextButton(onClick={vm.preview.value=null}){Text("取消")}})
        }
        historyBatch?.let { b ->
            val entries by produceState<List<RecoveryBatchItem>>(emptyList(),b) { value = vm.batchItems(b.batchId) }
            AlertDialog(onDismissRequest={historyBatch=null},title={Text("子批次 ${b.part} · ${label(b.state)}")},text={
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(b.backupLocator.ifBlank { "尚无已验证备份" }); Text(b.errorCode)
                    Text("计划 ${entries.size} · 新增 ${b.inserted} · 已存在 ${b.skipped}")
                    Text("过期 ${entries.count { it.state == "STALE" }} · 失败 ${entries.count { it.state == "FAILED" }} · 未处理 ${entries.count { it.state == "PENDING" }}")
                    entries.forEach { Text("${it.awemeId} · ${label(it.state)}") }
                }
            },confirmButton={TextButton(onClick={historyBatch=null}){Text("关闭")}})
        }
    }

    @Composable private fun CandidateDialog(c: RecoveryCandidate, enabled: Boolean, close: ()->Unit) {
        val inputs = remember(c) { vm.inputs(c) }
        var selection by remember(c) { mutableStateOf(inputs) }
        var chooseFile by remember(c) { mutableStateOf(false) }
        var filePage by rememberSaveable(c.awemeId) { mutableIntStateOf(0) }
        val work by produceState<RecoveryDetail?>(null,c) { value = vm.detail(c) }
        AlertDialog(onDismissRequest = close,title = { Text(c.awemeId) },text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                work?.let { Text(it.author);Text(it.description) }
                Text(c.method + " · " + c.reason)
                if (inputs == null) {
                    TextButton(onClick = { chooseFile = true }) { Text("选择本地文件") }
                    if (chooseFile) {
                        val files by remember(c.sessionId,filePage) { vm.unbound(c.sessionId,filePage*50) }.collectAsState(emptyList())
                        files.forEach { f -> TextButton(enabled = enabled,onClick = {
                            vm.attach(c.sessionId,f.logicalPath,c.awemeId); close()
                        }) { Text(f.logicalPath.substringAfterLast('/')) } }
                        Row {
                            TextButton(enabled = filePage > 0,onClick = { filePage-- }) { Text("上一页") }
                            TextButton(enabled = files.size == 50,onClick = { filePage++ }) { Text("下一页") }
                        }
                    }
                }
                if (inputs != null) {
                    Text(inputs.main)
                    if (work?.mediaType == "image" && inputs.members.size > 1) {
                        Text("勾选图集成员，圆点指定主图")
                        val chosen = selection ?: inputs
                        inputs.members.forEach { path ->
                            Row {
                                Checkbox(path in chosen.members,{ keep ->
                                    val members = inputs.members.filter { if (it == path) keep else it in chosen.members }
                                    val first = members.firstOrNull { !it.endsWith(".mp4",true) }
                                    if (first != null) {
                                        val main = chosen.main.takeIf { it in members } ?: first
                                        selection = chosen.copy(members = members,main = main,cover = main)
                                    }
                                })
                                if (!path.endsWith(".mp4",true)) RadioButton(path == chosen.main,{
                                    selection = chosen.copy(main = path,cover = path,members = inputs.members.filter { it == path || it in chosen.members })
                                })
                                Text(path.substringAfterLast('/'))
                            }
                        }
                    }
                    (listOfNotNull(inputs.cover) + inputs.evidence).distinct().forEach { path ->
                        val model = remember(path) { if (vm.repo.access.canUseFile()) vm.repo.access.local(path)
                            else runCatching { vm.repo.access.uri(path) }.getOrNull() }
                        AsyncImage(model, path.substringAfterLast('/'),Modifier.fillMaxWidth().height(128.dp))
                    }
                    if (work?.mediaType == "image") TextButton(onClick = {
                        startActivity(Intent(this@MediaRecoveryActivity,ImageViewerActivity::class.java)
                            .putExtra(ImageViewerActivity.EXTRA_FILE_PATH,selection?.main ?: inputs.main)
                            .putStringArrayListExtra(ImageViewerActivity.EXTRA_MEMBERS,ArrayList(selection?.members ?: inputs.members)))
                    }) { Text("预览本地图集") }
                    if (work?.mediaType == "video") TextButton(onClick = {
                        startActivity(VideoPlayerActivity.createFileIntent(this@MediaRecoveryActivity,inputs.main))
                    }) { Text("预览本地视频") }
                }
            }
        },confirmButton = { TextButton(enabled = enabled && c.state in listOf("READY","REVIEW","STALE","EXCLUDED"),onClick = { vm.review(c,true,selection);close() }) { Text("确认关联") } },
            dismissButton = { Row {
                if (c.state in listOf("CONFIRMED","READY","REVIEW","STALE")) TextButton(enabled = enabled,onClick={vm.review(c,false);close()}){Text("排除")}
                TextButton(onClick=close){Text("关闭")}
            } })
    }
    private fun label(state:String):String = when(state) {
        "SCANNING" -> "正在扫描"; "SCANNED" -> "已扫描"; "FETCHING" -> "正在查询"; "MATCHING" -> "正在匹配"
        "AUTH_REQUIRED" -> "请检查登录状态或接口访问权限"; "RATE_LIMITED" -> "接口限流，稍后继续"
        "INTERRUPTED","PAUSED" -> "已暂停"; "NEEDS_PERMISSION" -> "需要目录授权"; "READY" -> "建议关联，待确认"
        "REVIEW" -> "需复核"; "REVIEW_READY" -> "可复核"; "CONFIRMED" -> "已确认"; "STALE" -> "已过期"
        "EXCLUDED" -> "已排除"; "REMOTE_ONLY" -> "只有远端详情"; "FAILED" -> "失败"; "PLANNED" -> "已预览"
        "COMMITTED" -> "已提交"; "PENDING" -> "未处理"; "APPLIED","SUCCEEDED" -> "已新增"
        "SKIPPED_EXISTING","ALREADY_PRESENT" -> "已存在"; "NETWORK_ERROR" -> "网络错误"; "INVALID_RESPONSE" -> "无效响应"
        "REMOTE_UNAVAILABLE" -> "远端不可访问"; "ID_MISMATCH" -> "作品 ID 不符"; "BACKING_UP" -> "正在备份"; "BACKUP_VERIFIED" -> "备份已校验"
        else -> state
    }
}

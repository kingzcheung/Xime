package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.twotone.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.RimeExportManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SyncManager
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步与备份设置页。
 *
 * 两条链路同源（都基于引擎原生 sync 文本快照、按时间戳合并）：
 * - 词库同步：只同步自造词（sync 目录 ↔ 云插件通道），适合高频、增量
 * - 云备份：完整快照归档（设置/补丁 + 自造词 + 可选方案与插件），适合换机/灾难恢复
 * 备份包内的自造词同样是快照文本，恢复时合并而非覆盖 leveldb。
 * 备份目标为已安装的 backup 类型插件（单选激活，与剪贴板同步同模式），
 * 包由宿主生成/恢复，服务器配置由所选插件的配置表单承载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsContent(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToMarket: () -> Unit,
    /** 打开所选备份服务的配置页（独立页面，与插件中心同一入口） */
    onNavigateToPluginSettings: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedPlugins = remember { ExtensionManager.getAllInstalledPlugins() }
    val backupPlugins = remember { installedPlugins.filter { it.category == PluginCategory.BACKUP } }
    val syncPlugins = remember { ExtensionManager.getEnabledBackupPlugins(context) }
    // 与插件管理页/引擎同一判定规则（ActivePluginSelection）：偏好为空或指向未启用插件时回退首个已启用项
    var selectedPluginId by remember {
        mutableStateOf(
            ActivePluginSelection.resolve(
                SettingsPreferences.getBackupPluginId(context),
                syncPlugins.map { it.first }
            )
        )
    }
    var activePlugin by remember {
        mutableStateOf(
            syncPlugins.firstOrNull { it.first == selectedPluginId } ?: syncPlugins.firstOrNull()
        )
    }
    // 当前进行中的操作标识（"backup"/"list"/"restore:<id>"/"delete:<id>"）：
    // 按操作独立，loading 只出现在触发它的按钮上，其余按钮仅禁用
    var busyOp by remember { mutableStateOf<String?>(null) }
    val busy = busyOp != null
    var message by remember { mutableStateOf<String?>(null) }
    var remoteList by remember { mutableStateOf<List<com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry>?>(null) }
    // 切换备份服务走单选弹窗（与剪贴板同步/语音转文本同构：插件多时页面长度恒定）
    var showServicePicker by remember { mutableStateOf(false) }
    // 本地完整方案包导入的确认（覆盖式恢复，先确认再选文件）
    var pendingLocalImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    // 低频本地操作收进弹层：词条导入/导出、完整方案保存/恢复
    var showDictSheet by remember { mutableStateOf(false) }
    var showLocalSheet by remember { mutableStateOf(false) }
    // 云端备份列表展开态（默认收起，页面保持短）
    var remoteListExpanded by remember { mutableStateOf(false) }

    // 词库同步（词典快照为主链路，配置备份为附带；语义分工见各区块说明）
    var lastSyncAt by remember {
        mutableStateOf(SettingsPreferences.getLastRimeSyncAt(context))
    }
    var syncRemoteList by remember {
        mutableStateOf<List<com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry>?>(null)
    }
    LaunchedEffect(activePlugin) {
        val plugin = activePlugin?.second ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            syncRemoteList = SyncManager.listRemoteSnapshots(plugin)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busyOp = "import"
        message = null
        scope.launch(Dispatchers.IO) {
            val result = SyncManager.importSnapshots(context, uris.toList())
            withContext(Dispatchers.Main) {
                busyOp = null
                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                message = result.fold(
                    onSuccess = { r ->
                        buildString {
                            if (r.snapshotFiles > 0) {
                                append("已导入 ${r.snapshotFiles} 个快照")
                                if (r.merged) append("并完成合并")
                            }
                            if (r.importedEntries > 0) {
                                if (isNotEmpty()) append("；")
                                append("已导入 ${r.importedEntries} 条词条")
                            }
                        }
                    },
                    onFailure = { "导入失败：${it.message}" }
                )
            }
        }
    }

    val localPackageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingLocalImportUri = uri
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("同步与备份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsSection(
                title = "备份服务",
                content = {
                    if (backupPlugins.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "未安装备份插件，请先在扩展商店安装后再配置。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onNavigateToMarket,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("前往扩展商店")
                            }
                        }
                    } else {
                        // 入口始终可见（与剪贴板同步/语音转文本一致）：只装 1 个插件时也能点开确认候选与协议
                        // 行自带 16dp 内边距，这里不再套一层 padding 的 Column，避免双重留白
                        CurrentBackupServiceItem(
                            pluginInfo = installedPlugins.find { it.id == activePlugin?.first },
                            pluginId = activePlugin?.first,
                            plugin = activePlugin?.second,
                            onClick = { showServicePicker = true },
                            onConfigure = activePlugin?.first?.let { id ->
                                { onNavigateToPluginSettings(id) }
                            }
                        )
                    }
                }
            )

            SettingsSection(
                title = "词条（自造词）",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = buildString {
                                append("设备标识：")
                                append(SettingsPreferences.getRimeInstallationId(context).take(8))
                                append("　上次同步：")
                                append(
                                    if (lastSyncAt > 0) {
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                            .format(Date(lastSyncAt))
                                    } else "从未"
                                )
                                syncRemoteList?.let { append("　云端快照：${it.size} 份") }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 一个动作：本机整理（引擎原生 sync）+ 已配置服务时顺带云端多设备合并
                        Button(
                            onClick = {
                                busyOp = "syncAll"
                                message = null
                                scope.launch(Dispatchers.IO) {
                                    val plugin = activePlugin?.second
                                    val result: Result<Int> = if (plugin != null) {
                                        SyncManager.remoteSync(context, plugin)
                                    } else {
                                        SyncManager.syncNow(context).map { 0 }
                                    }
                                    val fresh = plugin?.let { SyncManager.listRemoteSnapshots(it) }
                                    withContext(Dispatchers.Main) {
                                        busyOp = null
                                        if (fresh != null) syncRemoteList = fresh
                                        lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                                        message = result.fold(
                                            onSuccess = { pulled ->
                                                when {
                                                    plugin == null -> "已整理本机词条（未配置备份服务，未上云）"
                                                    pulled > 0 -> "已合并 $pulled 台设备的词条，并上传本机快照"
                                                    else -> "已上传本机快照（云端暂无其他设备）"
                                                }
                                            },
                                            onFailure = { "同步失败：${it.message}" }
                                        )
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (busyOp == "syncAll") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("同步词条")
                            }
                        }
                        // 云端各设备快照（只读；合并由上面动作完成）
                        syncRemoteList?.let { list ->
                            if (list.isEmpty()) {
                                Text(
                                    text = "云端暂无快照；同步后其他设备可看到本机快照并自动合并新词条。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            } else {
                                list.forEach { entry ->
                                    Column {
                                        Text(
                                            text = entry.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = buildString {
                                                if (entry.createdAt > 0) {
                                                    append(
                                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                                            .format(Date(entry.createdAt))
                                                    )
                                                }
                                                if (entry.size >= 0) {
                                                    if (isNotEmpty()) append(" · ")
                                                    append(String.format(Locale.getDefault(), "%.1f KB", entry.size / 1024.0))
                                                }
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                        // 低频手动搬运收进弹层
                        TextButton(
                            onClick = { showDictSheet = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导入 / 导出词条  ›", fontSize = 13.sp)
                        }
                        Text(
                            text = "词条（自造词）基于引擎原生同步：多设备按时间戳合并不互相覆盖。" +
                                "导入支持其他 rime 前端/桌面端 sync 目录里的 `<词典名>.userdb.txt` 快照与 `<词典名>.txt` 词条码表。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            )

            SettingsSection(
                title = "完整备份（配置与方案）",
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "备份 = 整机的完整快照：设置与用户补丁、方案本体与资源、插件包、" +
                                "以及自造词快照（恢复时按时间戳合并，不会吃掉本机新词）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    val plugin = activePlugin?.second ?: return@Button
                                    busyOp = "backup"
                                    message = null
                                    scope.launch(Dispatchers.IO) {
                                        val result = BackupManager.backupNow(context, plugin)
                                        withContext(Dispatchers.Main) {
                                            busyOp = null
                                            message = if (result.ok) {
                                                "备份完成" + (result.message ?: "")
                                            } else {
                                                "备份失败：${result.message ?: "未知错误"}"
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !busy && activePlugin != null
                            ) {
                                if (busyOp == "backup") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.TwoTone.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    text = "立即备份",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val plugin = activePlugin?.second ?: return@OutlinedButton
                                    if (remoteListExpanded) {
                                        remoteListExpanded = false
                                        return@OutlinedButton
                                    }
                                    remoteListExpanded = true
                                    busyOp = "list"
                                    message = null
                                    scope.launch(Dispatchers.IO) {
                                        // 词条快照（rime-sync- 前缀）属于词条区，不参与全量恢复语义，此处过滤
                                        val list = BackupManager.listRemote(plugin)
                                            ?.filter { !SyncManager.isRemoteSyncEntry(it.name) }
                                        val err = if (list == null) "获取备份列表失败" else null
                                        withContext(Dispatchers.Main) {
                                            busyOp = null
                                            remoteList = list
                                            message = err
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !busy && activePlugin != null
                            ) {
                                if (busyOp == "list") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        if (remoteListExpanded) "收起列表" else "云端备份列表",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                        if (activePlugin == null) {
                            Text(
                                text = "未配置备份服务：云端备份不可用；可用下方「保存到本地 / 从本地恢复」。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        message?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 云端备份包列表（展开时；恢复=覆盖式，自造词按时间戳合并）
                        if (remoteListExpanded) {
                            val plugin = activePlugin?.second
                            val list = remoteList
                            if (plugin != null && list != null) {
                                if (list.isEmpty()) {
                                    Text(
                                        text = "云端暂无备份",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                list.forEach { entry ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = entry.name,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = buildString {
                                                    if (entry.createdAt > 0) {
                                                        append(
                                                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                                                .format(Date(entry.createdAt))
                                                        )
                                                    }
                                                    if (entry.size >= 0) {
                                                        if (isNotEmpty()) append(" · ")
                                                        append(String.format(Locale.getDefault(), "%.1f MB", entry.size / 1024.0 / 1024.0))
                                                    }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        Row {
                                            TextButton(
                                                onClick = {
                                                    busyOp = "restore:" + entry.id
                                                    message = null
                                                    scope.launch(Dispatchers.IO) {
                                                        val result = BackupManager.restore(context, plugin, entry.id)
                                                        withContext(Dispatchers.Main) {
                                                            busyOp = null
                                                            message = if (result.isSuccess)
                                                                "恢复完成，重启应用后生效；自造词快照将在引擎就绪后按时间戳合并"
                                                            else "恢复失败：${result.exceptionOrNull()?.message}"
                                                        }
                                                    }
                                                },
                                                enabled = !busy
                                            ) {
                                                if (busyOp == "restore:" + entry.id) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Text("恢复")
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    busyOp = "delete:" + entry.id
                                                    message = null
                                                    scope.launch(Dispatchers.IO) {
                                                        val ok = BackupManager.deleteRemote(plugin, entry.id)
                                                        val fresh = if (ok) BackupManager.listRemote(plugin)
                                                            ?.filter { !SyncManager.isRemoteSyncEntry(it.name) } else null
                                                        withContext(Dispatchers.Main) {
                                                            busyOp = null
                                                            if (ok) {
                                                                remoteList = fresh
                                                                message = "已删除"
                                                            } else {
                                                                message = "删除失败"
                                                            }
                                                        }
                                                    }
                                                },
                                                enabled = !busy
                                            ) {
                                                if (busyOp == "delete:" + entry.id) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    Text(
                                                        "删除",
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { showLocalSheet = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("保存到本地 / 从本地恢复  ›", fontSize = 13.sp)
                        }
                        Text(
                            text = "恢复覆盖 rime/ 目录同名文件（含自造词），并还原插件、插件配置与设置项；重启应用后全部生效。" +
                                "自造词按时间戳合并不吃新词；编译产物 build/ 与用户词典库 *.userdb 不进包。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            )

        }
    }

    // 词条本地导入/导出（低频，收进弹层；与 rime 生态其他前端格式互通）
    if (showDictSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDictSheet = false },
            sheetState = rememberModalBottomSheetState(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "导入 / 导出词条",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = {
                        showDictSheet = false
                        busyOp = "import"
                        message = null
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("导入词条（快照 / 码表）")
                }
                OutlinedButton(
                    onClick = {
                        showDictSheet = false
                        busyOp = "export"
                        message = null
                        scope.launch(Dispatchers.IO) {
                            val result = SyncManager.exportToDownloads(context)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                message = result.fold(
                                    onSuccess = { "已保存到下载目录：$it" },
                                    onFailure = { "导出失败：${it.message}" }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("导出词条快照到下载")
                }
                Text(
                    text = "导入吃其他 rime 前端/桌面端导出的 `<词典名>.userdb.txt` 快照与 `<词典名>.txt` 词条码表；" +
                        "导出的 zip 解压后其中的快照也能被对方还原。词条一律按时间戳合并，不会覆盖本机新词。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 完整方案的本地保存/恢复（与云端「立即备份」同格式的包）
    if (showLocalSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLocalSheet = false },
            sheetState = rememberModalBottomSheetState(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "保存到本地 / 从本地恢复",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = {
                        showLocalSheet = false
                        busyOp = "exportLocal"
                        message = null
                        scope.launch(Dispatchers.IO) {
                            val result = RimeExportManager.exportArchive(context)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                message = result.fold(
                                    onSuccess = { "已导出完整方案到下载目录：${it.fileName}" },
                                    onFailure = { "导出失败：${it.message}" }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("导出完整方案")
                }
                OutlinedButton(
                    onClick = {
                        showLocalSheet = false
                        localPackageLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("从本地导入")
                }
                Text(
                    text = "与云端「立即备份」同一个包格式（换机/离线可用）。本地导入为覆盖式恢复，会先弹确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showServicePicker) {
        // 切换备份服务：单选弹窗（点行即切换并关闭）
        SettingsSingleChoiceDialog(
            title = "选择备份服务",
            options = backupPlugins.map { plugin ->
                val protocols = plugin.capabilities?.backup?.protocols.orEmpty()
                SettingsChoiceOption(
                    id = plugin.id,
                    title = plugin.name,
                    subtitle = buildString {
                        append(plugin.description)
                        if (protocols.isNotEmpty()) {
                            append("\n备份协议: ")
                            append(protocols.joinToString("、"))
                        }
                    }
                )
            },
            selectedId = selectedPluginId,
            onSelect = { pickedId ->
                showServicePicker = false
                if (pickedId != selectedPluginId) {
                    selectedPluginId = pickedId
                    SettingsPreferences.setBackupPluginId(context, pickedId)
                    remoteList = null
                    scope.launch(Dispatchers.IO) {
                        // 单选激活：同一时间只启用 1 个备份插件
                        backupPlugins
                            .filter { it.id != pickedId }
                            .forEach {
                                SettingsPreferences.setPluginEnabled(context, it.id, false)
                                PluginManager.unloadPlugin(it.id)
                            }
                        SettingsPreferences.setPluginEnabled(context, pickedId, true)
                        PluginManager.launchPlugin(pickedId)
                        // 重新获取已启用实例，驱动配置表单与远端备份列表切换到新插件
                        activePlugin = ExtensionManager.getEnabledBackupPlugins(context)
                            .firstOrNull { it.first == pickedId }
                    }
                }
            },
            onDismiss = { showServicePicker = false }
        )
    }

    // 本地完整方案包导入：覆盖式恢复，先确认再解包
    pendingLocalImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLocalImportUri = null },
            title = { Text("从本地导入完整方案") },
            text = {
                Text(
                    "会覆盖 rime/ 目录下同名配置与方案文件，并还原插件、插件配置与设置项；" +
                        "自造词按时间戳合并（不会吃掉本机新词）。重启应用后全部生效。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLocalImportUri = null
                        busyOp = "importLocal"
                        message = null
                        scope.launch(Dispatchers.IO) {
                            val result = BackupManager.importLocal(context, uri)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                                message = result.fold(
                                    onSuccess = { "已从本地导入完整方案，重启应用后生效" },
                                    onFailure = { "导入失败：${it.message}" }
                                )
                            }
                        }
                    }
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocalImportUri = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 当前生效的备份服务（一行）。
 *
 * 切换入口收进对话框（[SettingsSingleChoiceDialog] 的等价内联实现）：插件多时页面长度恒定，
 * 这里只回答"现在用的是谁、备份协议是什么"。整行**始终可点**——只装一个插件时，
 * 这个入口是页面上唯一能确认候选与协议的地方，藏掉它会让"当前生效的是谁"无处可查。
 */
@Composable
private fun CurrentBackupServiceItem(
    pluginInfo: PluginInfo?,
    pluginId: String?,
    plugin: BackupPlugin?,
    onClick: () -> Unit,
    /** 非空时在行内提供「配置」入口（打开独立配置页） */
    onConfigure: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val icon = remember(pluginId, plugin) {
        if (pluginId == null || plugin == null) null
        else ExtensionManager.extractPluginIcon(context, pluginId, plugin, pluginInfo)
    }
    val protocols = pluginInfo?.capabilities?.backup?.protocols.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PluginIconView(
            icon = icon,
            category = PluginCategory.BACKUP
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pluginInfo?.name ?: pluginId ?: "未选择备份服务",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            if (!pluginInfo?.description.isNullOrBlank()) {
                Text(
                    text = pluginInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (protocols.isNotEmpty()) {
                Text(
                    text = "备份协议: " + protocols.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (onConfigure != null) {
            TextButton(onClick = onConfigure) {
                Text("配置", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            text = if (pluginId == null) "选择" else "切换",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

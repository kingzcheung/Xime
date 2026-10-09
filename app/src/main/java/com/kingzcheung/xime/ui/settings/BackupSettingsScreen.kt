package com.kingzcheung.xime.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore

import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.twotone.Backup
import androidx.compose.material.icons.twotone.CloudUpload
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.RimeExportManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步与备份设置页（交互形态版）。
 *
 * 只有两条链路（引擎原生 sync 文本快照为共同底座，按时间戳合并不互相覆盖）：
 * - **词条同步**：自造词的增量备份——本机整理 + 云端多设备合并，一个主动作；
 *   本地导入/导出与文本快照/码表互通属低频通道，收进弹层。
 * - **完整备份**：整机的时间点归档（设置/补丁 + 方案与资源 + 插件包 + 自造词快照），
 *   云端与本地出口同格；恢复是覆盖式，词条合并。
 *
 * 备份目标为已安装的 backup 类型插件（单选激活，与剪贴板同步同模式），
 * 包由宿主生成/恢复，服务器配置在所选插件的独立配置页。
 *
 * 交互设计（相对早期版本）：
 * - 状态前置：每张卡右上是"上次同步 / 上次备份"状态，服务区行首给在线状态点；
 * - 一个卡片一个主动作，云端列表/低频搬运收进可展开区与弹层，页面长度恒定；
 * - 结果反馈统一走 Snackbar；长操作在所属卡片内给线性进度条，只禁用冲突操作；
 * - 删除云端备份先确认（不可恢复），恢复文案里明示覆盖语义。
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
    val snackbar = remember { SnackbarHostState() }
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
    // 当前进行中的操作标识（"syncAll"/"backup"/"list"/"restore:<id>"/"delete:<id>"/…）：
    // 按操作独立；loading 除本操作按钮内圈外，还在所属卡片给一条细进度线
    var busyOp by remember { mutableStateOf<String?>(null) }
    val busy = busyOp != null
    var showServicePicker by remember { mutableStateOf(false) }
    var showDictSheet by remember { mutableStateOf(false) }
    var showLocalSheet by remember { mutableStateOf(false) }
    var remoteListExpanded by remember { mutableStateOf(false) }
    var snapshotListExpanded by remember { mutableStateOf(false) }
    // 恢复与删除都先确认：恢复=覆盖式落盘，删除=远端不可恢复
    var pendingRestoreEntry by remember { mutableStateOf<RemoteBackupEntry?>(null) }
    var pendingDeleteEntry by remember { mutableStateOf<RemoteBackupEntry?>(null) }
    var pendingLocalImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // 词条同步：上次时间 + 云端各设备快照（只读；合并由主动作完成）
    var lastSyncAt by remember { mutableStateOf(SettingsPreferences.getLastRimeSyncAt(context)) }
    var lastBackupAt by remember { mutableStateOf(SettingsPreferences.getLastBackupAt(context)) }
    var syncRemoteList by remember { mutableStateOf<List<RemoteBackupEntry>?>(null) }
    // 云端完整备份列表（null=未拉取；词条快照条目在拉取点过滤，这里只管备份包）
    var remoteList by remember { mutableStateOf<List<RemoteBackupEntry>?>(null) }
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
        scope.launch(Dispatchers.IO) {
            val result = SyncManager.importSnapshots(context, uris.toList())
            withContext(Dispatchers.Main) {
                busyOp = null
                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                snackbar.showSnackbar(
                    result.fold(
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

    //词条区主动作：本机整理（引擎原生 sync）+ 已配置服务时顺带云端多设备合并
    fun runWordSync() {
        val plugin = activePlugin?.second
        busyOp = "syncAll"
        scope.launch(Dispatchers.IO) {
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
                snackbar.showSnackbar(
                    result.fold(
                        onSuccess = { pulled ->
                            when {
                                plugin == null -> "已整理本机词条（未配置备份服务，未上云）"
                                pulled > 0 -> "已合并 $pulled 台设备的词条，并上传本机快照"
                                else -> "已上传本机快照（云端暂无其他设备）"
                            }
                        },
                        onFailure = { "同步失败：${it.message}" }
                    )
                )
            }
        }
    }

    //备份区主动作：刷新词条快照 → 打整机完整包 → 经备份插件上云（流式，不占内存）
    fun runCloudBackup() {
        val plugin = activePlugin?.second ?: return
        busyOp = "backup"
        scope.launch(Dispatchers.IO) {
            val result = BackupManager.backupNow(context, plugin)
            withContext(Dispatchers.Main) {
                busyOp = null
                if (result.ok) lastBackupAt = SettingsPreferences.getLastBackupAt(context)
                snackbar.showSnackbar(
                    if (result.ok) "备份完成" + (result.message ?: "")
                    else "备份失败：${result.message ?: "未知错误"}"
                )
            }
        }
    }

    //云端恢复：覆盖同名文件，词条快照由引擎就绪后按时间戳合并
    fun runRestore(entry: RemoteBackupEntry) {
        val plugin = activePlugin?.second ?: return
        busyOp = "restore:${entry.id}"
        scope.launch(Dispatchers.IO) {
            val result = BackupManager.restore(context, plugin, entry.id)
            withContext(Dispatchers.Main) {
                busyOp = null
                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                snackbar.showSnackbar(
                    if (result.isSuccess) "恢复完成，重启应用后生效；自造词快照将在引擎就绪后按时间戳合并"
                    else "恢复失败：${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun runDeleteRemote(entry: RemoteBackupEntry) {
        val plugin = activePlugin?.second ?: return
        busyOp = "delete:${entry.id}"
        scope.launch(Dispatchers.IO) {
            val ok = BackupManager.deleteRemote(plugin, entry.id)
            val fresh = if (ok) BackupManager.listRemote(plugin)?.filter { !SyncManager.isRemoteSyncEntry(it.name) } else null
            withContext(Dispatchers.Main) {
                busyOp = null
                if (ok) remoteList = fresh
                snackbar.showSnackbar(if (ok) "已删除该备份" else "删除失败")
            }
        }
    }

    // —— 刷新云端备份列表（过滤掉词条快照条目，它们属于词条区）——
    fun refreshRemoteList() {
        val plugin = activePlugin?.second ?: return
        busyOp = "list"
        scope.launch(Dispatchers.IO) {
            val list = BackupManager.listRemote(plugin)?.filter { !SyncManager.isRemoteSyncEntry(it.name) }
            val err = if (list == null) "获取备份列表失败" else null
            withContext(Dispatchers.Main) {
                busyOp = null
                remoteList = list
                err?.let { snackbar.showSnackbar(it) }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
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
            // ---------- 备份服务（词条同步与云端备份共用的通道） ----------
            SettingsSection(
                title = "备份服务",
                content = {
                    if (backupPlugins.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.TwoTone.Backup,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "未安装备份插件，云端通道不可用；本机整理与本地导入导出不受影响。",
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

            // ---------- 卡片一：词条同步（一个主动作） ----------
            SettingsSection(
                title = "词条（自造词）",
                modifier = Modifier.animateContentSize(),
                content = {
                    FeatureCardHeader(
                        icon = Icons.TwoTone.Sync,
                        title = "词条同步",
                        subtitle = "自造词 · 多设备按时间戳合并",
                        statusText = "上次同步：" + formatBackupTime(lastSyncAt),
                        statusReady = lastSyncAt > 0
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    WordCardActions(
                        busyOp = busyOp,
                        busy = busy,
                        onSync = ::runWordSync,
                        onSheet = { showDictSheet = true },
                        snapshotList = syncRemoteList,
                        expanded = snapshotListExpanded,
                        onToggleExpanded = { snapshotListExpanded = !snapshotListExpanded }
                    )
                }
            )

            // ---------- 卡片二：完整备份（一个主动作） ----------
            SettingsSection(
                title = "完整备份（配置与方案）",
                modifier = Modifier.animateContentSize(),
                content = {
                    FeatureCardHeader(
                        icon = Icons.TwoTone.Backup,
                        title = "完整备份",
                        subtitle = "整机快照：设置 · 方案与资源 · 插件包",
                        statusText = "上次备份：" + formatBackupTime(lastBackupAt),
                        statusReady = lastBackupAt > 0
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                    BackupCardActions(
                        busyOp = busyOp,
                        busy = busy,
                        hasPlugin = activePlugin != null,
                        onBackup = ::runCloudBackup,
                        remoteList = remoteList,
                        expanded = remoteListExpanded,
                        onToggleExpanded = {
                            remoteListExpanded = !remoteListExpanded
                            if (remoteListExpanded) refreshRemoteList()
                        },
                        onRestore = { pendingRestoreEntry = it },
                        onDelete = { pendingDeleteEntry = it },
                        onLocalSheet = { showLocalSheet = true }
                    )
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
                        scope.launch(Dispatchers.IO) {
                            val result = SyncManager.exportToDownloads(context)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                snackbar.showSnackbar(
                                    result.fold(
                                        onSuccess = { "已保存到下载目录：$it" },
                                        onFailure = { "导出失败：${it.message}" }
                                    )
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
                        scope.launch(Dispatchers.IO) {
                            val result = RimeExportManager.exportArchive(context)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                if (result.isSuccess) {
                                    lastBackupAt = SettingsPreferences.getLastBackupAt(context)
                                }
                                snackbar.showSnackbar(
                                    result.fold(
                                        onSuccess = { "已导出完整备份到下载目录：${it.fileName}" },
                                        onFailure = { "导出失败：${it.message}" }
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("保存完整备份到下载")
                }
                OutlinedButton(
                    onClick = {
                        showLocalSheet = false
                        localPackageLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) {
                    Text("从本地恢复")
                }
                Text(
                    text = "与云端「立即备份」同一个包格式（换机/离线可用）。从本地恢复为覆盖式，会先弹确认。",
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
                        // 重新获取已启用实例，驱动配置页入口与远端列表切换到新插件
                        activePlugin = ExtensionManager.getEnabledBackupPlugins(context)
                            .firstOrNull { it.first == pickedId }
                    }
                }
            },
            onDismiss = { showServicePicker = false }
        )
    }

    // 删除云端备份：不可恢复，先确认
    pendingDeleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEntry = null },
            title = { Text("删除云端备份") },
            text = { Text("将永久删除「${entry.name}」，删除后无法找回。确定删除？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteEntry = null
                    runDeleteRemote(entry)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEntry = null }) { Text("取消") }
            }
        )
    }

    // 云端恢复：覆盖式（自造词合并），先确认
    pendingRestoreEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRestoreEntry = null },
            title = { Text("恢复到本机") },
            text = {
                Text(
                    "把「${entry.name}」覆盖到本机：恢复同名配置/方案/插件与设置项；" +
                        "自造词按时间戳合并，本机新词不会丢。重启应用后生效。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreEntry = null
                    runRestore(entry)
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreEntry = null }) { Text("取消") }
            }
        )
    }

    // 本地完整方案包导入：覆盖式恢复，先确认再解包
    pendingLocalImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLocalImportUri = null },
            title = { Text("从本地恢复完整备份") },
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
                        scope.launch(Dispatchers.IO) {
                            val result = BackupManager.importLocal(context, uri)
                            withContext(Dispatchers.Main) {
                                busyOp = null
                                lastSyncAt = SettingsPreferences.getLastRimeSyncAt(context)
                                snackbar.showSnackbar(
                                    result.fold(
                                        onSuccess = { "已从本地恢复，重启应用后生效" },
                                        onFailure = { "导入失败：${it.message}" }
                                    )
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

/** 简短时间展示：同步与备份卡右上角的状态文案。 */
private fun formatBackupTime(at: Long): String =
    if (at > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(at)) else "从未"

/**
 * 功能卡头部：图标圆角底 + 标题 + 一句话副标题，右上角是状态（上次时间 + 状态点）。
 * 状态点只区分"做过（primary）/从未（outline）"，不造新色。
 */
@Composable
private fun FeatureCardHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    statusText: String,
    statusReady: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (statusReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
        }
    }
}

/** 词条卡主体：主动作 + 云端快照展开区 + 低频入口，按钮内的圈只在被点的那颗上转。 */
@Composable
private fun WordCardActions(
    busyOp: String?,
    busy: Boolean,
    onSync: () -> Unit,
    onSheet: () -> Unit,
    snapshotList: List<RemoteBackupEntry>?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busyOp == "syncAll") {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        Button(
            onClick = onSync,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busyOp == "syncAll") {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.TwoTone.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text("同步词条", modifier = Modifier.padding(start = 8.dp))
        }
        // 云端各设备快照：默认收起，每台设备一份固定名包；只读
        if (snapshotList != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy, onClick = onToggleExpanded)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "云端快照 · ${snapshotList.size} 份",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (expanded) {
                if (snapshotList.isEmpty()) {
                    Text(
                        text = "云端暂无快照；同步后其他设备可见本机快照并自动合并新词条。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    snapshotList.forEach { entry -> RemoteEntryMeta(entry, kbUnit = true) }
                }
            }
        }
        TextButton(
            onClick = onSheet,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("导入 / 导出词条  ›", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 备份卡主体：主动作行 + 云端列表展开区（恢复/删除）+ 本地低频入口。 */
@Composable
private fun BackupCardActions(
    busyOp: String?,
    busy: Boolean,
    hasPlugin: Boolean,
    onBackup: () -> Unit,
    remoteList: List<RemoteBackupEntry>?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRestore: (RemoteBackupEntry) -> Unit,
    onDelete: (RemoteBackupEntry) -> Unit,
    onLocalSheet: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val localBusy = busyOp == "exportLocal" || busyOp == "importLocal"
        if (localBusy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
        if (!hasPlugin) {
            Text(
                text = "未配置备份服务：云端备份不可用；可用下方本地出口。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onBackup,
                modifier = Modifier.weight(1f),
                enabled = !busy && hasPlugin
            ) {
                if (busyOp == "backup") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.TwoTone.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "立即备份",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            OutlinedButton(
                onClick = onToggleExpanded,
                modifier = Modifier.weight(1f),
                enabled = !busy && hasPlugin
            ) {
                if (busyOp == "list") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (expanded) "收起列表" else "云端备份列表",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
        // 云端备份包列表（恢复=覆盖式，自造词按时间戳合并）
        if (expanded && remoteList != null) {
            if (remoteList.isEmpty()) {
                Text(
                    text = "云端暂无备份",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            remoteList.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.bodySmall
                        )
                        RemoteEntryMetaRow(entry, mbUnit = true)
                    }
                    TextButton(
                        onClick = { onRestore(entry) },
                        enabled = !busy
                    ) {
                        if (busyOp == "restore:${entry.id}") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("恢复")
                        }
                    }
                    IconButton(
                        onClick = { onDelete(entry) },
                        enabled = !busy && busyOp != "delete:${entry.id}"
                    ) {
                        if (busyOp == "delete:${entry.id}") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        TextButton(
            onClick = onLocalSheet,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存到本地 / 从本地恢复  ›", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 云端条目的两行信息（词条区/备份区共用）。 */
@Composable
private fun RemoteEntryMeta(entry: RemoteBackupEntry, kbUnit: Boolean) {
    Column {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodySmall
        )
        RemoteEntryMetaRow(entry, mbUnit = !kbUnit)
    }
}

@Composable
private fun RemoteEntryMetaRow(entry: RemoteBackupEntry, mbUnit: Boolean) {
    Text(
        text = buildString {
            if (entry.createdAt > 0) {
                append(formatBackupTime(entry.createdAt))
            }
            if (entry.size >= 0) {
                if (isNotEmpty()) append(" · ")
                append(
                    if (mbUnit) String.format(Locale.getDefault(), "%.1f MB", entry.size / 1024.0 / 1024.0)
                    else String.format(Locale.getDefault(), "%.1f KB", entry.size / 1024.0)
                )
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
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

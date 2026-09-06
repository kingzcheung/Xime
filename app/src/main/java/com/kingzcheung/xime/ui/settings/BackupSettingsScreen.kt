package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Backup
import androidx.compose.material.icons.twotone.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.settings.BackupManager
import com.kingzcheung.xime.settings.ExportMode
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云备份（插件）设置页。
 *
 * 备份目标为已安装的 backup 类型插件（单选激活，与剪贴板同步同模式）；
 * 备份包由宿主 BackupManager 生成/恢复，服务器配置由所选插件的配置表单承载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsContent(
    onBack: () -> Unit,
    onNavigateToPlugins: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installedPlugins = remember { ExtensionManager.getAllInstalledPlugins() }
    val backupPlugins = remember { installedPlugins.filter { it.category == PluginCategory.BACKUP } }
    val syncPlugins = remember { ExtensionManager.getEnabledBackupPlugins(context) }
    var selectedPluginId by remember {
        mutableStateOf(
            SettingsPreferences.getBackupPluginId(context).ifEmpty {
                syncPlugins.firstOrNull()?.first ?: ""
            }
        )
    }
    var activePlugin by remember {
        mutableStateOf(
            syncPlugins.firstOrNull { it.first == selectedPluginId } ?: syncPlugins.firstOrNull()
        )
    }
    var backupMode by remember { mutableStateOf(ExportMode.CONFIG_ONLY) }
    // 当前进行中的操作标识（"backup"/"list"/"restore:<id>"/"delete:<id>"）：
    // 按操作独立，loading 只出现在触发它的按钮上，其余按钮仅禁用
    var busyOp by remember { mutableStateOf<String?>(null) }
    val busy = busyOp != null
    var message by remember { mutableStateOf<String?>(null) }
    var remoteList by remember { mutableStateOf<List<com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry>?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("云备份") },
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (backupPlugins.isEmpty()) {
                            Text(
                                text = "未安装备份插件，请先在插件中心安装后再配置。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onNavigateToPlugins,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("前往插件中心")
                            }
                        } else {
                            backupPlugins.forEach { plugin ->
                                val isActive = plugin.id == selectedPluginId
                                val protocols = plugin.capabilities?.backup?.protocols.orEmpty()
                                SettingsToggleItem(
                                    icon = Icons.TwoTone.Backup,
                                    title = plugin.name,
                                    subtitle = buildString {
                                        append(plugin.description)
                                        if (protocols.isNotEmpty()) {
                                            append("\n备份协议: ")
                                            append(protocols.joinToString("、"))
                                        }
                                    },
                                    checked = isActive,
                                    onCheckedChange = { checked ->
                                        if (checked && !isActive) {
                                            selectedPluginId = plugin.id
                                            SettingsPreferences.setBackupPluginId(context, plugin.id)
                                            remoteList = null
                                            scope.launch(Dispatchers.IO) {
                                                // 单选激活：同一时间只启用 1 个备份插件
                                                backupPlugins
                                                    .filter { it.id != plugin.id }
                                                    .forEach {
                                                        SettingsPreferences.setPluginEnabled(context, it.id, false)
                                                        PluginManager.unloadPlugin(it.id)
                                                    }
                                                SettingsPreferences.setPluginEnabled(context, plugin.id, true)
                                                PluginManager.launchPlugin(plugin.id)
                                                val instance = ExtensionManager.getEnabledBackupPlugins(context)
                                                    .firstOrNull { it.first == plugin.id }
                                                activePlugin = instance
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            )

            activePlugin?.let { selected ->
                val plugin = selected.second
                SettingsSection(
                    title = "备份设置",
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ExportMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = backupMode == mode,
                                        onClick = { backupMode = mode }
                                    )
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            if (backupMode == ExportMode.CONFIG_ONLY) {
                                Text(
                                    text = "仅配置：不含用户词典（.db）与模型（.bin/.gram）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                )

                SettingsSection(
                    title = "备份操作",
                    content = {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        busyOp = "backup"
                                        message = null
                                        scope.launch(Dispatchers.IO) {
                                            val result = BackupManager.backupNow(context, plugin, backupMode)
                                            withContext(Dispatchers.Main) {
                                                busyOp = null
                                                message = if (result.ok) "备份完成"
                                                else "备份失败：${result.message ?: "未知错误"}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
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
                                        busyOp = "list"
                                        message = null
                                        scope.launch(Dispatchers.IO) {
                                            val list = BackupManager.listRemote(plugin)
                                            val err = if (list == null) "获取备份列表失败" else null
                                            withContext(Dispatchers.Main) {
                                                busyOp = null
                                                remoteList = list
                                                message = err
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !busy
                                ) {
                                    Text("查看远端备份", fontSize = 12.sp)
                                }
                            }
                            message?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )

                remoteList?.let { list ->
                    SettingsSection(
                        title = "远端备份",
                        content = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (list.isEmpty()) {
                                    Text(
                                        text = "远端暂无备份",
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
                                                            message = if (result.isSuccess) "恢复完成，重启应用后生效"
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
                                                        val fresh = if (ok) BackupManager.listRemote(plugin) else null
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
                                Text(
                                    text = "恢复覆盖 rime/ 目录同名文件（含自造词），并还原插件、插件配置与设置项；重启应用后全部生效。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    )
                }

                // 服务器地址等配置由插件表单承载（host.config）
                PluginConfigFormScreen(
                    pluginId = selected.first,
                    plugin = plugin,
                    pluginName = installedPlugins.find { it.id == selected.first }?.name ?: selected.first,
                    onBack = {},
                    embedded = true
                )
            }
        }
    }
}

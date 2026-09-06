package com.kingzcheung.xime.plugin.core.runtime.installer

import android.app.Application
import android.net.Uri
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.PluginToolbarButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipFile

/** manifest.yaml 解析结果。 */
internal sealed class PluginParseResult {
    data class Success(val config: PluginConfig) : PluginParseResult()
    data class Failure(val reason: String) : PluginParseResult()
}

internal data class PluginConfig(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val type: String,
    val minHostVersion: String?,
    val maxHostVersion: String?,
    val entryScript: String?,
    val declaredHosts: List<String> = emptyList(),
    val allowCustomHosts: Boolean = false,
    val toolbarButtons: List<PluginToolbarButton> = emptyList(),
    val icon: String? = null,
    val capabilities: com.kingzcheung.xime.plugin.core.model.PluginCapabilities? = null
)

/** manifest.yaml 的类型化模型，与宿主一起用 kaml 解析。 */
@Serializable
internal data class PluginManifest(
    val id: String,
    val name: String? = null,
    val type: String = "unknown",
    val entry: String = "main.lua",
    val version: String = "0.0.0",
    val description: String? = null,
    val minHostVersion: String? = null,
    val maxHostVersion: String? = null,
    val network: NetworkConfig? = null,
    val toolbarButtons: List<ToolbarButtonConfig> = emptyList(),
    /** 顶层 icon：文字（如 "译"）或 resources/ 下图片文件名。 */
    val icon: String? = null,
    val capabilities: CapabilitiesConfig? = null
)

@Serializable
internal data class NetworkConfig(
    val hosts: List<String> = emptyList(),
    val allowCustomHosts: Boolean = false
)

/** manifest.toolbarButtons 单条（类型化）。 */
@Serializable
internal data class ToolbarButtonConfig(
    val id: String,
    val label: String = "",
    val icon: String? = null,
    val action: String = "open_panel"
)

/** manifest.capabilities 能力声明（类型化）。 */
@Serializable
internal data class CapabilitiesConfig(
    val emoji: EmojiCapabilitiesConfig? = null,
    val speech: SpeechCapabilitiesConfig? = null,
    val tool: ToolCapabilitiesConfig? = null,
    @kotlinx.serialization.SerialName("clipboard_sync")
    val clipboardSync: ClipboardSyncCapabilitiesConfig? = null,
    @kotlinx.serialization.SerialName("backup")
    val backup: BackupCapabilitiesConfig? = null,
    /** 下行事件订阅（如 "input_changed"），小写 snake_case。 */
    val events: List<String> = emptyList(),
    /** 候选词变换能力（hotPath，硬超时 15ms）。 */
    @kotlinx.serialization.SerialName("candidate_transform")
    val candidateTransform: Boolean = false,
    /** 快捷发送只读能力（注入 host.quickSend）。 */
    @kotlinx.serialization.SerialName("quick_send_read")
    val quickSendRead: Boolean = false,
    /** 剪贴板只读能力（注入 host.clipboard）。 */
    @kotlinx.serialization.SerialName("clipboard_read")
    val clipboardRead: Boolean = false
)

@Serializable
internal data class EmojiCapabilitiesConfig(
    val supportsSearch: Boolean = false,
    val columns: Int? = null,
    val itemHeightDp: Int? = null
)

@Serializable
internal data class SpeechCapabilitiesConfig(
    val inputMode: String = "streaming",
    val supportsPartialResults: Boolean = true,
    val requiresNetwork: Boolean = true
)

@Serializable
internal data class ToolCapabilitiesConfig(
    val display: String? = null
)

@Serializable
internal data class ClipboardSyncCapabilitiesConfig(
    val protocols: List<String> = emptyList()
)

@Serializable
internal data class BackupCapabilitiesConfig(
    val protocols: List<String> = emptyList()
)

/** manifest 能力声明 → 类型化模型（未知字段静默忽略，非法枚举值按未声明处理）。 */
private fun CapabilitiesConfig.toModel(): com.kingzcheung.xime.plugin.core.model.PluginCapabilities {
    return com.kingzcheung.xime.plugin.core.model.PluginCapabilities(
        emoji = emoji?.let {
            com.kingzcheung.xime.plugin.core.model.PluginCapabilities.EmojiCapabilities(
                supportsSearch = it.supportsSearch,
                columns = it.columns?.takeIf { c -> c > 0 },
                itemHeightDp = it.itemHeightDp?.takeIf { h -> h > 0 }
            )
        },
        speech = speech?.let {
            com.kingzcheung.xime.plugin.core.model.PluginCapabilities.SpeechCapabilities(
                inputMode = it.inputMode,
                supportsPartialResults = it.supportsPartialResults,
                requiresNetwork = it.requiresNetwork
            )
        },
        tool = tool?.let {
            com.kingzcheung.xime.plugin.core.model.PluginCapabilities.ToolCapabilities(
                display = when (it.display?.lowercase()) {
                    "direct" -> com.kingzcheung.xime.plugin.core.api.ToolResult.DIRECT
                    "passive" -> com.kingzcheung.xime.plugin.core.api.ToolResult.PASSIVE
                    // 旧契约 "select"（全屏结果页）已并入 passive（InfoPanel 内 items 点选上屏）
                    "select" -> com.kingzcheung.xime.plugin.core.api.ToolResult.PASSIVE
                    else -> null
                }
            )
        },
        clipboardSync = clipboardSync?.let {
            com.kingzcheung.xime.plugin.core.model.PluginCapabilities.ClipboardSyncCapabilities(
                protocols = it.protocols.filter { p -> p.isNotBlank() }
            )
        },
        backup = backup?.let {
            com.kingzcheung.xime.plugin.core.model.PluginCapabilities.BackupCapabilities(
                protocols = it.protocols.filter { p -> p.isNotBlank() }
            )
        },
        events = events.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct(),
        candidateTransform = candidateTransform,
        quickSendRead = quickSendRead,
        clipboardRead = clipboardRead
    )
}

/**
 * 插件安装器（Lua 脚本插件）。
 *
 * 插件包为 zip（.xipk），结构：
 *   manifest.yaml   元数据（宿主解析）
 *   main.lua        入口脚本（宿主 Lua 沙箱执行）
 *   libs/           纯 Lua 依赖库
 *   resources/      资源文件
 *
 * 安装 = 解压到 files/plugins/<id>/ + 解析 manifest 写入注册表。
 */
class InstallerManager(
    private val context: Application,
    private val xmlManager: XmlManager
) {
    companion object {
        private const val PLUGINS_DIR = "plugins"
        private const val MANIFEST_YAML = "manifest.yaml"

        /** xipk 包大小上限（10MB）：插件是脚本+资源，超过即拒绝安装。 */
        private const val MAX_ARCHIVE_FILE_BYTES = 10 * 1024 * 1024

        /** 解压条目数与解压后总体积上限（防 zip bomb）。 */
        private const val MAX_ARCHIVE_ENTRIES = 512
        private const val MAX_ARCHIVE_TOTAL_BYTES = 64 * 1024 * 1024

        /** 插件 id 白名单：字母/数字/下划线/连字符，点号仅作命名空间分段（禁止 .. / 空段 / /），最长 64，杜绝路径穿越。 */
        private val PLUGIN_ID_REGEX = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$")
        private const val PLUGIN_ID_MAX_LENGTH = 64

        /** 入口脚本：普通文件名，不允许路径分隔符与 ".."。 */
        private val ENTRY_SCRIPT_REGEX = Regex("^[A-Za-z0-9_.-]{1,128}$")

        /** 网络声明域名：合法域名或 IPv4（禁止通配/空白/超长，授权 UI 直接展示，需可读可信）。 */
        private val DECLARED_HOST_REGEX = Regex(
            "^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$"
        )

        internal fun isValidPluginId(id: String): Boolean =
            id.length <= PLUGIN_ID_MAX_LENGTH && PLUGIN_ID_REGEX.matches(id)

        private fun isValidEntryScript(name: String): Boolean =
            ENTRY_SCRIPT_REGEX.matches(name) && !name.contains("..")

        /** 网络声明域名是否合法（域名/IPv4，供 manifest 解析时过滤）。 */
        internal fun isValidDeclaredHost(host: String): Boolean =
            DECLARED_HOST_REGEX.matches(host)

        /** 插件包内资源相对路径：允许子目录（/ 分隔），禁止 .. 穿越、绝对路径与反斜杠。 */
        fun isValidResourcePath(name: String): Boolean {
            if (name.isBlank() || name.length > 256) return false
            if (name.startsWith("/") || name.contains("\\")) return false
            return name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        }

        /** 工具栏按钮 id：非空；禁止逗号（偏好存储按逗号分隔）、XML 特殊字符与换行/空白控制符。
         *  全局限定 id 建议带插件命名空间（如 `pluginId:action`）。 */
        internal fun isValidToolbarButtonId(id: String?): Boolean {
            if (id.isNullOrBlank()) return false
            if (id.length > 64) return false
            return !id.any { it in ",\u003C\u003E\"'&|\\\n\r\t " }
        }


        private val manifestYaml: Yaml by lazy {
            Yaml(configuration = YamlConfiguration(strictMode = false))
        }

        /** 解析 manifest.yaml 文本（kam 类型化解析），失败时携带可读的错误提示。 */
        internal fun parseManifestContent(content: String): PluginParseResult = try {
            val manifest = manifestYaml.decodeFromString(PluginManifest.serializer(), content)
            val declaredHosts = manifest.network?.hosts.orEmpty()
                .filter { it.isNotBlank() && isValidDeclaredHost(it) }
            val toolbarButtons = manifest.toolbarButtons
                .filter { isValidToolbarButtonId(it.id) }
                .map {
                    PluginToolbarButton(
                        id = it.id,
                        label = it.label,
                        icon = it.icon?.takeIf { i -> i.isNotBlank() },
                        action = it.action.ifBlank { "open_panel" }
                    )
                }

            PluginParseResult.Success(
                PluginConfig(
                    id = manifest.id,
                    name = manifest.name ?: manifest.id,
                    version = manifest.version,
                    description = manifest.description ?: "",
                    type = manifest.type,
                    minHostVersion = manifest.minHostVersion?.takeIf { it.isNotBlank() },
                    maxHostVersion = manifest.maxHostVersion?.takeIf { it.isNotBlank() },
                    entryScript = manifest.entry,
                    declaredHosts = declaredHosts,
                    allowCustomHosts = manifest.network?.allowCustomHosts ?: false,
                    toolbarButtons = toolbarButtons,
                    icon = manifest.icon?.takeIf { it.isNotBlank() },
                    capabilities = manifest.capabilities?.toModel()
                )
            )
        } catch (e: Exception) {
            Log.e("InstallerManager", "parsePluginConfig yaml failed", e)
            PluginParseResult.Failure(manifestError(e))
        }

        /** 把 manifest 解析异常整理成可读的提示（kaml 消息含行号/字段）。 */
        private fun manifestError(e: Exception): String {
            val detail = e.message
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: e.javaClass.simpleName
            return "manifest.yaml 解析失败：$detail"
        }
    }

    sealed class InstallResult {
        data class Success(val pluginInfo: PluginInfo) : InstallResult()
        data class Failure(val reason: String, val exception: Throwable? = null) : InstallResult()
    }

    private val pluginsDir: File by lazy {
        File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }
    }

    suspend fun installPlugin(
        pluginFile: File,
        forceOverwrite: Boolean = false,
        source: PluginSource = PluginSource.FILE
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!pluginFile.exists()) {
            return@withContext InstallResult.Failure("插件文件不存在")
        }
        if (pluginFile.length() > MAX_ARCHIVE_FILE_BYTES) {
            return@withContext InstallResult.Failure(
                "插件包超过大小上限（${MAX_ARCHIVE_FILE_BYTES / 1024 / 1024}MB），拒绝安装"
            )
        }

        val pluginConfig = when (val parsed = parsePluginConfig(pluginFile)) {
            is PluginParseResult.Failure -> return@withContext InstallResult.Failure(parsed.reason)
            is PluginParseResult.Success -> parsed.config
        }
        val pluginId = pluginConfig.id
        if (!isValidPluginId(pluginId)) {
            return@withContext InstallResult.Failure("非法插件 id: $pluginId（仅允许字母/数字/下划线/连字符，点号分段，最长 64）")
        }
        val entryScript = pluginConfig.entryScript ?: "main.lua"
        if (!isValidEntryScript(entryScript)) {
            return@withContext InstallResult.Failure("非法入口脚本: $entryScript")
        }
        val pluginDir = getPluginDirectory(pluginId)

        // 校验插件声明的宿主版本范围
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(context)
        if (hostVersion != null &&
            !com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion, pluginConfig.minHostVersion, pluginConfig.maxHostVersion
            )
        ) {
            val range = buildString {
                append("当前主应用版本 v$hostVersion 不在插件支持范围内")
                if (!pluginConfig.minHostVersion.isNullOrBlank()) {
                    append("（最低 v${pluginConfig.minHostVersion}")
                    if (!pluginConfig.maxHostVersion.isNullOrBlank()) {
                        append(" - v${pluginConfig.maxHostVersion}")
                    }
                    append("）")
                }
            }
            return@withContext InstallResult.Failure(range)
        }

        val existingPlugin = xmlManager.getPluginById(pluginId)

        // Lua 插件无版本号概念：只有首次安装或强制覆盖才重新解压
        if (!forceOverwrite && existingPlugin != null) {
            return@withContext InstallResult.Success(existingPlugin)
        }

        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        pluginDir.mkdirs()

        try {
            extractPluginArchive(pluginFile, pluginDir)
            val entryFile = File(pluginDir, entryScript)
            if (!entryFile.exists()) {
                throw IllegalArgumentException("Lua 入口脚本不存在: $entryScript")
            }

            val pluginInfo = PluginInfo(
                id = pluginConfig.id,
                name = pluginConfig.name,
                iconResId = 0,
                description = pluginConfig.description,
                versionCode = 0,
                versionName = pluginConfig.version,
                path = entryFile.absolutePath,
                type = pluginConfig.type,
                enabled = existingPlugin?.enabled ?: true,
                installTime = existingPlugin?.installTime ?: System.currentTimeMillis(),
                source = source,
                minHostVersion = pluginConfig.minHostVersion,
                maxHostVersion = pluginConfig.maxHostVersion,
                trustLevel = com.kingzcheung.xime.plugin.core.util.PluginSignatureUtil.classifyLuaPlugin(source),
                entryScript = entryScript,
                declaredHosts = pluginConfig.declaredHosts,
                allowCustomHosts = pluginConfig.allowCustomHosts,
                toolbarButtons = pluginConfig.toolbarButtons,
                manifestIcon = pluginConfig.icon,
                capabilities = pluginConfig.capabilities
            )

            if (existingPlugin != null) {
                xmlManager.updatePlugin(pluginInfo)
            } else {
                xmlManager.addPlugin(pluginInfo)
            }
            xmlManager.flushToDisk()

            InstallResult.Success(pluginInfo)
        } catch (e: Exception) {
            pluginDir.deleteRecursively()
            InstallResult.Failure("插件安装失败: ${e.message}", e)
        }
    }

    suspend fun uninstallPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidPluginId(pluginId)) {
            Log.e("InstallerManager", "uninstallPlugin 拒绝非法 id: $pluginId")
            return@withContext false
        }
        val pluginDir = getPluginDirectory(pluginId)
        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        xmlManager.removePlugin(pluginId)
        xmlManager.flushToDisk()
        true
    }

    suspend fun installPluginFromUri(uri: Uri): InstallResult = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return@withContext InstallResult.Failure("无法解析文件路径"))
            installPlugin(file, forceOverwrite = true, source = PluginSource.FILE)
        } else {
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.xipk")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext InstallResult.Failure("无法读取文件")
                installPlugin(tempFile, forceOverwrite = true, source = PluginSource.FILE)
            } catch (e: Exception) {
                InstallResult.Failure("插件导入失败: ${e.message}", e)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    internal fun getPluginDirectory(pluginId: String): File {
        require(isValidPluginId(pluginId)) { "非法插件 id: $pluginId" }
        return File(pluginsDir, pluginId)
    }

    /** 解压 Lua 插件包到插件目录（防 zip-slip 路径穿越与 zip bomb 解压膨胀）。 */
    private fun extractPluginArchive(archiveFile: File, pluginDir: File) {
        ZipFile(archiveFile).use { zip ->
            var entryCount = 0
            var totalBytes = 0L
            for (entry in zip.entries()) {
                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw IllegalArgumentException("插件包条目数超过上限（$MAX_ARCHIVE_ENTRIES）")
                }
                if (entry.isDirectory) continue
                if (entry.size > 0) totalBytes += entry.size
                if (totalBytes > MAX_ARCHIVE_TOTAL_BYTES) {
                    throw IllegalArgumentException("插件包解压体积超过上限（${MAX_ARCHIVE_TOTAL_BYTES / 1024 / 1024}MB）")
                }
                // Windows 打包工具可能产生 "\" 分隔的条目名，统一规范为 "/"
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("lib/")) continue
                if (name.contains("../") || name.startsWith("/")) {
                    throw IllegalArgumentException("非法路径: $name")
                }
                val outputFile = File(pluginDir, name)
                outputFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun parsePluginConfig(pluginFile: File): PluginParseResult {
        val content = try {
            ZipFile(pluginFile).use { zip ->
                val entry = zip.getEntry(MANIFEST_YAML)
                    ?: return PluginParseResult.Failure("插件配置解析失败（缺少 manifest.yaml）")
                zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e("InstallerManager", "parsePluginConfig failed", e)
            return PluginParseResult.Failure("插件配置解析失败：${e.message}")
        }

        return parseManifestContent(content)
    }
}

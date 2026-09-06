package com.kingzcheung.xime.plugin.core.runtime.installer

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.model.PluginToolbarButton
import java.io.File

class XmlManager(private val context: Application) {
    companion object {
        private const val PLUGINS_XML = "plugins.xml"
    }

    private val pluginsFile: File by lazy {
        File(context.filesDir, PLUGINS_XML)
    }

    private val plugins = mutableMapOf<String, PluginInfo>()

    init {
        loadFromDisk()
    }

    fun getAllPlugins(): List<PluginInfo> = plugins.values.toList()

    fun getPluginById(id: String): PluginInfo? = plugins[id]

    fun addPlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun updatePlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun removePlugin(id: String) {
        plugins.remove(id)
    }

    fun flushToDisk() {
        try {
            // 原子写入：先写临时文件再 rename，避免崩溃损坏注册表（覆盖式 rename 在 Android 上可靠）
            val tmp = File(pluginsFile.parentFile, "${pluginsFile.name}.tmp")
            tmp.bufferedWriter().use { writer ->
                writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                writer.write("<plugins>\n")
                for (plugin in plugins.values) {
                    writer.write("  <plugin>\n")
                    writer.write("    <id>${escapeXml(plugin.id)}</id>\n")
                    writer.write("    <name>${escapeXml(plugin.name)}</name>\n")
                    writer.write("    <description>${escapeXml(plugin.description)}</description>\n")
                    writer.write("    <versionCode>${plugin.versionCode}</versionCode>\n")
                    writer.write("    <versionName>${escapeXml(plugin.versionName)}</versionName>\n")
                    writer.write("    <path>${escapeXml(plugin.path)}</path>\n")
                    writer.write("    <type>${escapeXml(plugin.type)}</type>\n")
                    writer.write("    <enabled>${plugin.enabled}</enabled>\n")
                    writer.write("    <installTime>${plugin.installTime}</installTime>\n")
                    writer.write("    <source>${plugin.source.name}</source>\n")
                    writer.write("    <iconResId>${plugin.iconResId}</iconResId>\n")
                    writer.write("    <trustLevel>${plugin.trustLevel.name}</trustLevel>\n")
                    if (plugin.minHostVersion != null) {
                        writer.write("    <minHostVersion>${escapeXml(plugin.minHostVersion)}</minHostVersion>\n")
                    }
                    if (plugin.maxHostVersion != null) {
                        writer.write("    <maxHostVersion>${escapeXml(plugin.maxHostVersion)}</maxHostVersion>\n")
                    }
                    if (plugin.entryScript != null) {
                        writer.write("    <entryScript>${escapeXml(plugin.entryScript)}</entryScript>\n")
                    }
                    if (plugin.declaredHosts.isNotEmpty()) {
                        writer.write("    <networkHosts>${escapeXml(plugin.declaredHosts.joinToString(","))}</networkHosts>\n")
                    }
                    if (plugin.allowCustomHosts) {
                        writer.write("    <allowCustomHosts>true</allowCustomHosts>\n")
                    }
                    for (button in plugin.toolbarButtons) {
                        writer.write("    <toolbarButton id=\"${escapeXml(button.id)}\" label=\"${escapeXml(button.label)}\"")
                        if (button.icon != null) {
                            writer.write(" icon=\"${escapeXml(button.icon)}\"")
                        }
                        writer.write(" action=\"${escapeXml(button.action)}\"/>\n")
                    }
                    if (plugin.manifestIcon != null) {
                        writer.write("    <manifestIcon>${escapeXml(plugin.manifestIcon)}</manifestIcon>\n")
                    }
                    if (plugin.capabilities != null) {
                        writer.write("    <capabilities>${escapeXml(encodeCapabilities(plugin.capabilities))}</capabilities>\n")
                    }
                    writer.write("  </plugin>\n")
                }
                writer.write("</plugins>\n")
            }
            if (pluginsFile.exists() && !pluginsFile.delete()) {
                Log.w("XmlManager", "删除旧注册表失败，rename 将覆盖")
            }
            if (!tmp.renameTo(pluginsFile)) {
                Log.e("XmlManager", "注册表写入失败：rename 失败")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromDisk() {
        if (!pluginsFile.exists()) return

        try {
            val content = pluginsFile.readText()
            parsePluginsXml(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parsePluginsXml(content: String) {
        val pluginRegex = Regex("<plugin>(.*?)</plugin>", RegexOption.DOT_MATCHES_ALL)
        val matches = pluginRegex.findAll(content)

        for (match in matches) {
            val pluginContent = match.groupValues[1]
            val id = extractTag(pluginContent, "id")
            val name = extractTag(pluginContent, "name")
            val description = extractTag(pluginContent, "description")
            val versionCode = extractTag(pluginContent, "versionCode")?.toLongOrNull() ?: 0
            val versionName = extractTag(pluginContent, "versionName") ?: ""
            val path = extractTag(pluginContent, "path")
            val type = extractTag(pluginContent, "type") ?: "unknown"
            val enabled = extractTag(pluginContent, "enabled")?.toBoolean() ?: true
            val installTime = extractTag(pluginContent, "installTime")?.toLongOrNull() ?: System.currentTimeMillis()
            val source = extractTag(pluginContent, "source")
                ?.let { runCatching { PluginSource.valueOf(it) }.getOrNull() }
                ?: PluginSource.SYSTEM
            val iconResId = extractTag(pluginContent, "iconResId")?.toIntOrNull() ?: 0
            val minHostVersion = extractTag(pluginContent, "minHostVersion")?.takeIf { it.isNotBlank() }
            val maxHostVersion = extractTag(pluginContent, "maxHostVersion")?.takeIf { it.isNotBlank() }
            val entryScript = extractTag(pluginContent, "entryScript")?.takeIf { it.isNotBlank() }
            val networkHosts = extractTag(pluginContent, "networkHosts")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val allowCustomHosts = extractTag(pluginContent, "allowCustomHosts")?.toBoolean() ?: false
            val toolbarButtons = parseToolbarButtons(pluginContent)
            val trustLevel = com.kingzcheung.xime.plugin.core.util.PluginSignatureUtil.classifyLuaPlugin(source)
            val manifestIcon = extractTag(pluginContent, "manifestIcon")?.takeIf { it.isNotBlank() }
            val capabilities = extractTag(pluginContent, "capabilities")
                ?.takeIf { it.isNotBlank() }
                ?.let { decodeCapabilities(it) }

            if (id != null && path != null) {
                plugins[id] = PluginInfo(
                    id = id,
                    name = name ?: "",
                    iconResId = iconResId,
                    description = description ?: "",
                    versionCode = versionCode,
                    versionName = versionName,
                    path = path,
                    type = type,
                    enabled = enabled,
                    installTime = installTime,
                    source = source,
                    minHostVersion = minHostVersion,
                    maxHostVersion = maxHostVersion,
                    trustLevel = trustLevel,
                    entryScript = entryScript,
                    declaredHosts = networkHosts,
                    allowCustomHosts = allowCustomHosts,
                    toolbarButtons = toolbarButtons,
                    manifestIcon = manifestIcon,
                    capabilities = capabilities
                )
            }
        }
    }

    private fun parseToolbarButtons(pluginContent: String): List<PluginToolbarButton> {
        val regex = Regex("<toolbarButton\\b([^>]*)/>")
        return regex.findAll(pluginContent).mapNotNull { m ->
            val tag = m.groupValues[1]
            val id = extractAttribute(tag, "id") ?: return@mapNotNull null
            val rawIcon = extractAttribute(tag, "icon")
            PluginToolbarButton(
                id = unescapeXml(id),
                label = unescapeXml(extractAttribute(tag, "label").orEmpty()),
                icon = rawIcon?.let { if (it.isEmpty()) null else unescapeXml(it) },
                action = unescapeXml(extractAttribute(tag, "action").orEmpty())
                    .ifBlank { "open_panel" }
            )
        }.toList()
    }

    private fun extractAttribute(tag: String, name: String): String? {
        val regex = Regex("(?:^|\\s)$name=\"([^\"]*)\"")
        return regex.find(tag)?.groupValues?.get(1)
    }

    private fun extractTag(content: String, tagName: String): String? {
        val regex = Regex("<$tagName>(.*?)</$tagName>")
        return regex.find(content)?.groupValues?.get(1)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun unescapeXml(text: String): String {
        return text
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    /** capabilities → JSON 文本（便于单个 XML 节点持久化）。 */
    private fun encodeCapabilities(cap: com.kingzcheung.xime.plugin.core.model.PluginCapabilities): String {
        val root = mutableMapOf<String, Any?>()
        cap.emoji?.let {
            root["emoji"] = mapOf(
                "supportsSearch" to it.supportsSearch,
                "columns" to it.columns,
                "itemHeightDp" to it.itemHeightDp
            )
        }
        cap.speech?.let {
            root["speech"] = mapOf(
                "inputMode" to it.inputMode,
                "supportsPartialResults" to it.supportsPartialResults,
                "requiresNetwork" to it.requiresNetwork
            )
        }
        cap.tool?.let {
            root["tool"] = mapOf("display" to it.display?.name)
        }
        cap.clipboardSync?.let {
            root["clipboardSync"] = mapOf("protocols" to it.protocols)
        }
        cap.backup?.let {
            root["backup"] = mapOf("protocols" to it.protocols)
        }
        if (cap.events.isNotEmpty()) {
            root["events"] = cap.events
        }
        if (cap.candidateTransform) {
            root["candidateTransform"] = true
        }
        if (cap.quickSendRead) {
            root["quickSendRead"] = true
        }
        if (cap.clipboardRead) {
            root["clipboardRead"] = true
        }
        return com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.encode(root) ?: ""
    }

    /** capabilities JSON 文本 → 类型化模型（解析失败返回 null，不影响插件加载）。 */
    private fun decodeCapabilities(json: String): com.kingzcheung.xime.plugin.core.model.PluginCapabilities? {
        return runCatching {
            val root = com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.decode(json)
                as? Map<*, *> ?: return null
            com.kingzcheung.xime.plugin.core.model.PluginCapabilities(
                emoji = (root["emoji"] as? Map<*, *>)?.let { m ->
                    com.kingzcheung.xime.plugin.core.model.PluginCapabilities.EmojiCapabilities(
                        supportsSearch = (m["supportsSearch"] as? Boolean) ?: false,
                        columns = (m["columns"] as? Number)?.toInt(),
                        itemHeightDp = (m["itemHeightDp"] as? Number)?.toInt()
                    )
                },
                speech = (root["speech"] as? Map<*, *>)?.let { m ->
                    com.kingzcheung.xime.plugin.core.model.PluginCapabilities.SpeechCapabilities(
                        inputMode = (m["inputMode"] as? String) ?: "streaming",
                        supportsPartialResults = (m["supportsPartialResults"] as? Boolean) ?: true,
                        requiresNetwork = (m["requiresNetwork"] as? Boolean) ?: true
                    )
                },
                tool = (root["tool"] as? Map<*, *>)?.let { m ->
                    com.kingzcheung.xime.plugin.core.model.PluginCapabilities.ToolCapabilities(
                        display = (m["display"] as? String)?.let { s ->
                            // 旧契约 "select"（全屏结果页）已并入 passive（InfoPanel 内 items 点选上屏）
                            if (s.equals("select", ignoreCase = true)) com.kingzcheung.xime.plugin.core.api.ToolResult.PASSIVE
                            else com.kingzcheung.xime.plugin.core.api.ToolResult.entries
                                .firstOrNull { it.name.equals(s, ignoreCase = true) }
                        }
                    )
                },
                clipboardSync = (root["clipboardSync"] as? Map<*, *>)?.let { m ->
                    com.kingzcheung.xime.plugin.core.model.PluginCapabilities.ClipboardSyncCapabilities(
                        protocols = (m["protocols"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    )
                },
                backup = (root["backup"] as? Map<*, *>)?.let { m ->
                    com.kingzcheung.xime.plugin.core.model.PluginCapabilities.BackupCapabilities(
                        protocols = (m["protocols"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                    )
                },
                events = (root["events"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                candidateTransform = (root["candidateTransform"] as? Boolean) ?: false,
                quickSendRead = (root["quickSendRead"] as? Boolean) ?: false,
                clipboardRead = (root["clipboardRead"] as? Boolean) ?: false
            )
        }.getOrNull()
    }
}

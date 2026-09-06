package com.kingzcheung.xime.plugin.core.runtime.lifecycle

import android.app.Application
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.lua.LuaAsrPluginAdapter
import com.kingzcheung.xime.plugin.core.lua.LuaBackupPluginAdapter
import com.kingzcheung.xime.plugin.core.lua.LuaClipboardSyncPluginAdapter
import com.kingzcheung.xime.plugin.core.lua.LuaEmojiPluginAdapter
import com.kingzcheung.xime.plugin.core.lua.LuaPluginAdapter
import com.kingzcheung.xime.plugin.core.lua.LuaScriptRuntime
import com.kingzcheung.xime.plugin.core.lua.LuaToolPluginAdapter
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginContext
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.runtime.installer.XmlManager
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class PluginLifecycleManager(
    private val application: Application,
    private val xmlManager: XmlManager,
    private val installerManager: InstallerManager,
    private val loadedPlugins: ConcurrentHashMap<String, LoadedPluginInfo>,
    private val pluginInstances: ConcurrentHashMap<String, IPluginEntryClass>
) {

    companion object {
        private const val TAG = "PluginLifecycle"
    }

    suspend fun launchPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (loadedPlugins.containsKey(pluginId)) {
                return@withContext reloadPlugin(pluginId)
            }
            launchSinglePlugin(pluginId)
        } catch (e: Throwable) {
            if (loadedPlugins.containsKey(pluginId)) {
                unloadPlugin(pluginId)
            }
            false
        }
    }

    suspend fun unloadPlugin(pluginId: String) = withContext(Dispatchers.IO) {
        if (!loadedPlugins.containsKey(pluginId)) return@withContext

        pluginInstances[pluginId]?.let { instance ->
            try {
                instance.onUnload()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        loadedPlugins.remove(pluginId)
        pluginInstances.remove(pluginId)
    }

    suspend fun loadEnabledPlugins(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "loadEnabledPlugins called")
        val allPlugins = xmlManager.getAllPlugins()
        Log.d(TAG, "All plugins from XmlManager: ${allPlugins.map { "${it.id}(enabled=${it.enabled})" }}")

        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(application)
        val enabledPlugins = allPlugins.filter { plugin ->
            if (!plugin.enabled || loadedPlugins.containsKey(plugin.id)) return@filter false
            val compatible = com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion ?: "", plugin.minHostVersion, plugin.maxHostVersion
            )
            if (!compatible) {
                Log.w(TAG, "Plugin ${plugin.id} 不兼容当前主应用版本，跳过加载")
            }
            compatible
        }
        Log.d(TAG, "Enabled plugins to load: ${enabledPlugins.map { it.id }}")

        if (enabledPlugins.isEmpty()) return@withContext 0

        var successCount = 0
        for (plugin in enabledPlugins) {
            Log.d(TAG, "Attempting to load plugin: ${plugin.id}")
            if (launchSinglePlugin(plugin.id)) {
                successCount++
                Log.d(TAG, "Successfully loaded: ${plugin.id}")
            } else {
                Log.w(TAG, "Failed to load: ${plugin.id}")
            }
        }
        Log.d(TAG, "loadEnabledPlugins completed: $successCount loaded")
        successCount
    }

    private suspend fun launchSinglePlugin(pluginId: String): Boolean {
        Log.d(TAG, "launchSinglePlugin: $pluginId")
        val pluginInfo = xmlManager.getPluginById(pluginId)
        if (pluginInfo == null) {
            Log.w(TAG, "Plugin info not found: $pluginId")
            return false
        }
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(application)
        if (!com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion ?: "", pluginInfo.minHostVersion, pluginInfo.maxHostVersion
            )
        ) {
            Log.w(TAG, "Plugin $pluginId 不兼容当前主应用版本，拒绝加载")
            return false
        }
        Log.d(TAG, "Plugin info: path=${pluginInfo.path}, entryScript=${pluginInfo.entryScript}")

        val loadedPlugin = loadPlugin(pluginInfo)
        if (loadedPlugin == null) {
            Log.w(TAG, "Failed to load plugin: $pluginId")
            return false
        }
        loadedPlugins[pluginId] = loadedPlugin
        Log.d(TAG, "Plugin loaded into memory: $pluginId")

        val instance = instantiatePlugin(loadedPlugin)
        if (instance == null) {
            Log.w(TAG, "Failed to instantiate plugin: $pluginId")
            unloadPlugin(pluginId)
            return false
        }
        pluginInstances[pluginId] = instance
        Log.d(TAG, "Plugin instance created: $pluginId")

        return true
    }

    private suspend fun reloadPlugin(pluginId: String): Boolean {
        unloadPlugin(pluginId)
        return launchSinglePlugin(pluginId)
    }

    private fun loadPlugin(plugin: PluginInfo): LoadedPluginInfo? {
        return try {
            Log.d(TAG, "loadPlugin: ${plugin.id}, path=${plugin.path}")
            val entryFile = File(plugin.path)
            if (!entryFile.exists()) {
                Log.w(TAG, "Plugin entry script not found: ${plugin.path}")
                return null
            }
            val pluginDir = entryFile.parentFile ?: File(plugin.path).parentFile
            val runtime = LuaScriptRuntime(
                pluginId = plugin.id,
                pluginDir = pluginDir,
                entryScript = plugin.entryScript ?: "main.lua",
                configStore = PluginManager.configStoreFactory.create(application, plugin.id),
                wsHostApi = PluginManager.wsHostApiFactory?.invoke(plugin.id),
                httpHostApi = PluginManager.httpHostApiFactory?.invoke(plugin.id),
                cryptoHostApi = PluginManager.cryptoHostApiFactory?.invoke(),
                sseHostApi = PluginManager.sseHostApiFactory?.invoke(plugin.id),
                // 数据类 API 按 manifest 能力声明门禁注入：未声明连实例都不创建（host 表不挂）
                quickSendHostApi = if (plugin.capabilities?.quickSendRead == true) {
                    PluginManager.quickSendHostApiFactory?.invoke(plugin.id)
                } else null,
                clipboardHostApi = if (plugin.capabilities?.clipboardRead == true) {
                    PluginManager.clipboardHostApiFactory?.invoke(plugin.id)
                } else null
            )
            // 按能力声明启用下行事件通道：未声明 events 的插件零开销、零行为变化。
            runtime.initEvents(plugin.capabilities?.events?.toSet() ?: emptySet())
            LoadedPluginInfo(pluginInfo = plugin, script = runtime)
        } catch (e: Exception) {
            Log.e(TAG, "loadPlugin failed for ${plugin.id}", e)
            null
        }
    }

    private fun instantiatePlugin(loadedPlugin: LoadedPluginInfo): IPluginEntryClass? {
        val plugin = loadedPlugin.pluginInfo
        Log.d(TAG, "Instantiating Lua plugin: ${plugin.id}")
        return try {
            val pluginContext = PluginContext(
                application = application,
                pluginInfo = plugin,
                configStore = PluginManager.configStoreFactory.create(application, plugin.id)
            )
            val adapter: LuaPluginAdapter = when (plugin.category) {
                PluginCategory.ASR ->
                    LuaAsrPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.EMOJI ->
                    LuaEmojiPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.CLIPBOARD_SYNC ->
                    LuaClipboardSyncPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.BACKUP ->
                    LuaBackupPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                PluginCategory.TOOL ->
                    LuaToolPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
                else ->
                    LuaPluginAdapter(
                        runtime = loadedPlugin.script ?: return null,
                        pluginContext = pluginContext
                    )
            }
            adapter.onLoad(pluginContext)
            Log.d(TAG, "Lua plugin ${plugin.id} onLoad called successfully")
            adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate Lua plugin ${plugin.id}", e)
            null
        }
    }
}

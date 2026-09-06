package com.kingzcheung.xime.plugin.core.lua

import android.util.Log
import com.kingzcheung.xime.plugin.core.api.BackupPlugin
import com.kingzcheung.xime.plugin.core.api.BackupResult
import com.kingzcheung.xime.plugin.core.api.RemoteBackupEntry
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaPluginContract
import com.kingzcheung.xime.plugin.core.model.PluginContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue

/**
 * backup 类型 Lua 插件的宿主侧适配器：实现 [BackupPlugin] 接口。
 *
 * 备份包的生成与恢复由宿主 BackupManager 承载，协议逻辑（WebDAV / S3 / 自建 HTTP）
 * 全部由插件 Lua 用 `host.http` + `host.crypto` 承载，本类只做接口桥接：
 * - pushBackup(name, archive)  → Lua `pushBackup({name, archive})`，归档为二进制 LuaString
 * - pullBackup(id)             → Lua `pullBackup(id)`，返回二进制 LuaString（nil → 失败）
 * - listBackups()              → Lua `listBackups()`，返回 {id,name,createdAt,size} 数组
 * - deleteBackup(id)           → Lua `deleteBackup(id)`
 * - testConnection()           → Lua `testConnection()`，返回错误消息（nil/空 → 成功）
 */
class LuaBackupPluginAdapter(
    runtime: LuaScriptRuntime,
    pluginContext: PluginContext
) : LuaPluginAdapter(runtime, pluginContext), BackupPlugin {

    override suspend fun pushBackup(name: String, archive: ByteArray): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val args = LuaTable()
                args.set("name", name)
                args.set("archive", LuaString.valueOf(archive))
                val result = runtime.call(LuaPluginContract.FN_PUSH_BACKUP, args)
                parseBackupResult(result)
            } catch (e: Exception) {
                Log.e("LuaBackup", "pushBackup failed", e)
                BackupResult(ok = false, message = e.message ?: "pushBackup failed")
            }
        }

    override suspend fun pullBackup(id: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.call(LuaPluginContract.FN_PULL_BACKUP, LuaValue.valueOf(id))
            luaStringToBytes(result)
        } catch (e: Exception) {
            Log.e("LuaBackup", "pullBackup failed", e)
            null
        }
    }

    override suspend fun listBackups(): List<RemoteBackupEntry>? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.call(LuaPluginContract.FN_LIST_BACKUPS)
            if (!result.istable()) return@withContext null
            LuaScriptRuntime.tableToList(result).mapNotNull { item ->
                if (!item.istable()) return@mapNotNull null
                val map = LuaScriptRuntime.tableToMap(item)
                val id = map["id"]?.tojstring()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RemoteBackupEntry(
                    id = id,
                    name = map["name"]?.tojstring() ?: id,
                    createdAt = map["createdAt"]?.tolong() ?: 0L,
                    size = map["size"]?.tolong() ?: -1L
                )
            }
        } catch (e: Exception) {
            Log.e("LuaBackup", "listBackups failed", e)
            null
        }
    }

    override suspend fun deleteBackup(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            runtime.call(LuaPluginContract.FN_DELETE_BACKUP, LuaValue.valueOf(id)).toboolean()
        } catch (e: Exception) {
            Log.e("LuaBackup", "deleteBackup failed", e)
            false
        }
    }

    override suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        try {
            val result = runtime.call("testConnection")
            if (result.isnil()) null else result.tojstring().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            e.message ?: "connection test failed"
        }
    }

    /** pushBackup 返回值兼容两种形态：bool 直接映射，table 取 {ok, id, message}。 */
    private fun parseBackupResult(result: LuaValue): BackupResult {
        if (result.istable()) {
            val map = LuaScriptRuntime.tableToMap(result)
            val ok = map["ok"]?.toboolean() ?: false
            return BackupResult(
                ok = ok,
                id = map["id"]?.tojstring()?.takeIf { it.isNotEmpty() },
                message = map["message"]?.tojstring()?.takeIf { it.isNotEmpty() }
            )
        }
        return BackupResult(ok = result.toboolean())
    }

    /** Lua 二进制字符串 → 字节流（zip 归档；与 LuaScriptRuntime.luaToBytes 同语义）。 */
    private fun luaStringToBytes(value: LuaValue): ByteArray? {
        if (value is LuaString) {
            val out = ByteArray(value.m_length)
            value.copyInto(0, out, 0, value.m_length)
            return out
        }
        return null
    }
}

package com.kingzcheung.xime.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable
import com.kingzcheung.xime.plugin.core.config.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插件设置 schema 的异步加载结果。
 *
 * [loaded] 为 false 表示仍在 IO 读取，用于区分"插件没有设置项"与"还没读到"。
 */
data class PluginSchemaState(
    val schema: List<UiNode> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * 在 IO 线程读取插件配置就绪状态（必填项是否都填了）。
 *
 * 与 [rememberPluginSettingsSchema] 同理：`isConfigured()` 会经 schema 进插件运行时，
 * 组合期不能同步调。[plugin] 为 null 时返回 null（无插件可判）。
 */
@Composable
fun rememberPluginConfigured(
    plugin: IPluginConfigurable?,
    vararg keys: Any?,
): Boolean? {
    var configured by remember(plugin, *keys) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(plugin, *keys) {
        configured = if (plugin == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                // 读取失败按"就绪"处理：宁可少一次引导，也不要误报"未配置"把用户赶去配置页
                runCatching { plugin.isConfigured() }.getOrElse { true }
            }
        }
    }
    return configured
}

/**
 * 读取插件 schema 的容错封装（不负责线程切换）：插件运行时报错（含运行时正在/已经关闭）
 * 一律降级为空 schema，绝不把异常抛进 UI 组合。
 */
internal fun readPluginSettingsSchema(plugin: IPluginConfigurable?): List<UiNode> =
    if (plugin == null) {
        emptyList()
    } else {
        runCatching { plugin.getSettingsSchema() }.getOrElse { emptyList() }
    }

/**
 * 在 IO 线程读取插件设置 schema；组合期只读状态，**绝不**直接调插件运行时。
 *
 * 为什么必须这样：`getSettingsSchema()` 是跨 QuickJS 的同步调用（硬超时 180s，超时还会
 * `poison()` 停掉插件），而设置类界面经常在**组合期**调用它（每次重组都会进一次引擎）：
 * - 主线程被 JS 执行阻塞，首帧/滚动都可能卡顿；
 * - 插件此时若正被停用/重载（`PluginLifecycleManager.unloadPlugin → runtime.close()`），
 *   调用会落到正在关闭的运行时上（v3.1.0 真机 record："调用 settings.schema: Already closed"）。
 *
 * [keys] 为额外的重算键（变化时重新读取）；[plugin] 实例变化（如插件重载后拿到新实例）
 * 也会重新读取。[plugin] 为 null 时直接给出"已加载且为空"。
 */
@Composable
fun rememberPluginSettingsSchema(
    plugin: IPluginConfigurable?,
    vararg keys: Any?,
): PluginSchemaState {
    var state by remember(plugin, *keys) {
        mutableStateOf(if (plugin == null) PluginSchemaState(loaded = true) else PluginSchemaState())
    }
    LaunchedEffect(plugin, *keys) {
        if (plugin == null) {
            state = PluginSchemaState(loaded = true)
            return@LaunchedEffect
        }
        state = withContext(Dispatchers.IO) {
            PluginSchemaState(schema = readPluginSettingsSchema(plugin), loaded = true)
        }
    }
    return state
}

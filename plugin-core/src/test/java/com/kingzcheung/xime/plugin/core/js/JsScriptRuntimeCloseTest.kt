package com.kingzcheung.xime.plugin.core.js

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 运行时关闭 / 调用竞态回归。
 *
 * 真机 record：`插件脚本执行出错（调用 settings.schema）… QuickJsException: Already closed`。
 * 场景是设置页在**组合期**读 `settings.schema`（`PluginsSettingsScreen.ExtensionItem` 判定
 * 是否有设置项），而插件正好在这一刻被停用/重载（`PluginLifecycleManager.unloadPlugin`
 * → `onUnload` → `runtime.close()`）。旧实现 close() 直接 `engine.close()` + `shutdownNow()`，
 * 且 `loaded` 直到末尾才清：
 * - 竞态窗口内被受理的调用落到已关闭的引擎上 → `Already closed`，被记成"[脚本错误]"，
 *   弹给用户并建议"更新插件"（实际是宿主生命周期竞态，用户无法自救）；
 * - 队列里尚未开始的调用被 shutdownNow 取消 → `CancellationException` 被
 *   [JsScriptRuntime] 的 isInterruptLike 判成超时 → `poison()` 把插件永久停掉。
 *
 * 现在的契约：关闭期间的调用只会得到"无结果"（null / 空 schema / hasMethod=false），
 * 既不碰已关闭的引擎，也不写脚本错误、不中毒。
 */
class JsScriptRuntimeCloseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pluginId = "js-close-race"

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /**
     * `onUnload` 忙等 ~30ms：真机上卸载要跑插件的 onUnload（可能还有网络/文件收尾），
     * 这里把"关闭中"窗口拉宽到可观测，让并发调用能稳定落在窗口里。
     */
    private val pluginJs = """
        globalThis.plugin = {
          settings: {
            schema: function () {
              return [{ type: 'text', key: 'server', label: '服务器地址' }];
            }
          },
          onUnload: function () {
            var until = Date.now() + 30;
            while (Date.now() < until) {}
          }
        };
    """.trimIndent()

    @After
    fun tearDown() {
        PluginErrorLog.clearErrors(pluginId)
    }

    private fun makeRuntime(): JsScriptRuntime {
        val dir = tmp.newFolder("close-race-${System.nanoTime()}")
        File(dir, "main.js").writeText(pluginJs)
        return JsScriptRuntime(
            pluginId = pluginId,
            pluginDir = dir,
            entryScript = "main.js",
            configStore = InMemoryConfigStore(),
            callTimeoutMs = 10_000L,
            callbackTimeoutMs = 1_000L
        )
    }

    /** 关闭后的调用一律"无结果"，且不得留下任何脚本错误记录。 */
    @Test
    fun `关闭后调用返回空结果且不记脚本错误`() {
        PluginErrorLog.clearErrors(pluginId)
        val runtime = makeRuntime()
        assertTrue("main.js 应能加载", runtime.load())
        assertEquals("关闭前 schema 应可用", 1, (runtime.call("settings.schema") as? List<*>)?.size)

        runtime.close()
        runtime.close() // 幂等：重复卸载（如先禁用再卸载）不应报错

        assertNull("关闭后同步调用应返回 null", runtime.call("settings.schema"))
        assertNull("关闭后异步调用应返回 null", runtime.callAsync("settings.schema"))
        assertFalse("关闭后 hasMethod 应为 false", runtime.hasMethod("settings.schema"))

        val errors = PluginErrorLog.getErrors(pluginId)
        assertTrue(
            "关闭后的调用不是脚本错误，不应写错误记录：${
                errors.map { "${it.category}:${it.operation}:${it.message}" }
            }",
            errors.isEmpty()
        )
    }

    /** 关闭期间并发调用：只允许"正常结果"或"无结果"，不得报 Already closed、不得中毒。 */
    @Test
    fun `关闭期间的并发调用不产生脚本错误也不中毒`() {
        PluginErrorLog.clearErrors(pluginId)
        val rounds = 15
        repeat(rounds) { round ->
            val runtime = makeRuntime()
            assertTrue("第 $round 轮加载失败", runtime.load())
            assertNotNull("关闭前 schema 应可用", runtime.call("settings.schema"))

            val start = CountDownLatch(1)
            val closer = thread(name = "close-race-closer-$round") {
                start.await()
                runtime.close()
            }
            val results = ConcurrentLinkedQueue<Any?>()
            val caller = thread(name = "close-race-caller-$round") {
                start.await()
                // 模拟设置页在组合期反复读 settings.schema
                while (closer.isAlive) {
                    results.add(runtime.call("settings.schema"))
                }
                results.add(runtime.call("settings.schema"))
            }
            start.countDown()
            closer.join(30_000)
            caller.join(30_000)
            assertFalse("第 $round 轮 close 未在 30s 内结束", closer.isAlive)
            assertFalse("第 $round 轮调用线程未在 30s 内结束", caller.isAlive)

            // 关闭后仍可安全调用
            assertNull("关闭后调用应返回 null", runtime.call("settings.schema"))

            for (value in results) {
                if (value == null) continue // 已关闭/正在关闭：按无结果处理
                val list = value as? List<*>
                assertNotNull("第 $round 轮返回形状异常：$value", list)
                assertEquals("第 $round 轮 schema 节点数异常", 1, list!!.size)
            }
        }

        val errors = PluginErrorLog.getErrors(pluginId)
        assertTrue(
            "关闭竞态不得被记成脚本错误（尤其不得中毒）：${
                errors.map { "${it.category}:${it.operation}:${it.message}" }
            }",
            errors.isEmpty()
        )
    }
}

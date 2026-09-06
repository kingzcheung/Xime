package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardT9ConfigTest {

    private fun parseT9(yamlFragment: String): KeyboardT9Partial? =
        KeysConfigHelper.parseKeyboardT9YamlPartial("keyboard:\n  t9:\n    " + yamlFragment.replace("\n", "\n    "))

    @Test
    fun `解析 side_symbols 列表`() {
        val partial = parseT9("side_symbols:\n    - \"，\"\n    - \"。\"\n    - \"、\"\n    - \"？\"\n    - \"！\"")
        assertEquals(listOf("，", "。", "、", "？", "！"), partial?.sideSymbols)
    }

    @Test
    fun `未配置 side_symbols 返回 null`() {
        val partial = parseT9("other: 1")
        assertNull(partial?.sideSymbols)
    }

    @Test
    fun `keyboard 段缺失返回 null`() {
        assertNull(KeysConfigHelper.parseKeyboardT9YamlPartial("style:\n  dark_mode: 2"))
    }

    @Test
    fun `side_symbols 空列表视为未配置`() {
        val partial = parseT9("  side_symbols: []")
        assertNull(partial?.sideSymbols)
    }

    @Test
    fun `合并 custom 覆盖 builtin`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(sideSymbols = listOf("、", "：")),
            builtIn = KeyboardT9Partial(sideSymbols = listOf("，", "。", "？", "！")),
        )
        assertEquals(listOf("、", "："), merged.sideSymbols)
    }

    @Test
    fun `合并 custom 未配置回退 builtin`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(),
            builtIn = KeyboardT9Partial(sideSymbols = listOf("，", "。", "？", "！")),
        )
        assertEquals(listOf("，", "。", "？", "！"), merged.sideSymbols)
    }

    @Test
    fun `合并 双方未配置回退内置默认值`() {
        val merged = KeysConfigHelper.mergeT9Configs(custom = null, builtIn = null)
        assertEquals(KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS, merged.sideSymbols)
    }

    @Test
    fun `custom 空列表视为未配置回退默认值`() {
        val merged = KeysConfigHelper.mergeT9Configs(
            custom = KeyboardT9Partial(sideSymbols = emptyList()),
            builtIn = null,
        )
        assertEquals(KeysConfigHelper.DEFAULT_T9_SIDE_SYMBOLS, merged.sideSymbols)
    }
}

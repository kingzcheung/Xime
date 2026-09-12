package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.view.inputmethod.InputConnection
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.then
import org.mockito.kotlin.whenever

/**
 * 语音松手收尾状态机测试：finishRecognition 停止送音后等待引擎最终结果，
 * 超时才回退提交部分结果（修复"说完立刻松手，尾部语音被截断"）。
 */
@RunWith(MockitoJUnitRunner.Silent::class)
class VoiceRecognitionHandlerFinishTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockInputConnection: InputConnection

    @Mock
    private lateinit var mockManager: SpeechRecognitionManager

    @Mock
    private lateinit var mockMainHandler: Handler

    private lateinit var handler: VoiceRecognitionHandler

    private val stateChanges = mutableListOf<InputUIState>()
    private var voiceCompleteCount = 0

    /** mock mainHandler 的 postDelayed 队列，可手动推进超时。 */
    private val posted = mutableListOf<Runnable>()

    private lateinit var onResult: (String) -> Unit
    private lateinit var onPartial: (String) -> Unit
    private lateinit var onState: (RecognitionState) -> Unit

    @Before
    fun setup() {
        whenever(mockContext.getSharedPreferences(any(), anyInt())).thenReturn(mockPrefs)
        // 所有布尔设置返回默认值；isSttUseLocal 置 true 使 resolveProviderName 走本地引擎
        // 早退（不触碰未初始化的 PluginManager/ExtensionManager），warmup 分支为异步线程无副作用
        whenever(mockPrefs.getBoolean(any(), any<Boolean>())).thenAnswer { it.getArgument(1) }
        whenever(mockPrefs.getBoolean(eq(SettingsPreferences.KEY_STT_USE_LOCAL), any<Boolean>()))
            .thenReturn(true)
        whenever(mockMainHandler.postDelayed(any<Runnable>(), anyLong())).thenAnswer {
            posted.add(it.getArgument(0))
            true
        }
        whenever(mockMainHandler.removeCallbacks(any<Runnable>())).thenAnswer {
            posted.remove(it.getArgument<Runnable>(0))
        }

        handler = VoiceRecognitionHandler(
            context = mockContext,
            onStateChanged = { stateChanges.add(it) },
            getState = { InputUIState() },
            getInputConnection = { mockInputConnection },
            onVoiceComplete = { voiceCompleteCount++ },
            managerFactory = { mockManager },
            mainHandler = mockMainHandler
        )
        handler.initialize()

        val onResultCaptor = argumentCaptor<(String) -> Unit>()
        val onPartialCaptor = argumentCaptor<(String) -> Unit>()
        val onStateCaptor = argumentCaptor<(RecognitionState) -> Unit>()
        verify(mockManager).setCallbacks(
            onResultCaptor.capture(), onPartialCaptor.capture(), onStateCaptor.capture(),
            any(), any(), any()
        )
        onResult = onResultCaptor.firstValue
        onPartial = onPartialCaptor.firstValue
        onState = onStateCaptor.firstValue
    }

    private fun runTimeouts() {
        val copy = posted.toList()
        posted.clear()
        copy.forEach { it.run() }
    }

    @Test
    fun `收尾时收到最终结果则提交完整结果并取消超时兜底`() {
        onPartial.invoke("你好")
        handler.finishRecognition()

        // 停止送音、进入"正在识别"、安排了超时兜底
        verify(mockManager).stopRecognition()
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)
        assertEquals(1, posted.size)

        // 引擎吐出最终结果：提交增量部分（partial 已通过 composing 上屏，句号由启发式补齐）
        onResult.invoke("你好世界再见")

        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("世界再见。"), eq(1))
        assertEquals(1, voiceCompleteCount)
        // 超时兜底已被取消，手动推进不再重复提交
        assertTrue(posted.isEmpty())
        runTimeouts()
        verify(mockInputConnection, never()).commitText(eq("你好，"), eq(1))
        assertEquals(1, voiceCompleteCount)
    }

    @Test
    fun `超时未收到最终结果则回退提交部分结果且忽略迟到的最终结果`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        runTimeouts()

        // 部分结果"你好"已通过 composing 上屏，兜底只补上启发式句号"，"
        verify(mockInputConnection).finishComposingText()
        verify(mockInputConnection).commitText(eq("，"), eq(1))
        assertEquals(1, voiceCompleteCount)

        // 迟到的最终结果被抑制：不再写入输入框（避免重复/错乱）
        onResult.invoke("你好世界再见")
        verify(mockInputConnection, times(1)).commitText(any(), anyInt())
        assertEquals(2, voiceCompleteCount)
    }

    @Test
    fun `无已识别文本时收尾不等待直接结束`() {
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        assertEquals(1, voiceCompleteCount)
        assertTrue(posted.isEmpty())
        assertFalse(stateChanges.any { it.voiceRecognitionState == RecognitionState.PROCESSING })
    }

    @Test
    fun `收尾中重复调用幂等`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        assertEquals(1, posted.size)
        // 收尾尚未完成（在等最终结果），不触发 onVoiceComplete
        assertEquals(0, voiceCompleteCount)
    }

    @Test
    fun `会话被丢弃后收尾不提交文本且超时兜底失效`() {
        onPartial.invoke("你好")
        handler.abandonSession()
        handler.finishRecognition()

        verify(mockManager).stopRecognition()
        runTimeouts()
        verify(mockInputConnection, never()).commitText(any(), anyInt())
        verify(mockInputConnection, never()).finishComposingText()
        assertEquals(0, voiceCompleteCount)
    }

    @Test
    fun `收尾期间引擎的IDLE状态不覆盖正在识别显示`() {
        onPartial.invoke("你好")
        handler.finishRecognition()
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)

        // 引擎 stop 过程中回调 IDLE：保持"正在识别..."显示
        onState.invoke(RecognitionState.IDLE)
        assertEquals(RecognitionState.PROCESSING, stateChanges.last().voiceRecognitionState)

        // 其他状态（如 ERROR）不被过滤
        onState.invoke(RecognitionState.ERROR)
        assertEquals(RecognitionState.ERROR, stateChanges.last().voiceRecognitionState)
    }

    @Test
    fun `部分结果写入composing区域并同步到UI状态`() {
        onPartial.invoke("你好")

        verify(mockInputConnection).setComposingText(eq("你好"), eq(1))
        assertEquals("你好", stateChanges.last().voiceRecognizedText)
    }
}

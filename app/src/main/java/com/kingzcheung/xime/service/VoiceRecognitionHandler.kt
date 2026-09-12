package com.kingzcheung.xime.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.speech.SpeechRecognitionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger

class VoiceRecognitionHandler(
    private val context: Context,
    private val onStateChanged: (InputUIState) -> Unit,
    private val getState: () -> InputUIState,
    private val getInputConnection: () -> InputConnection?,
    private val onVoiceComplete: () -> Unit = {},
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onSpectrumChanged: (FloatArray) -> Unit = {},
    /** 语音向输入框写入 composing 文本时回调（标记 composing 区域存在，供 endComposingInputBox 判断）。 */
    private val onComposingWritten: () -> Unit = {},
    /** manager 工厂，供测试注入 mock。 */
    private val managerFactory: (Context) -> SpeechRecognitionManager = { SpeechRecognitionManager(it) },
    /** 超时调度器，供测试注入并手动推进。 */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    companion object {
        private const val TAG = "VoiceRecognition"

        /**
         * 松手后等待 ASR 引擎最终结果的超时。在线插件 stop() 只发送结束信号
         * （finish-task/最后一包标记），服务端处理完尾点才经 WebSocket 异步回调
         * 最终结果，通常 1~2s；超时仍未收到则回退提交已收到的部分结果。
         */
        private const val FINISH_TIMEOUT_MS = 3000L
    }

    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    var textBeforeVoiceInput = ""
    var textLengthBeforeVoiceInput = 0

    fun initialize() {
        FileLogger.i(TAG, "Initializing speech recognition system")

        speechRecognitionManager = managerFactory(context)

        speechRecognitionManager.setCallbacks(
            onResult = { text ->
                handleSpeechResult(text)
            },
            onPartialResult = { text ->
                handlePartialResult(text)
            },
            onStateChange = { state ->
                handleSpeechStateChange(state)
            },
            onError = { error, userVisible ->
                handleSpeechError(error, userVisible)
            },
            onAmplitude = { amplitude ->
                handleAmplitudeUpdate(amplitude)
            },
            onSpectrum = { spectrum ->
                handleSpectrumUpdate(spectrum)
            }
        )

        val providerName = resolveProviderName()

        onStateChanged(getState().copy(voicePluginName = providerName))
        FileLogger.i(TAG, "STT provider: $providerName")

        // 若"使用本地模型"开关已开启，启动时即加载模型并常驻，
        // 保证语音时绝不现场加载模型（避免丢开头音频）。
        // 注意：keep-alive 预热也走这里（AsrSupport.warmup 注册常驻后端），
        // 不要再走 manager.preload——那会经 AsrSupport.create 造一个临时后端，
        // 与本 warmup 并发时双重绑定 :asr、双重加载模型（日志曾见两次 initialize）
        if (SettingsPreferences.isSttUseLocal(context) &&
            AsrBackendFactory.getLocalName() != null
        ) {
            Thread {
                AsrBackendFactory.warmup(context)
            }.start()
        }
    }

    private val delayedPreStartRunnable = Runnable {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.startPreStart()
        }
    }

    fun startDelayedPreStart(delayMs: Long = 150) {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        mainHandler.postDelayed(delayedPreStartRunnable, delayMs)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(delayedPreStartRunnable)
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.cancelPreStart()
        }
    }

    fun startRecognition() {
        if (!::speechRecognitionManager.isInitialized) {
            Log.e(TAG, "speechRecognitionManager not initialized")
            onStateChanged(getState().copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceRecognitionState = RecognitionState.ERROR
            ))
            return
        }

        // 上一次会话若还在收尾等待中（快速再次开始），直接废弃收尾状态
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        suppressDuplicateFinal = false

        textBeforeVoiceInput = getInputConnection()?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        textLengthBeforeVoiceInput = textBeforeVoiceInput.length

        val providerName = resolveProviderName()
        onStateChanged(getState().copy(voicePluginName = providerName))

        speechRecognitionManager.startRecognition()
    }

    fun stopRecognition() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.stopRecognition()
        }
        // handleFinalResult is now called from within handleSpeechResult
        // when the final stopRecognition result arrives
    }

    fun release() {
        if (::speechRecognitionManager.isInitialized) {
            speechRecognitionManager.release()
        }
    }

    fun isInitialized(): Boolean = ::speechRecognitionManager.isInitialized

    private fun resolveProviderName(): String {
        // 用户开启"本地识别"且当前构建支持离线语音时，优先显示本地引擎名
        if (SettingsPreferences.isSttUseLocal(context)) {
            val localName = AsrBackendFactory.getLocalName()
            if (localName != null) return localName
        }
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isNotEmpty()) {
            val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
            val selected = enabledPlugins.firstOrNull { it.first == selectedId }
                ?: enabledPlugins.firstOrNull()
            if (selected != null) {
                return ExtensionManager.getAllInstalledPlugins()
                    .firstOrNull { it.id == selected.first }?.name ?: selected.first
            }
        }
        return "未配置"
    }

    private var lastPartialText = ""
    private var lastAmplitudeUpdate = 0L
    private var smoothedAmplitude = 0f
    private var smoothedSpectrum = FloatArray(16)
    // 抬起时已提交当前识别文本后，置真以忽略随后可能迟到的重复最终结果
    private var suppressDuplicateFinal = false
    // 松手收尾中：已停止送音，等待引擎吐出最终结果（超时由 finishTimeoutRunnable 兜底）
    @Volatile
    private var finishing = false
    // 输入法窗口隐藏等场景：丢弃本会话，迟到结果不得写入任何输入框
    private var sessionAbandoned = false
    private var errorToast: Toast? = null

    private val finishTimeoutRunnable = Runnable { onFinishTimeout() }

    /** 输入法隐藏/切换输入框时调用：丢弃当前会话的未识别文本，忽略迟到的最终结果 */
    fun abandonSession() {
        sessionAbandoned = true
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        lastPartialText = ""
    }

    // 语音按钮长按抬起时调用：立即提交当前已识别的文本（不依赖可能被断连竞态吞掉的异步最终结果）
    fun commitPendingOnRelease() {
        if (sessionAbandoned) return
        val ic = getInputConnection()
        val partial = lastPartialText
        Log.d(TAG, "commitPendingOnRelease: ic=${ic != null}, partial='$partial', suppress=$suppressDuplicateFinal")
        if (ic == null) return
        if (partial.isEmpty()) return
        val punctuatedText = addPunctuation(partial)
        commitFinal(ic, punctuatedText, partial)
        suppressDuplicateFinal = true
        lastPartialText = ""
    }

    /**
     * 松手/点按结束语音的收尾入口：停止送音后等待引擎最终结果，而不是立即提交
     * 部分结果——在线 ASR 需 1~2s 处理尾点，立即提交会把未处理完的语音截断。
     * 收到最终结果正常提交；超时（[FINISH_TIMEOUT_MS]）才回退提交部分结果。
     * 完成路径（最终结果/超时/错误）统一经 onVoiceComplete 通知宿主恢复键盘。
     */
    fun finishRecognition() {
        if (!::speechRecognitionManager.isInitialized) return
        if (finishing) return
        if (sessionAbandoned) {
            speechRecognitionManager.stopRecognition()
            return
        }
        if (lastPartialText.isEmpty()) {
            // 无已识别文本（没说话/极短语音）：无内容可等，直接结束；
            // 迟到的最终结果仍走正常提交路径（说了话就该上屏）
            speechRecognitionManager.stopRecognition()
            onVoiceComplete()
            return
        }
        finishing = true
        // 收尾期间保持"正在识别..."显示：引擎 stop 过程中的 IDLE 状态由
        // handleSpeechStateChange 过滤，直到最终结果/超时才结束
        onStateChanged(getState().copy(voiceRecognitionState = RecognitionState.PROCESSING))
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        mainHandler.postDelayed(finishTimeoutRunnable, FINISH_TIMEOUT_MS)
        speechRecognitionManager.stopRecognition()
    }

    private fun onFinishTimeout() {
        if (!finishing) return
        finishing = false
        Log.d(TAG, "finish timeout: committing partial result as fallback")
        // 超时未收到最终结果：提交已收到的部分结果兜底（会话已丢弃时内部直接跳过）
        commitPendingOnRelease()
        onVoiceComplete()
    }

    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result (final): $text")

        if (finishing) {
            // 收尾中收到最终结果：取消超时兜底，正常提交完整结果
            finishing = false
            mainHandler.removeCallbacks(finishTimeoutRunnable)
        }

        if (sessionAbandoned) {
            sessionAbandoned = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        if (suppressDuplicateFinal) {
            // 抬起时已提交，忽略迟到的重复最终结果
            suppressDuplicateFinal = false
            lastPartialText = ""
            onVoiceComplete()
            return
        }

        val cleanText = text.replace(" ", "")
        val ic = getInputConnection()
        if (ic != null && cleanText.isNotEmpty() && !cleanText.startsWith("错误:")) {
            val punctuatedText = addPunctuation(cleanText)
            commitFinal(ic, punctuatedText, lastPartialText)
        }
        lastPartialText = ""
        onVoiceComplete()
    }
    
    // 增量语音模式：先结束 composing，再只提交增量，避免重复与整段重写。
    private fun commitFinal(ic: InputConnection, finalText: String, partial: String) {
        ic.finishComposingText()
        if (partial.isNotEmpty() && finalText.startsWith(partial)) {
            val remainder = finalText.substring(partial.length)
            if (remainder.isNotEmpty()) {
                ic.commitText(remainder, 1)
            } else {
                Log.d(TAG, "commitFinal: remainder empty, only finished composing")
            }
        } else {
            // 最终结果与部分结果不一致：删除已上屏的部分，再提交完整结果
            if (partial.isNotEmpty()) {
                ic.deleteSurroundingText(partial.length, 0)
            }
            ic.commitText(finalText, 1)
        }
        Log.d(TAG, "commitFinal: final='$finalText', partial='$partial'")
    }
    
    private fun addPunctuation(text: String): String {
        val cleanText = text.trim().replace(" ", "")
        if (cleanText.isEmpty()) return text

        // 若文本末尾已带句末标点（如 funasr/volc 等自带标点的后端），不再追加，避免"。。"
        if (cleanText.last() in "。！？；：，、；：,.!?;:，") return cleanText

        return "$cleanText${heuristicPunctuation(cleanText)}"
    }

    private fun heuristicPunctuation(text: String): String {
        return when {
            text.any { it in "吗呢么吧" } || text.contains("什么") || text.contains("怎么") || text.contains("为什么") || text.contains("如何") || text.contains("哪") -> "？"
            text.length < 4 -> "，"
            else -> "。"
        }
    }

    private fun handlePartialResult(text: String) {
        if (sessionAbandoned || suppressDuplicateFinal) return
        if (text == lastPartialText) return
        lastPartialText = text
        Log.d(TAG, "Speech result (partial): $text")
        
        // 过滤掉空格，避免显示空白
        val cleanText = text.replace(" ", "")
        if (cleanText.isEmpty()) return
        
        val ic = getInputConnection()
        if (ic != null) {
            onComposingWritten()
            ic.setComposingText(cleanText, 1)
        }
        onStateChanged(getState().copy(voiceRecognizedText = cleanText))
    }

    private fun handleSpeechStateChange(state: RecognitionState) {
        Log.d(TAG, "Speech state changed: $state")
        if (state == RecognitionState.LISTENING) {
            lastPartialText = ""
            suppressDuplicateFinal = false
            sessionAbandoned = false
        }
        // 收尾等待最终结果期间，引擎 stop 产生的 IDLE 不覆盖"正在识别..."显示
        if (finishing && state == RecognitionState.IDLE) return
        onStateChanged(getState().copy(voiceRecognitionState = state))
    }

    private fun handleSpeechError(error: String, userVisible: Boolean) {
        Log.e(TAG, "Speech error: $error")
        FileLogger.e(TAG, "Speech error: $error")
        finishing = false
        mainHandler.removeCallbacks(finishTimeoutRunnable)
        lastPartialText = ""
        if (userVisible && error.isNotBlank()) {
            errorToast?.cancel()
            errorToast = Toast.makeText(context, error, Toast.LENGTH_LONG)
            errorToast?.show()
        }
        onVoiceComplete()
    }

    private fun handleAmplitudeUpdate(amplitude: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAmplitudeUpdate < 80) return
        lastAmplitudeUpdate = now
        smoothedAmplitude = smoothedAmplitude * 0.45f + amplitude * 0.55f
        onAmplitudeChanged(smoothedAmplitude)
    }

    private fun handleSpectrumUpdate(spectrum: FloatArray) {
        val smoothed = smoothedSpectrum
        for (i in spectrum.indices) {
            smoothed[i] = smoothed[i] * 0.5f + spectrum[i] * 0.5f
        }
        onSpectrumChanged(smoothed.copyOf())
    }
}
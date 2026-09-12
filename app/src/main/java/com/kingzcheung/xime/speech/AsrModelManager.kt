package com.kingzcheung.xime.speech

import android.content.Context
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelStorage
import java.io.File

/**
 * ASR 模型管理与选择。
 *
 * 模型推理由自研的 streaming zipformer2 实现（libasr_jni.so）负责。
 * 模型清单与描述来自「扩展商店」远程索引（[ModelManager]，category=asr），
 * 索引未加载时回退到内置默认模型（zipformer-zh-int8）。
 */
class AsrModelManager(private val context: Context) {

    companion object {
        /** 内置默认 ASR 模型（远程索引加载前/失败时的兜底）。 */
        val DEFAULT_MODEL = AsrModelInfo(
            id = "zipformer-zh-int8",
            name = "中文 Zipformer int8",
            description = "Zipformer 架构，适合实时语音识别，int8 量化",
            language = "zh",
            size = "132.63MB",
            downloadUrl = "https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            modelType = "transducer",
            files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
            encoderFile = "encoder.int8.onnx",
            decoderFile = "decoder.onnx",
            joinerFile = "joiner.int8.onnx"
        )

        /** 兼容旧引用。 */
        @Deprecated("使用 getAsrModels()/getSelectedModelInfo() 从索引读取")
        val AVAILABLE_MODELS: List<AsrModelInfo> = listOf(DEFAULT_MODEL)

        private const val DEFAULT_ID = "zipformer-zh-int8"

        /** 把索引里的 ModelInfo 转换为 ASR 专用模型信息。 */
        private fun toAsrModelInfo(info: com.kingzcheung.xime.model.ModelInfo): AsrModelInfo {
            val version = info.resolvedVersion()
            val fileNames = info.files.map { it.name }
            return AsrModelInfo(
                id = info.id,
                name = info.name,
                description = info.description,
                language = "zh",
                size = version?.size ?: info.size,
                downloadUrl = info.archiveUrl ?: "",
                modelType = "transducer",
                files = fileNames,
                encoderFile = "encoder.int8.onnx",
                decoderFile = "decoder.onnx",
                joinerFile = "joiner.int8.onnx"
            )
        }
    }

    data class AsrModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val language: String,
        val size: String,
        val downloadUrl: String,
        val modelType: String = "transducer",
        val files: List<String>,
        val encoderFile: String = "",
        val decoderFile: String = "",
        val joinerFile: String = "",
        val needsAutoPunctuation: Boolean = true
    )

    /** ASR 分类的模型清单（索引优先，索引未加载时用内置默认）。 */
    fun getAsrModels(): List<AsrModelInfo> {
        val fromIndex = ModelManager.getModelsByCategory(ModelCategory.ASR)
            .map { toAsrModelInfo(it) }
        return if (fromIndex.isNotEmpty()) fromIndex else listOf(DEFAULT_MODEL)
    }

    /** 所有 ASR 模型 id，用于判断某个 id 是否为已知 ASR 模型。 */
    fun getAsrModelIds(): Set<String> = getAsrModels().map { it.id }.toSet()

    fun isModelReady(): Boolean {
        val modelDir = getSelectedModelDir()
        if (!modelDir.exists()) return false
        val files = modelDir.listFiles()
        return files != null && files.isNotEmpty()
    }

    fun getSelectedModelDir(): File {
        val modelId = getSelectedModelId()
        val dir = ModelStorage.getModelDir(context, modelId)
        // 兼容旧版：自动迁移 asr_models/<id>/ 下的模型文件
        ModelStorage.migrateLegacyForModel(context, modelId)
        return dir
    }

    fun getSelectedModelId(): String {
        val sharedPrefs = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        return sharedPrefs.getString("selected_model", DEFAULT_ID) ?: DEFAULT_ID
    }

    /** 当前选中模型的完整信息（索引优先，兜底内置默认）。 */
    fun getSelectedModelInfo(): AsrModelInfo? {
        val modelId = getSelectedModelId()
        return getAsrModels().find { it.id == modelId } ?: DEFAULT_MODEL
    }

    fun setModel(modelId: String) {
        val sharedPrefs = context.getSharedPreferences("asr_model", Context.MODE_PRIVATE)
        sharedPrefs.edit().putString("selected_model", modelId).apply()
    }

    fun findFile(dir: File, fileName: String): File? {
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findFile(child, fileName)
                if (found != null) return found
            }
        }
        return null
    }
}


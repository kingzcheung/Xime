package com.kingzcheung.xime.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kingzcheung.xime.model.ModelCategory
import com.kingzcheung.xime.model.ModelDownloadState
import com.kingzcheung.xime.model.ModelInfo
import com.kingzcheung.xime.model.ModelManager
import com.kingzcheung.xime.model.ModelVersion
import com.kingzcheung.xime.settings.MarketVersionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelItemState(
    val model: ModelInfo,
    val isDownloaded: Boolean = false,
    val diskSize: Long = 0,
    val downloadState: ModelDownloadState = ModelDownloadState.Idle,
    /** 本地已下载的模型版本（无则未下载或版本未知） */
    val installedVersion: String? = null,
) {
    /** 是否已有更新：已下载且本地版本 != 索引默认版本。 */
    val hasUpdate: Boolean
        get() {
            if (!isDownloaded || installedVersion == null) return false
            val latest = model.resolvedVersion()?.version ?: return false
            return installedVersion != latest
        }
}

data class ModelManagementUiState(
    val models: List<ModelItemState> = emptyList(),
    val isLoading: Boolean = true,
    val toastMessage: String? = null,
    /** 当前选中的模型 id → 版本字符串（空表示默认版本） */
    val selectedVersions: Map<String, String> = emptyMap(),
    /** 分类过滤：null 表示全部 */
    val selectedCategory: ModelCategory? = null,
) {
    /** 按分类过滤后的模型列表。 */
    val filteredModels: List<ModelItemState>
        get() = if (selectedCategory == null) models
        else models.filter { it.model.category == selectedCategory }
}

class ModelManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _uiState = MutableStateFlow(ModelManagementUiState())
    val uiState: StateFlow<ModelManagementUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadModels()
            refreshFromRemote()
        }
    }

    private suspend fun loadModels() {
        val allModels = ModelManager.getAllModels()
        val versions = withContext(Dispatchers.IO) {
            MarketVersionStore.getAllModelVersions(context)
        }
        val items = allModels.map { model ->
            val downloaded = withContext(Dispatchers.IO) {
                ModelManager.isModelDownloaded(context, model)
            }
            val size = if (downloaded) {
                withContext(Dispatchers.IO) {
                    ModelManager.getModelSizeOnDisk(context, model.id)
                }
            } else 0L
            ModelItemState(
                model = model,
                isDownloaded = downloaded,
                diskSize = size,
                installedVersion = if (downloaded) versions[model.id] else null,
            )
        }
        _uiState.update { it.copy(models = items, isLoading = false) }
    }

    private suspend fun refreshFromRemote() {
        _uiState.update { it.copy(isLoading = true) }
        ModelManager.loadFromRemote(context)
        loadModels()
    }

    fun refresh() {
        // 连点防抖：刷新进行中忽略后续点击（重复拉取网络 index 徒增开销）
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            refreshFromRemote()
        }
    }

    /** 仅重新检查本地下载状态（不重新拉取远程 index），用于从本地管理页返回后同步。 */
    fun refreshDownloadedState() {
        viewModelScope.launch { loadModels() }
    }

    fun selectVersion(modelId: String, version: String) {
        _uiState.update {
            it.copy(selectedVersions = it.selectedVersions + (modelId to version))
        }
    }

    fun selectCategory(category: ModelCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun downloadModel(modelId: String, version: ModelVersion? = null) {
        val index = _uiState.value.models.indexOfFirst { it.model.id == modelId }
        if (index < 0) return

        viewModelScope.launch {
            updateModelState(index) {
                it.copy(downloadState = ModelDownloadState.Downloading(0f, 0, -1))
            }

            var downloadError: String? = null
            val selectedVersion = version
                ?: _uiState.value.selectedVersions[modelId]?.let { versionStr ->
                    ModelManager.getModel(modelId)?.versions?.firstOrNull { it.version == versionStr }
                }
            ModelManager.downloadModel(context, _uiState.value.models[index].model, { state ->
                when (state) {
                    is ModelDownloadState.Downloading -> {
                        updateModelState(index) { it.copy(downloadState = state) }
                    }
                    is ModelDownloadState.Error -> {
                        downloadError = state.message
                        updateModelState(index) { it.copy(downloadState = state) }
                    }
                    else -> {}
                }
            }, selectedVersion)

            if (downloadError != null) {
                updateModelState(index) { it.copy(downloadState = ModelDownloadState.Idle) }
            } else {
                val downloaded = withContext(Dispatchers.IO) {
                    ModelManager.isModelDownloaded(context, modelId)
                }
                val size = if (downloaded) {
                    withContext(Dispatchers.IO) {
                        ModelManager.getModelSizeOnDisk(context, modelId)
                    }
                } else 0L
                // 记录本地下载版本（用于更新检测）；下载成功但版本未知时留空
                val actualVersion = if (downloaded) {
                    (selectedVersion?.version ?: _uiState.value.models[index].model.resolvedVersion()?.version)
                        ?.also {
                            withContext(Dispatchers.IO) {
                                MarketVersionStore.setModelVersion(context, modelId, it)
                            }
                        }
                } else null
                updateModelState(index) {
                    it.copy(
                        isDownloaded = downloaded,
                        diskSize = size,
                        installedVersion = actualVersion,
                        downloadState = ModelDownloadState.Idle
                    )
                }
                if (downloaded) {
                    _uiState.update { s -> s.copy(toastMessage = "模型下载完成") }
                }
            }
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                ModelManager.deleteModel(context, modelId)
            }
            if (success) {
                withContext(Dispatchers.IO) {
                    MarketVersionStore.removeModelVersion(context, modelId)
                }
                val index = _uiState.value.models.indexOfFirst { it.model.id == modelId }
                if (index >= 0) {
                    updateModelState(index) {
                        it.copy(isDownloaded = false, diskSize = 0, installedVersion = null)
                    }
                }
                _uiState.update { s -> s.copy(toastMessage = "模型已删除") }
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun updateModelState(index: Int, transform: (ModelItemState) -> ModelItemState) {
        _uiState.update { state ->
            val updated = state.models.toMutableList()
            updated[index] = transform(updated[index])
            state.copy(models = updated)
        }
    }
}

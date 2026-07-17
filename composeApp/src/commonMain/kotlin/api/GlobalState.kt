package api

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object GlobalState {
    /**
     * 全局刷新信号，用于通知各页面重新从后端拉取数据
     */
    private val _refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshSignal = _refreshSignal.asSharedFlow()

    /**
     * 全局收藏歌曲 ID 列表
     */
    private val _favoriteIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds = _favoriteIds.asStateFlow()

    private val _showPlaylistState = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showPlaylistState = _showPlaylistState.asStateFlow()

    fun updateFavorites(ids: Set<String>) {
        _favoriteIds.value = ids
    }

    fun togglePlaylist(show: Boolean) {
        _showPlaylistState.value = show
    }

    private val _showStoragePermissionDialog = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showStoragePermissionDialog = _showStoragePermissionDialog.asStateFlow()

    fun updateShowStoragePermissionDialog(show: Boolean) {
        _showStoragePermissionDialog.value = show
    }

    /**
     * 触发全局刷新
     */
    fun triggerRefresh() {
        _refreshSignal.tryEmit(Unit)
    }
}

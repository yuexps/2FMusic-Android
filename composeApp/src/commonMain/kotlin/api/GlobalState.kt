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
     * 分模块数据刷新信号
     */
    private val _historyRefreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val historyRefreshSignal = _historyRefreshSignal.asSharedFlow()

    private val _favoriteRefreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val favoriteRefreshSignal = _favoriteRefreshSignal.asSharedFlow()

    private val _musicListRefreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val musicListRefreshSignal = _musicListRefreshSignal.asSharedFlow()

    fun triggerHistoryRefresh() {
        _historyRefreshSignal.tryEmit(Unit)
    }

    fun triggerFavoriteRefresh() {
        _favoriteRefreshSignal.tryEmit(Unit)
    }

    fun triggerMusicListRefresh() {
        _musicListRefreshSignal.tryEmit(Unit)
    }

    /**
     * 触发全局刷新
     */
    fun triggerRefresh() {
        _refreshSignal.tryEmit(Unit)
        _historyRefreshSignal.tryEmit(Unit)
        _favoriteRefreshSignal.tryEmit(Unit)
        _musicListRefreshSignal.tryEmit(Unit)
    }
}

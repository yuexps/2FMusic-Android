package utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 定时关闭（倒计时暂停）全局管理器
 */
object SleepTimerManager {
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds: StateFlow<Int?> = _remainingSeconds.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null

    /**
     * 启动定时倒计时（单位：分钟）
     */
    fun startTimer(minutes: Int) {
        timerJob?.cancel()
        val totalSeconds = minutes * 60
        _remainingSeconds.value = totalSeconds

        timerJob = scope.launch {
            var current = totalSeconds
            while (current > 0) {
                delay(1000)
                current--
                _remainingSeconds.value = current
            }
            
            // 时间倒计时到零，触发暂停
            try {
                Platform.playerController.pause()
                Platform.toast.show("定时关闭已生效，播放已暂停")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            _remainingSeconds.value = null
        }
    }

    /**
     * 启动定时倒计时（单位：秒，方便测试或精准定时）
     */
    fun startTimerSeconds(seconds: Int) {
        timerJob?.cancel()
        _remainingSeconds.value = seconds

        timerJob = scope.launch {
            var current = seconds
            while (current > 0) {
                delay(1000)
                current--
                _remainingSeconds.value = current
            }
            
            try {
                Platform.playerController.pause()
                Platform.toast.show("定时关闭已生效，播放已暂停")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            _remainingSeconds.value = null
        }
    }

    /**
     * 停止/清除定时关闭
     */
    fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        _remainingSeconds.value = null
    }
}

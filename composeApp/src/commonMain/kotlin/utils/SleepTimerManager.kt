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

        // 同步注册系统级硬件闹钟（Doze 休眠防杀守护）
        Platform.playerController.setPlatformAlarm(minutes)

        timerJob = scope.launch {
            var current = totalSeconds
            while (current > 0) {
                delay(1000)
                current--
                _remainingSeconds.value = current
            }

            // 时间倒计时到零，触发停止服务自毁
            try {
                Platform.playerController.stopService()
                Platform.toast.show("定时关闭已生效，已暂停播放并注销服务")
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

        // 分钟级对齐设置底层闹钟
        val minutes = (seconds + 59) / 60
        if (minutes > 0) {
            Platform.playerController.setPlatformAlarm(minutes)
        }

        timerJob = scope.launch {
            var current = seconds
            while (current > 0) {
                delay(1000)
                current--
                _remainingSeconds.value = current
            }

            try {
                Platform.playerController.stopService()
                Platform.toast.show("定时关闭已生效，已暂停播放并注销服务")
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
        // 取消系统闹钟
        Platform.playerController.cancelPlatformAlarm()
    }
}

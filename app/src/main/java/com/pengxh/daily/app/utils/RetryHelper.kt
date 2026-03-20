package com.pengxh.daily.app.utils

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 打卡失败重试工具类
 *
 * 重试流程：
 * 1. 后台结束钉钉的所有进程
 * 2. 预热网络（检查网络连通性）
 * 3. 唤醒GPS定位服务
 * 4. 等待充足时间让系统、网络、定位稳定
 * 5. 重新打开钉钉进行打卡
 */
object RetryHelper {

    private val kTag = "RetryHelper"
    private var currentRetryCount = 0

    /**
     * 判断是否启用了失败重试功能
     */
    fun isRetryEnabled(): Boolean {
        return SaveKeyValues.getValue(Constant.RETRY_ON_FAIL_KEY, false) as Boolean
    }

    /**
     * 获取最大重试次数
     */
    fun getMaxRetryCount(): Int {
        return SaveKeyValues.getValue(
            Constant.RETRY_MAX_COUNT_KEY, Constant.DEFAULT_RETRY_COUNT
        ) as Int
    }

    /**
     * 判断是否还能继续重试
     */
    fun canRetry(): Boolean {
        return isRetryEnabled() && currentRetryCount < getMaxRetryCount()
    }

    /**
     * 获取当前重试次数
     */
    fun getCurrentRetryCount(): Int = currentRetryCount

    /**
     * 重置重试计数（在任务成功或新任务开始时调用）
     */
    fun resetRetryCount() {
        currentRetryCount = 0
    }

    /**
     * 执行重试流程
     *
     * @param context 上下文
     * @param onRetryStarted 重试开始回调
     * @param onRetryComplete 重试完成回调（钉钉已重新打开）
     * @param onRetryExhausted 重试次数耗尽回调
     */
    fun executeRetry(
        context: Context,
        onRetryStarted: ((Int) -> Unit)? = null,
        onRetryComplete: (() -> Unit)? = null,
        onRetryExhausted: (() -> Unit)? = null
    ) {
        if (!canRetry()) {
            Log.d(kTag, "重试次数已耗尽，当前: $currentRetryCount, 最大: ${getMaxRetryCount()}")
            onRetryExhausted?.invoke()
            return
        }

        currentRetryCount++
        val retryNum = currentRetryCount
        Log.d(kTag, "开始第 $retryNum 次重试，最大 ${getMaxRetryCount()} 次")
        onRetryStarted?.invoke(retryNum)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Step 1: 结束钉钉进程
                LogFileManager.writeLog("【重试第${retryNum}次】Step 1: 结束目标应用进程")
                killTargetApp(context)
                delay(5000) // 等待进程完全退出（增加到5秒）

                // Step 2: 预热网络
                LogFileManager.writeLog("【重试第${retryNum}次】Step 2: 预热网络连接")
                warmUpNetwork(context)
                delay(2000) // 等待网络稳定

                // Step 3: 唤醒GPS定位服务
                LogFileManager.writeLog("【重试第${retryNum}次】Step 3: 唤醒GPS定位")
                withContext(Dispatchers.Main) {
                    wakeUpLocation(context)
                }
                delay(5000) // 等待GPS定位稳定（关键：给定位服务足够时间）

                // Step 4: 重新打开钉钉（不传needCountDown，由调用方重新启动计时器）
                LogFileManager.writeLog("【重试第${retryNum}次】Step 4: 重新打开目标应用进行打卡")
                withContext(Dispatchers.Main) {
                    context.openApplication(true)
                    onRetryComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(kTag, "重试过程发生异常: ${e.message}")
                LogFileManager.writeLog("【重试第${retryNum}次】重试异常: ${e.message}")
                // 异常时也回调完成，让上层可以重新启动计时器
                withContext(Dispatchers.Main) {
                    onRetryComplete?.invoke()
                }
            }
        }
    }

    /**
     * 结束目标应用的所有进程
     */
    private fun killTargetApp(context: Context) {
        val targetPackage = Constant.getTargetApp()
        try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // 使用 killBackgroundProcesses（不需要 root）
            activityManager.killBackgroundProcesses(targetPackage)
            Log.d(kTag, "已请求系统结束 $targetPackage 的后台进程")

            // 通过 am force-stop 命令尝试强制停止（需要 SYSTEM 权限，可能失败）
            try {
                val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", targetPackage))
                process.waitFor()
                Log.d(kTag, "force-stop 命令执行完毕，exit code: ${process.exitValue()}")
                LogFileManager.writeLog("force-stop 执行结果: exitCode=${process.exitValue()}")
            } catch (e: Exception) {
                Log.w(kTag, "force-stop 命令执行失败（需要系统权限）: ${e.message}")
                LogFileManager.writeLog("force-stop 失败（需系统权限）: ${e.message}")
            }

            LogFileManager.writeLog("已结束目标应用 $targetPackage 的进程")
        } catch (e: Exception) {
            Log.e(kTag, "结束应用进程失败: ${e.message}")
            LogFileManager.writeLog("结束应用进程失败: ${e.message}")
        }
    }

    /**
     * 预热网络连接
     * 检查网络状态，并通过访问一个轻量 URL 激活网络连接
     */
    private fun warmUpNetwork(context: Context) {
        // 检查网络可用性
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)

        if (capabilities == null) {
            LogFileManager.writeLog("网络不可用，跳过网络预热")
            return
        }

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            else -> "其他"
        }
        LogFileManager.writeLog("当前网络类型: $networkType, 联网能力: $hasInternet")

        // 通过轻量请求激活网络
        try {
            val url = URL("https://www.baidu.com/favicon.ico")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            LogFileManager.writeLog("网络预热完成，响应码: $responseCode")
        } catch (e: Exception) {
            LogFileManager.writeLog("网络预热请求失败: ${e.message}")
        }

        // 第二次请求 - 确保网络完全就绪（访问钉钉相关域名）
        try {
            val url = URL("https://g.alicdn.com/favicon.ico")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            connection.connect()
            val responseCode = connection.responseCode
            connection.disconnect()
            LogFileManager.writeLog("阿里CDN预热完成，响应码: $responseCode")
        } catch (e: Exception) {
            LogFileManager.writeLog("阿里CDN预热请求失败（非致命）: ${e.message}")
        }
    }

    /**
     * 唤醒GPS定位服务
     * 通过请求定位权限和启动定位来确保GPS就绪
     * 钉钉极速打卡依赖位置信息，如果GPS未就绪则无法触发
     */
    private fun wakeUpLocation(context: Context) {
        try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled =
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            LogFileManager.writeLog("GPS状态: ${if (isGpsEnabled) "开启" else "关闭"}, 网络定位: ${if (isNetworkEnabled) "开启" else "关闭"}")

            if (!isGpsEnabled && !isNetworkEnabled) {
                LogFileManager.writeLog("GPS和网络定位均未开启，极速打卡可能无法触发")
            }

            // 通过发送定位相关的广播来唤醒系统定位服务
            // 这不需要定位权限，只是确保定位服务处于活跃状态
            try {
                val intent = Intent("android.location.GPS_ENABLED_CHANGE")
                intent.putExtra("enabled", true)
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                // 静默处理，这只是一个辅助手段
                Log.d(kTag, "发送GPS广播失败（非致命）: ${e.message}")
            }
        } catch (e: Exception) {
            LogFileManager.writeLog("唤醒定位服务失败: ${e.message}")
        }
    }
}

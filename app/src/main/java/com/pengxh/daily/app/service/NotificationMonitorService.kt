package com.pengxh.daily.app.service

import android.app.Notification
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pengxh.daily.app.extensions.backToMainActivity
import com.pengxh.daily.app.extensions.openApplication
import com.pengxh.daily.app.sqlite.DatabaseWrapper
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.BroadcastManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.EmailManager
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageType
import com.pengxh.daily.app.utils.RetryHelper
import com.pengxh.kt.lite.extensions.show
import com.pengxh.kt.lite.extensions.timestampToCompleteDate
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * @description: 状态栏监听服务
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 23:17
 */
class NotificationMonitorService : NotificationListenerService() {

    private val kTag = "MonitorService"
    private val emailManager by lazy { EmailManager(this) }
    private val batteryManager by lazy { getSystemService(BatteryManager::class.java) }
    private val auxiliaryApp = arrayOf(Constant.WECHAT, Constant.QQ, Constant.TIM, Constant.ZFB)

    /**
     * 有可用的并且和通知管理器连接成功时回调
     */
    override fun onListenerConnected() {
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.NOTICE_LISTENER_CONNECTED.action
        )
    }

    /**
     * 判断通知内容是否为远程指令
     *
     * 支持两种格式：
     * 1. 带前缀格式："#dt停止" "#dt启动" 等（推荐，不会被日常消息误触发）
     * 2. 精确匹配格式：整条消息只包含指令关键词（如整条消息就是"停止"）
     *
     * @param notice 通知内容
     * @param command 指令关键词
     * @return 是否匹配
     */
    private fun isRemoteCommand(notice: String, command: String): Boolean {
        val trimmed = notice.trim()
        val prefix = SaveKeyValues.getValue(
            Constant.REMOTE_COMMAND_PREFIX_KEY, Constant.REMOTE_COMMAND_PREFIX
        ) as String

        // 格式1：带前缀，如 "#dt停止"
        if (trimmed == "${prefix}${command}") return true

        // 格式2：精确匹配，整条消息就是指令关键词
        if (trimmed == command) return true

        return false
    }

    /**
     * 当有新通知到来时会回调
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        // 获取接收消息APP的包名
        val pkg = sbn.packageName
        // 获取接收消息的标题
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        // 获取接收消息的内容
        val notice = extras.getString(Notification.EXTRA_TEXT)
        if (notice.isNullOrBlank()) {
            return
        }

        val targetApp = Constant.getTargetApp()

        // 保存指定包名的通知，其他的一律不保存
        if (pkg == targetApp || pkg in auxiliaryApp) {
            NotificationBean().apply {
                packageName = pkg
                notificationTitle = title
                notificationMsg = notice
                postTime = System.currentTimeMillis().timestampToCompleteDate()
            }.also {
                DatabaseWrapper.insertNotice(it)
            }
        }

        // 目标应用打卡通知
        if (pkg == targetApp && notice.contains("成功")) {
            // 打卡成功，重置重试计数
            RetryHelper.resetRetryCount()

            // 先发送取消超时定时器广播，再返回主界面
            BroadcastManager.getDefault().sendBroadcast(
                this, MessageType.CANCEL_COUNT_DOWN_TIMER.action
            )

            backToMainActivity()
            "即将发送通知邮件，请注意查收".show(this)
            emailManager.sendEmail(null, notice, false)
        }

        // 辅助App远程指令（必须精确匹配或带前缀，防止日常消息误触发）
        if (pkg in auxiliaryApp) {
            when {
                // 电量查询 - 精确匹配
                isRemoteCommand(notice, "电量") -> {
                    val capacity = batteryManager.getIntProperty(
                        BatteryManager.BATTERY_PROPERTY_CAPACITY
                    )
                    emailManager.sendEmail(
                        "查询手机电量通知", "当前手机剩余电量为：${capacity}%", false
                    )
                }

                // 启动任务 - 精确匹配
                isRemoteCommand(notice, "启动") -> {
                    LogFileManager.writeLog("收到远程启动指令：$notice")
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.START_DAILY_TASK.action
                    )
                }

                // 停止任务 - 精确匹配
                isRemoteCommand(notice, "停止") -> {
                    LogFileManager.writeLog("收到远程停止指令：$notice")
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.STOP_DAILY_TASK.action
                    )
                }

                // 开始循环 - 精确匹配
                isRemoteCommand(notice, "开始循环") -> {
                    LogFileManager.writeLog("收到远程开始循环指令：$notice")
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, true)
                    emailManager.sendEmail(
                        "循环任务状态通知", "循环任务状态已更新为：开启", false
                    )
                }

                // 暂停循环 - 精确匹配
                isRemoteCommand(notice, "暂停循环") -> {
                    LogFileManager.writeLog("收到远程暂停循环指令：$notice")
                    SaveKeyValues.putValue(Constant.TASK_AUTO_START_KEY, false)
                    emailManager.sendEmail(
                        "循环任务状态通知", "循环任务状态已更新为：暂停", false
                    )
                }

                // 息屏 - 精确匹配
                isRemoteCommand(notice, "息屏") -> {
                    LogFileManager.writeLog("收到远程息屏指令：$notice")
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.SHOW_MASK_VIEW.action
                    )
                }

                // 亮屏 - 精确匹配
                isRemoteCommand(notice, "亮屏") -> {
                    LogFileManager.writeLog("收到远程亮屏指令：$notice")
                    BroadcastManager.getDefault().sendBroadcast(
                        this, MessageType.HIDE_MASK_VIEW.action
                    )
                }

                // 考勤记录 - 精确匹配
                isRemoteCommand(notice, "考勤记录") -> {
                    LogFileManager.writeLog("收到远程考勤记录查询指令：$notice")
                    var record = ""
                    var index = 1
                    DatabaseWrapper.loadCurrentDayNotice().forEach {
                        if (it.notificationMsg.contains("考勤打卡")) {
                            record += "【第${index}次】${it.notificationMsg}，时间：${it.postTime}\r\n"
                            index++
                        }
                    }
                    emailManager.sendEmail("当天考勤记录通知", record, false)
                }

                else -> {
                    // 自定义打卡指令 - 也使用精确匹配
                    val key = SaveKeyValues.getValue(Constant.TASK_COMMAND_KEY, "打卡") as String
                    if (isRemoteCommand(notice, key)) {
                        LogFileManager.writeLog("收到远程打卡指令：$notice")
                        openApplication(true)
                    }
                }
            }
        }
    }

    /**
     * 当有通知移除时会回调
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onListenerDisconnected() {
        BroadcastManager.getDefault().sendBroadcast(
            this, MessageType.NOTICE_LISTENER_DISCONNECTED.action
        )
    }
}
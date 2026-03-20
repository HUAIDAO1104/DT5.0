package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/29 12:42
 */
object Constant {
    const val RESET_TIME_KEY = "RESET_TIME_KEY"
    const val STAY_DD_TIMEOUT_KEY = "STAY_DD_TIMEOUT_KEY"
    const val GESTURE_DETECTOR_KEY = "GESTURE_DETECTOR_KEY"
    const val BACK_TO_HOME_KEY = "BACK_TO_HOME_KEY"
    const val TASK_COMMAND_KEY = "TASK_COMMAND_KEY"
    const val RANDOM_TIME_KEY = "RANDOM_TIME_KEY"
    const val RANDOM_MINUTE_RANGE_KEY = "RANDOM_MINUTE_RANGE_KEY"
    const val TASK_AUTO_START_KEY = "TASK_AUTO_START_KEY"
    const val TARGET_APP_KEY = "TARGET_APP_KEY"
    const val SKIP_HOLIDAY_KEY = "SKIP_HOLIDAY_KEY"
    const val RETRY_ON_FAIL_KEY = "RETRY_ON_FAIL_KEY"
    const val RETRY_MAX_COUNT_KEY = "RETRY_MAX_COUNT_KEY"
    const val DEFAULT_RETRY_COUNT = 2

    /**
     * 远程指令前缀，所有远程控制指令必须以此前缀开头
     * 例如发送 "#dt启动" "#dt停止" 才会触发，防止日常聊天消息误触发
     */
    const val REMOTE_COMMAND_PREFIX = "#dt"
    const val REMOTE_COMMAND_PREFIX_KEY = "REMOTE_COMMAND_PREFIX_KEY"

    const val DING_DING = "com.alibaba.android.rimet" // 钉钉
    const val FEI_SHU = "com.ss.android.lark" // 飞书
    const val WEWORK = "com.tencent.wework" // 企业微信

    const val WECHAT = "com.tencent.mm" // 微信
    const val QQ = "com.tencent.mobileqq" // QQ
    const val TIM = "com.tencent.tim" // TIM
    const val ZFB = "com.eg.android.AlipayGphone" // 支付宝

    const val FOREGROUND_RUNNING_SERVICE_TITLE = "为保证程序正常运行，请勿移除此通知"
    const val DEFAULT_RESET_HOUR = 0
    const val DEFAULT_OVER_TIME = 30

    // 目标APP
    fun getTargetApp(): String {
        val index = SaveKeyValues.getValue(TARGET_APP_KEY, 0) as Int
        return when (index) {
            0 -> DING_DING
//            1 -> FEI_SHU
//            2 -> WEWORK
            else -> DING_DING
        }
    }
}
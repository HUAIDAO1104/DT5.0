package com.pengxh.daily.app.extensions

import com.github.gzuliyujiang.wheelpicker.entity.TimeEntity
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.extensions.appendZero
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.text.SimpleDateFormat
import java.util.Locale

fun DailyTaskBean.convertToTimeEntity(): TimeEntity {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val date = dateFormat.parse("${TimeKit.getTodayDate()} ${this.time}")!!
    return TimeEntity.target(date)
}

fun DailyTaskBean.diffCurrent(): Pair<String, Int> {
    val needRandom = SaveKeyValues.getValue(Constant.RANDOM_TIME_KEY, true) as Boolean

    //18:00:59
    val array = this.time.split(":")
    var totalSeconds = array[0].toInt() * 3600 + array[1].toInt() * 60 + array[2].toInt()

    // 随机时间：使用双向偏移（±），让随机时间均匀分布在基准时间的前后
    // 例如设置7分钟 → 偏移范围 [-420, +420) 秒，即基准时间 ±7 分钟
    if (needRandom) {
        val minuteRange = SaveKeyValues.getValue(Constant.RANDOM_MINUTE_RANGE_KEY, 5) as Int

        // 双向随机：范围 [-minuteRange*60, +minuteRange*60)
        // 例如 minuteRange=7 → [-420, +420) 秒
        val maxOffsetSeconds = minuteRange * 60
        if (maxOffsetSeconds > 0) {
            // nextInt(-420, 420) 生成 [-420, 419] 的均匀分布
            val randomOffset = kotlin.random.Random.nextInt(-maxOffsetSeconds, maxOffsetSeconds)
            totalSeconds += randomOffset
        }

        // 确保时间在有效范围内 [00:00:00, 23:59:59]
        totalSeconds = totalSeconds.coerceIn(0, 86399)
    }

    // 转换回 时:分:秒 格式
    val hour = totalSeconds / 3600
    val minute = (totalSeconds % 3600) / 60
    val second = totalSeconds % 60

    val newTime = "${hour.appendZero()}:${minute.appendZero()}:${second.appendZero()}"

    //获取当前日期，计算时间差
    val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    val taskDateTime = "${TimeKit.getTodayDate()} $newTime"
    val taskDate = simpleDateFormat.parse(taskDateTime) ?: return Pair(newTime, 0)
    val currentMillis = System.currentTimeMillis()
    val diffSeconds = (taskDate.time - currentMillis) / 1000
    return Pair(newTime, diffSeconds.toInt())
}
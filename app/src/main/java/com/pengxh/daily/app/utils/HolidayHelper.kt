package com.pengxh.daily.app.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pengxh.kt.lite.extensions.readAssetsFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 节假日判断工具类
 *
 * 判断逻辑：
 * 1. 如果当天在 workdays（调休补班）列表中 → 工作日，需要打卡
 * 2. 如果当天在 holidays（法定假日）列表中 → 休息日，不打卡
 * 3. 如果当天是周六或周日 → 休息日，不打卡
 * 4. 其他情况 → 工作日，需要打卡
 */
object HolidayHelper {

    private val kTag = "HolidayHelper"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    // 缓存：年份 -> (holidays, workdays)
    private val cache = mutableMapOf<String, Pair<Set<String>, Set<String>>>()

    /**
     * 加载指定年份的节假日数据
     */
    private fun loadYearData(context: Context, year: String): Pair<Set<String>, Set<String>> {
        cache[year]?.let { return it }

        try {
            val json = context.readAssetsFile("holidays.json")
            val type = object : TypeToken<Map<String, Map<String, List<String>>>>() {}.type
            val allData: Map<String, Map<String, List<String>>> = Gson().fromJson(json, type)

            val yearData = allData[year]
            if (yearData != null) {
                val holidays = yearData["holidays"]?.toSet() ?: emptySet()
                val workdays = yearData["workdays"]?.toSet() ?: emptySet()
                val pair = Pair(holidays, workdays)
                cache[year] = pair
                return pair
            }
        } catch (e: Exception) {
            Log.e(kTag, "加载节假日数据失败: ${e.message}")
        }

        return Pair(emptySet(), emptySet())
    }

    /**
     * 判断今天是否为休息日（周末或法定节假日，但排除调休补班日）
     *
     * @return true 表示今天是休息日，不需要打卡
     */
    fun isTodayHoliday(context: Context): Boolean {
        val calendar = Calendar.getInstance()
        val today = dateFormat.format(calendar.time)
        val year = calendar.get(Calendar.YEAR).toString()

        val (holidays, workdays) = loadYearData(context, year)

        // 优先级1：调休补班日 → 工作日
        if (today in workdays) {
            Log.d(kTag, "$today 是调休补班日，需要打卡")
            return false
        }

        // 优先级2：法定节假日 → 休息日
        if (today in holidays) {
            Log.d(kTag, "$today 是法定节假日，不打卡")
            return true
        }

        // 优先级3：判断是否为周末
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            Log.d(kTag, "$today 是周末，不打卡")
            return true
        }

        Log.d(kTag, "$today 是普通工作日，需要打卡")
        return false
    }

    /**
     * 获取今天的休息日类型描述
     *
     * @return 描述文本，如"周末"、"法定节假日"，如果是工作日返回 null
     */
    fun getTodayHolidayDesc(context: Context): String? {
        val calendar = Calendar.getInstance()
        val today = dateFormat.format(calendar.time)
        val year = calendar.get(Calendar.YEAR).toString()

        val (holidays, workdays) = loadYearData(context, year)

        if (today in workdays) return null

        if (today in holidays) return "法定节假日"

        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return "周末"
        }

        return null
    }
}

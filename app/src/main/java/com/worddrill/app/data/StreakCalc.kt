package com.worddrill.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 连续打卡天数计算。纯逻辑，方便单元测试。
 *
 * 规则：今天练过就从今天开始往前数连续的天数；今天还没练不算断签，
 * 从昨天开始数（所以中午打开 App 时昨天的连续记录不会归零）。
 */
object StreakCalc {

    const val DATE_FORMAT = "yyyy-MM-dd"

    fun current(today: String, studiedDates: Set<String>): Int {
        val fmt = SimpleDateFormat(DATE_FORMAT, Locale.US).apply { isLenient = false }
        val parsed = runCatching { fmt.parse(today) }.getOrNull() ?: return 0
        val cal = Calendar.getInstance()
        cal.time = parsed
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (!studiedDates.contains(fmt.format(cal.time))) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        var streak = 0
        while (studiedDates.contains(fmt.format(cal.time))) {
            streak++
            cal.add(Calendar.DAY_OF_YEAR, -1)
            if (streak > 3650) break
        }
        return streak
    }
}

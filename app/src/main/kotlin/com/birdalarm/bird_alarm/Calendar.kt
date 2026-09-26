package com.birdalarm.bird_alarm

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 课表规则下的三种日子。 */
enum class ScheduleDay { EarlyClass, Workday, Holiday }

/**
 * 早八（第一节课 9 点前开始，兰大第一节 8:30）/ 无早八的工作日 / 节假日。
 * 工作日按 [ChinaWorkdayCalendar]（含调休）；课表来自 Cadence，放假调休它已经按校历算过。
 * 课表里没有这天的数据（没装 Cadence、没授权、超出读取范围）时，工作日一律按早八算：
 * 宁可早响，也不能让「无早八」的闹钟睡过第一节课。
 */
fun scheduleDayOf(date: LocalDate): ScheduleDay {
    val first = CadenceSchedule.firstClassMinute(date)
    if (first != null && first >= 0 && first < 9 * 60) return ScheduleDay.EarlyClass
    if (!ChinaWorkdayCalendar.isWorkday(date)) return ScheduleDay.Holiday
    return if (first == null) ScheduleDay.EarlyClass else ScheduleDay.Workday
}

/** 从 Cadence 读来的课表，只留每天第一节课的开始时刻。只在内存里，每次排闹钟前重读。 */
object CadenceSchedule {
    // 日期 → 第一节课开始的分钟数（0 点起算），-1 = 那天没课；不在表里 = 不知道。
    @Volatile private var firstClass: Map<LocalDate, Int> = emptyMap()

    fun firstClassMinute(date: LocalDate): Int? = firstClass[date]

    /**
     * 用 [firstDay] 起 [days] 天里所有课的开始时刻（毫秒）整体替换缓存；[starts] 为 null
     * 表示读不到，这些天都当「不知道」。有变化返回 true。
     */
    fun update(firstDay: LocalDate, days: Int, starts: List<Long>?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val next = HashMap<LocalDate, Int>()
        if (starts != null) {
            for (i in 0 until days) next[firstDay.plusDays(i.toLong())] = -1
            for (ms in starts) {
                val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
                val current = next[t.toLocalDate()] ?: continue
                val minute = t.hour * 60 + t.minute
                if (current < 0 || minute < current) next[t.toLocalDate()] = minute
            }
        }
        val changed = next != firstClass
        firstClass = next
        return changed
    }
}

/**
 * 中国节假日数据：在线获取（timor.tech），带本地缓存；离线/失败时回退到
 * [ChinaWorkdayCalendar] 内置的 2026 表。数据为 日期 → 是否放假。
 */
object ChinaHolidayData {
    const val DATA_PREFIX = "holiday_cn_data_"
    const val FETCHED_PREFIX = "holiday_cn_fetched_"
    private const val REFRESH_MILLIS = 7 * 86_400_000L
    @Volatile private var offDays: Map<LocalDate, Boolean> = emptyMap()

    /** 某日是否放假：true=放假，false=调休补班，null=无在线数据。 */
    fun lookup(date: LocalDate): Boolean? = offDays[date]

    /** 解析 timor.tech 的返回并合并进内存；解析到有效数据返回 true。 */
    fun merge(body: String): Boolean = try {
        val holiday = JSONObject(body).optJSONObject("holiday")
        val parsed = HashMap<LocalDate, Boolean>()
        holiday?.keys()?.forEach { key ->
            val day = holiday.optJSONObject(key) ?: return@forEach
            val date = day.optString("date").takeIf { it.isNotEmpty() } ?: return@forEach
            // 宽松解析：holiday 字段可能是布尔，也可能因接口变动成数字 / 字符串
            val raw = day.opt("holiday")
            parsed[LocalDate.parse(date)] = raw == true || raw == 1 || raw?.toString() == "true"
        }
        if (parsed.isNotEmpty()) offDays = offDays + parsed
        parsed.isNotEmpty()
    } catch (_: Exception) {
        false
    }

    /** 用本地缓存填充内存数据（快速、无网络）。 */
    fun loadCache(store: KeyValueStore, today: LocalDate = LocalDate.now()) {
        for (year in listOf(today.year, today.year + 1)) store.getString("$DATA_PREFIX$year")?.let { merge(it) }
    }

    /** 后台刷新当年与次年的数据，一周内刷过就跳过；有更新返回 true（调用方据此重排闹钟）。 */
    fun refresh(store: KeyValueStore, fetch: (String) -> String, now: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        val year = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).year
        for (y in listOf(year, year + 1)) {
            val fetchedAt = store.getLong("$FETCHED_PREFIX$y") ?: 0L
            if (store.getString("$DATA_PREFIX$y") != null && now - fetchedAt < REFRESH_MILLIS) continue
            try {
                val body = fetch("https://timor.tech/api/holiday/year/$y")
                if (merge(body)) {
                    store.putString("$DATA_PREFIX$y", body)
                    store.putLong("$FETCHED_PREFIX$y", now)
                    changed = true
                }
            } catch (_: Exception) {
                // 离线 / 失败：继续用缓存或内置 2026 表
            }
        }
        return changed
    }
}

object ChinaWorkdayCalendar {
    private val holidays2026 = listOf(
        "2026-01-01", "2026-01-02", "2026-01-03",
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18", "2026-02-19", "2026-02-20", "2026-02-21", "2026-02-22", "2026-02-23",
        "2026-04-04", "2026-04-05", "2026-04-06",
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
        "2026-06-19", "2026-06-20", "2026-06-21",
        "2026-09-25", "2026-09-26", "2026-09-27",
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07",
    ).map(LocalDate::parse).toSet()

    private val adjustedWorkdays2026 = listOf(
        "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10",
    ).map(LocalDate::parse).toSet()

    fun isWorkday(date: LocalDate): Boolean {
        offDayOverride(date)?.let { return !it }
        return date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    }

    // 该日期是否放假：true=放假，false=调休补班，null=普通日（按周一~周五判断）。
    // 优先用在线节假日数据，无则回退到内置 2026 表。
    private fun offDayOverride(date: LocalDate): Boolean? = ChinaHolidayData.lookup(date)
        ?: when (date) {
            in adjustedWorkdays2026 -> false
            in holidays2026 -> true
            else -> null
        }
}

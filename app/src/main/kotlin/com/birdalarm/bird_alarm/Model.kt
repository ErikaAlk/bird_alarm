package com.birdalarm.bird_alarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/** 键值存储：生产用 SharedPreferences，单测用内存表。 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

/** 闹钟的重复规则。[key] 是存档里的名字，和 Flutter 版一致。 */
enum class RepeatRule(val key: String, val label: String) {
    Weekdays("weekdays", "自定义"),
    ChinaWorkdays("chinaWorkdays", "工作日"),
    ClassSchedule("classSchedule", "课表"),
    ChinaHolidays("chinaHolidays", "节假日"),
}

data class BirdAlarm(
    val id: String,
    val time: LocalTime,
    /** 1 = 周一 … 7 = 周日。自定义规则下为空表示每天都响（Flutter 版就是这么算的）。 */
    val repeatDays: Set<Int>,
    val rule: RepeatRule,
    val enabled: Boolean,
    val label: String,
    /** 光闹钟比鸟鸣提前多少分钟开始亮（0~30），只在设置里打开「联动 HA 光闹钟」时生效。 */
    val lightLeadMinutes: Int = DEFAULT_LIGHT_LEAD,
    /** 「课表」规则下没有早八的工作日响这个时间；有早八那天响 [time]。其他规则不用它。 */
    val noEarlyTime: LocalTime = DEFAULT_NO_EARLY,
) {
    /** 这个闹钟在 [date] 那天几点响；那天不响返回 null。某天几点响统一走这里。 */
    fun timeOn(date: LocalDate): LocalTime? = when (rule) {
        RepeatRule.ClassSchedule -> when (scheduleDayOf(date)) {
            ScheduleDay.EarlyClass -> time
            ScheduleDay.Workday -> noEarlyTime
            ScheduleDay.Holiday -> null
        }
        RepeatRule.ChinaWorkdays -> time.takeIf { ChinaWorkdayCalendar.isWorkday(date) }
        // 休息日：法定节假日 + 正常放假的周末；调休补班日与普通工作日不响
        RepeatRule.ChinaHolidays -> time.takeIf { !ChinaWorkdayCalendar.isWorkday(date) }
        RepeatRule.Weekdays -> time.takeIf { repeatDays.isEmpty() || date.dayOfWeek.value in repeatDays }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", time.hour)
        .put("minute", time.minute)
        .put("repeatDays", JSONArray(repeatDays.sorted()))
        .put("repeatRule", rule.key)
        .put("enabled", enabled)
        .put("label", label)
        .put("lightLeadMinutes", lightLeadMinutes)
        .put("noEarlyHour", noEarlyTime.hour)
        .put("noEarlyMinute", noEarlyTime.minute)

    companion object {
        const val DEFAULT_LIGHT_LEAD = 10
        const val DEFAULT_LABEL = "鸟鸣唤醒"
        val DEFAULT_NO_EARLY: LocalTime = LocalTime.of(8, 30)

        fun fromJson(json: JSONObject): BirdAlarm = BirdAlarm(
            id = json.getString("id"),
            time = LocalTime.of(json.optInt("hour", 7), json.optInt("minute", 0)),
            repeatDays = json.optJSONArray("repeatDays")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty(),
            rule = parseRule(json),
            enabled = json.optBoolean("enabled", true),
            label = json.optString("label", "晨间鸟鸣"),
            lightLeadMinutes = json.optInt("lightLeadMinutes", DEFAULT_LIGHT_LEAD).coerceIn(0, 30),
            noEarlyTime = LocalTime.of(
                json.optInt("noEarlyHour", DEFAULT_NO_EARLY.hour),
                json.optInt("noEarlyMinute", DEFAULT_NO_EARLY.minute),
            ),
        )

        private fun parseRule(json: JSONObject): RepeatRule = when (json.optString("repeatRule")) {
            "chinaWorkdays" -> RepeatRule.ChinaWorkdays
            "chinaHolidays" -> RepeatRule.ChinaHolidays
            "classSchedule" -> RepeatRule.ClassSchedule
            // 只在没合并的 v1.6.0 测试包里出现过的两条规则，退回工作日：每个工作日照原时间响，宁早勿漏
            "earlyClass", "noEarlyClass" -> RepeatRule.ChinaWorkdays
            "weekdays" -> RepeatRule.Weekdays
            // 更早的存档只有 useChinaWorkdays 布尔字段
            else -> if (json.optBoolean("useChinaWorkdays", false)) RepeatRule.ChinaWorkdays else RepeatRule.Weekdays
        }

        fun listFromJson(raw: String): List<BirdAlarm> = JSONArray(raw).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        fun listToJson(alarms: List<BirdAlarm>): String = JSONArray(alarms.map { it.toJson() }).toString()
    }
}

/** 一条鸟鸣。内置的有 [assetPath]，下载 / 导入的有 [localPath]，只加进音库没下载的只有 [url]。 */
data class BirdSound(
    val id: String,
    val cnName: String,
    val enName: String,
    val sciName: String,
    val source: String,
    val url: String? = null,
    val localPath: String? = null,
    val assetPath: String? = null,
) {
    val playable: Boolean get() = !url.isNullOrEmpty() || localPath != null || assetPath != null
    val builtIn: Boolean get() = id.startsWith("starter-")

    /** 原生响铃引用它的路径：本机文件用绝对路径，内置的用 APK 里的 asset 路径。只能在线播放的不进抽取池。 */
    val nativeRef: String? get() = localPath ?: assetPath

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("cnName", cnName).put("enName", enName).put("sciName", sciName).put("source", source)
        .apply {
            url?.let { put("url", it) }
            localPath?.let { put("localPath", it) }
            assetPath?.let { put("assetPath", it) }
        }

    companion object {
        fun fromJson(json: JSONObject): BirdSound = BirdSound(
            id = json.getString("id"),
            cnName = json.optStringOrNull("cnName") ?: json.optStringOrNull("enName") ?: "未知鸟种",
            enName = json.optString("enName"),
            sciName = json.optString("sciName"),
            source = json.optStringOrNull("source") ?: "本地",
            url = json.optStringOrNull("url"),
            localPath = json.optStringOrNull("localPath"),
            assetPath = json.optStringOrNull("assetPath"),
        )

        fun listFromJson(raw: String): List<BirdSound> = JSONArray(raw).let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        fun listToJson(sounds: List<BirdSound>): String = JSONArray(sounds.map { it.toJson() }).toString()
    }
}

/** IOC / AviList 名录里的一种鸟。 */
data class BirdName(val sci: String, val display: String, val cn: String, val en: String) {
    fun matches(query: String): Boolean =
        display.contains(query, true) || cn.contains(query, true) || en.contains(query, true) || sci.contains(query, true)
}

/** 缺这个键或值是 null 时返回 null（optString 会返回空串或 "null"）。 */
fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

// ─────────────────────────── 排程：纯函数，原生和界面共用一套 ───────────────────────────

/**
 * 某个闹钟在 (from, from+366 天) 内的下一次响铃；找不到返回 null。
 * 窗口取一年：「节假日」这类规则相邻两次可能隔好几天（旧的 8 天窗口会让闹钟被静默取消）。
 * [skip] 是被「倒计时通知 → 关闭闹钟」跳过的那一次，跳过它看下一次。
 */
fun BirdAlarm.nextOccurrence(from: LocalDateTime, skip: LocalDateTime? = null): LocalDateTime? {
    for (offset in 0L until 366L) {
        val day = from.toLocalDate().plusDays(offset)
        val at = day.atTime(timeOn(day) ?: continue)
        if (!at.isAfter(from) || at == skip) continue
        return at
    }
    return null
}

/** 所有启用的闹钟里最早的一次（严格晚于 [after]）。 */
fun nextEnabledOccurrence(alarms: List<BirdAlarm>, after: LocalDateTime, skip: LocalDateTime? = null): Pair<LocalDateTime, BirdAlarm>? =
    alarms.filter { it.enabled }
        .mapNotNull { a -> a.nextOccurrence(after, skip)?.let { it to a } }
        .minByOrNull { it.first }

/**
 * 接下来若干次（跨所有启用的闹钟）的响铃，升序。下发给原生：每次响铃后 / 在通知里关掉后，
 * 原生据此续排下一次，不用打开 App；光闹钟也按它推亮灯时刻。
 */
fun upcomingOccurrences(alarms: List<BirdAlarm>, now: LocalDateTime, skip: LocalDateTime? = null, count: Int = 8): List<Pair<LocalDateTime, BirdAlarm>> {
    val result = ArrayList<Pair<LocalDateTime, BirdAlarm>>()
    var cursor = now
    repeat(count) {
        val next = nextEnabledOccurrence(alarms, cursor, skip) ?: return result
        result += next
        cursor = next.first
    }
    return result
}

/** 刚刚（12 小时内）响过的那个闹钟：响铃页拿它显示标签。贪睡后再响时它还是原来那个。 */
fun lastOccurrence(alarms: List<BirdAlarm>, now: LocalDateTime): BirdAlarm? =
    alarms.filter { it.enabled }
        .mapNotNull { a ->
            (0L..1L).map { now.toLocalDate().minusDays(it) }
                .mapNotNull { d -> a.timeOn(d)?.let { d.atTime(it) } }
                .filter { !it.isAfter(now) && Duration.between(it, now).toHours() < 12 }
                .maxOrNull()?.let { it to a }
        }
        .maxByOrNull { it.first }?.second

/** 每次响铃对应的光闹钟开始亮的时刻：响铃时间减去该闹钟的提前量，去重、升序。 */
fun lightStartTimes(occurrences: List<Pair<LocalDateTime, BirdAlarm>>): List<LocalDateTime> =
    occurrences.map { (at, alarm) -> at.minusMinutes(alarm.lightLeadMinutes.toLong()) }.distinct().sorted()

/** 「7 小时 3 分钟」这样的剩余时间；没有下一次或已过返回 null。只在 App 内显示，不进常驻通知。 */
fun countdownText(now: LocalDateTime, target: LocalDateTime?): String? {
    if (target == null || target.isBefore(now)) return null
    val d = Duration.between(now, target)
    val days = d.toDays()
    val hours = d.toHours() % 24
    val minutes = d.toMinutes() % 60
    return when {
        days > 0 -> if (hours > 0) "$days 天 $hours 小时" else "$days 天"
        d.toHours() > 0 -> if (minutes > 0) "${d.toHours()} 小时 $minutes 分钟" else "${d.toHours()} 小时"
        d.toMinutes() > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}

/** 闹钟页大标题下那行状态（DESIGN §14：将来的时间写“距离下次响铃还有 7 小时 3 分钟”）。 */
fun nextRingStatus(now: LocalDateTime, next: LocalDateTime?): String =
    countdownText(now, next)?.let { "距离下次响铃还有 $it" } ?: "暂无启用的闹钟"

private val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun weekdayShort(day: Int): String = weekdayNames[day - 1].substring(1)

/** 闹钟卡片元信息里的重复规则。 */
fun repeatText(alarm: BirdAlarm): String = when (alarm.rule) {
    RepeatRule.ChinaWorkdays -> "工作日"
    RepeatRule.ChinaHolidays -> "节假日"
    RepeatRule.ClassSchedule -> "课表"
    RepeatRule.Weekdays -> weekdaysText(alarm.repeatDays)
}

fun weekdaysText(days: Set<Int>): String = when {
    days.isEmpty() || days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "周一至周五"
    days == setOf(6, 7) -> "周末"
    else -> days.sorted().joinToString(" ") { weekdayNames[it - 1] }
}

fun LocalTime.hhmm(): String = "%02d:%02d".format(hour, minute)

/** 「今天 07:30」「明天 06:50」「9月28日 07:00」。 */
fun shortDateTime(t: LocalDateTime, today: LocalDate = LocalDate.now()): String {
    val day = when (t.toLocalDate()) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> "${t.monthValue}月${t.dayOfMonth}日"
    }
    return "$day ${t.toLocalTime().hhmm()}"
}

fun LocalDateTime.epochMillis(zone: ZoneId = ZoneId.systemDefault()): Long = atZone(zone).toInstant().toEpochMilli()

fun millisToLocal(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

// ─────────────────────────── 每日一鸟 ───────────────────────────

/**
 * 按日期定随机种子挑「每日一鸟」：同一天永远是同一只，隔天自动换。
 * 只挑有中文名的鸟种；名录还没加载完（为空）时返回 null。
 */
fun pickDailyBird(names: List<BirdName>, day: LocalDate): BirdName? {
    val named = names.filter { it.cn.isNotEmpty() }
    if (named.isEmpty()) return null
    return named[Random(day.year * 10000 + day.monthValue * 100 + day.dayOfMonth).nextInt(named.size)]
}

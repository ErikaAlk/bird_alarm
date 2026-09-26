package com.birdalarm.bird_alarm

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 早八 / 无早八 / 节假日的判定、「课表」闹钟、排程与光闹钟时刻。 */
class ScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    // 2026-09-28 周一、09-29 周二、09-30 周三是普通工作日；10-03 是国庆假期
    private val monday = LocalDate.of(2026, 9, 28)

    private fun ms(month: Int, day: Int, hour: Int, minute: Int) = LocalDateTime.of(2026, month, day, hour, minute).epochMillis(zone)

    private fun alarm(rule: RepeatRule, time: LocalTime = LocalTime.of(7, 0), days: Set<Int> = emptySet(), lead: Int = 10, enabled: Boolean = true, id: String = "a") =
        BirdAlarm(id, time, days, rule, enabled, "", lead, LocalTime.of(8, 30))

    @After
    fun reset() {
        CadenceSchedule.update(monday, 0, null, zone)
    }

    @Test
    fun `第一节 9 点前是早八，9 点整不是`() {
        CadenceSchedule.update(monday, 3, listOf(ms(9, 28, 10, 20), ms(9, 28, 8, 30), ms(9, 29, 9, 0)), zone)
        assertEquals(ScheduleDay.EarlyClass, scheduleDayOf(LocalDate.of(2026, 9, 28)))
        assertEquals(ScheduleDay.Workday, scheduleDayOf(LocalDate.of(2026, 9, 29)))
        // 读到了、那天没课的工作日：无早八
        assertEquals(ScheduleDay.Workday, scheduleDayOf(LocalDate.of(2026, 9, 30)))
    }

    @Test
    fun `读不到课表的工作日按早八算，休息日还是节假日`() {
        CadenceSchedule.update(monday, 3, null, zone)
        assertEquals(ScheduleDay.EarlyClass, scheduleDayOf(LocalDate.of(2026, 9, 29)))
        assertEquals(ScheduleDay.Holiday, scheduleDayOf(LocalDate.of(2026, 10, 3)))
        // 超出读取范围的工作日同样按早八
        CadenceSchedule.update(monday, 1, emptyList(), zone)
        assertEquals(ScheduleDay.Workday, scheduleDayOf(LocalDate.of(2026, 9, 28)))
        assertEquals(ScheduleDay.EarlyClass, scheduleDayOf(LocalDate.of(2026, 9, 29)))
    }

    @Test
    fun `课表没变时不报变化`() {
        val starts = listOf(ms(9, 28, 8, 30))
        CadenceSchedule.update(monday, 2, starts, zone)
        assertFalse(CadenceSchedule.update(monday, 2, starts, zone))
        assertTrue(CadenceSchedule.update(monday, 2, emptyList(), zone))
    }

    @Test
    fun `「课表」闹钟一个管两种工作日，休息日不响`() {
        val a = alarm(RepeatRule.ClassSchedule)
        CadenceSchedule.update(monday, 2, listOf(ms(9, 28, 8, 30), ms(9, 29, 10, 20)), zone)
        assertEquals(LocalTime.of(7, 0), a.timeOn(LocalDate.of(2026, 9, 28)))
        assertEquals(LocalTime.of(8, 30), a.timeOn(LocalDate.of(2026, 9, 29)))
        // 读取范围外的工作日：按早八
        assertEquals(LocalTime.of(7, 0), a.timeOn(LocalDate.of(2026, 9, 30)))
        assertNull(a.timeOn(LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun `工作日和节假日规则按调休走`() {
        val work = alarm(RepeatRule.ChinaWorkdays)
        val rest = alarm(RepeatRule.ChinaHolidays)
        // 10-10 周六是调休补班日
        assertEquals(LocalTime.of(7, 0), work.timeOn(LocalDate.of(2026, 10, 10)))
        assertNull(rest.timeOn(LocalDate.of(2026, 10, 10)))
        // 10-01 国庆
        assertNull(work.timeOn(LocalDate.of(2026, 10, 1)))
        assertEquals(LocalTime.of(7, 0), rest.timeOn(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `光闹钟时刻 = 响铃减提前量，去重升序`() {
        val at = LocalDateTime.of(2026, 9, 29, 7, 0)
        assertEquals(
            listOf(LocalDateTime.of(2026, 9, 29, 6, 50), LocalDateTime.of(2026, 9, 30, 7, 0)),
            lightStartTimes(listOf(at.plusDays(1) to alarm(RepeatRule.ClassSchedule, lead = 0), at to alarm(RepeatRule.ClassSchedule, lead = 10), at.plusMinutes(5) to alarm(RepeatRule.ClassSchedule, lead = 15))),
        )
    }

    @Test
    fun `接下来几次响铃跨闹钟按时间排，跳过被通知关掉的那一次`() {
        val now = LocalDateTime.of(2026, 9, 28, 6, 0)
        val a = alarm(RepeatRule.Weekdays, LocalTime.of(7, 0), id = "a")
        val b = alarm(RepeatRule.Weekdays, LocalTime.of(6, 30), setOf(1), id = "b")
        val off = alarm(RepeatRule.Weekdays, LocalTime.of(6, 10), enabled = false, id = "off")
        val upcoming = upcomingOccurrences(listOf(a, b, off), now, count = 3)
        assertEquals(listOf(LocalDateTime.of(2026, 9, 28, 6, 30), LocalDateTime.of(2026, 9, 28, 7, 0), LocalDateTime.of(2026, 9, 29, 7, 0)), upcoming.map { it.first })
        val skipped = upcomingOccurrences(listOf(a, b), now, skip = LocalDateTime.of(2026, 9, 28, 6, 30), count = 1)
        assertEquals(LocalDateTime.of(2026, 9, 28, 7, 0), skipped.single().first)
    }

    @Test
    fun `节假日规则隔着长假也找得到下一次`() {
        // 9-28 起的下一个休息日是 10-01（国庆），而不是被 8 天窗口漏掉
        val rest = alarm(RepeatRule.ChinaHolidays)
        assertEquals(LocalDateTime.of(2026, 10, 1, 7, 0), rest.nextOccurrence(LocalDateTime.of(2026, 9, 28, 8, 0)))
    }

    @Test
    fun `响铃页取刚响过的那个闹钟，贪睡后再响还是它`() {
        val early = alarm(RepeatRule.Weekdays, LocalTime.of(7, 0), id = "early").copy(label = "起床")
        val late = alarm(RepeatRule.Weekdays, LocalTime.of(9, 0), id = "late").copy(label = "上课")
        assertEquals("起床", lastOccurrence(listOf(early, late), LocalDateTime.of(2026, 9, 28, 7, 5))?.label)
        // 上一次是昨天，已经超过 12 小时
        assertNull(lastOccurrence(listOf(early, late), LocalDateTime.of(2026, 9, 28, 6, 0)))
    }

    @Test
    fun `Flutter 版的存档照原样读得出来`() {
        val a = BirdAlarm.fromJson(JSONObject("""{"id":"1","hour":6,"minute":45,"repeatDays":[1,3],"repeatRule":"classSchedule","enabled":false,"label":"x","lightLeadMinutes":45,"noEarlyHour":8,"noEarlyMinute":0}"""))
        assertEquals(LocalTime.of(6, 45), a.time)
        assertEquals(setOf(1, 3), a.repeatDays)
        assertEquals(RepeatRule.ClassSchedule, a.rule)
        assertFalse(a.enabled)
        assertEquals(30, a.lightLeadMinutes) // 超出 0~30 的截断
        assertEquals(LocalTime.of(8, 0), a.noEarlyTime)
        // 更早的只有 useChinaWorkdays；没合并的测试包里的两条规则退回工作日
        assertEquals(RepeatRule.ChinaWorkdays, BirdAlarm.fromJson(JSONObject("""{"id":"2","useChinaWorkdays":true}""")).rule)
        assertEquals(RepeatRule.ChinaWorkdays, BirdAlarm.fromJson(JSONObject("""{"id":"3","repeatRule":"noEarlyClass"}""")).rule)
        // 来回一遍不丢字段
        assertEquals(a, BirdAlarm.fromJson(a.toJson()))
    }

    @Test
    fun `覆盖安装时只搬 Flutter 自己的条目，去掉前缀`() {
        val moved = flutterEntries(mapOf("flutter.bird_alarm_alarms" to "[]", "flutter.bird_alarm_fade_in_seconds" to 30L, "other" to "x", "flutter.nothing" to null))
        assertEquals(mapOf("bird_alarm_alarms" to "[]", "bird_alarm_fade_in_seconds" to 30L), moved)
    }

    @Test
    fun `在线节假日数据宽松解析`() {
        val body = """{"code":0,"holiday":{"01-01":{"holiday":true,"date":"2099-01-01"},"01-04":{"holiday":false,"date":"2099-01-04"},"01-05":{"holiday":"true","date":"2099-01-05"}}}"""
        assertTrue(ChinaHolidayData.merge(body))
        assertFalse(ChinaWorkdayCalendar.isWorkday(LocalDate.of(2099, 1, 1)))
        assertTrue(ChinaWorkdayCalendar.isWorkday(LocalDate.of(2099, 1, 4))) // 周日补班
        assertFalse(ChinaWorkdayCalendar.isWorkday(LocalDate.of(2099, 1, 5)))
        assertFalse(ChinaHolidayData.merge("不是 JSON"))
    }
}

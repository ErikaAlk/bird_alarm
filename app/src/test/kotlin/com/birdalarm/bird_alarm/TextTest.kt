package com.birdalarm.bird_alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 「距离下次响铃还有多久」、重复规则文案、每日一鸟的挑选。都是纯函数。 */
class TextTest {
    private val now = LocalDateTime.of(2026, 7, 26, 22, 0)

    @Test
    fun `按剩余时间挑单位`() {
        assertEquals("不到 1 分钟", countdownText(now, now.plusSeconds(30)))
        assertEquals("42 分钟", countdownText(now, now.plusMinutes(42)))
        assertEquals("8 小时 12 分钟", countdownText(now, now.plusHours(8).plusMinutes(12)))
        assertEquals("9 小时", countdownText(now, now.plusHours(9)))
        assertEquals("2 天 3 小时", countdownText(now, now.plusDays(2).plusHours(3)))
        assertEquals("3 天", countdownText(now, now.plusDays(3)))
        assertEquals("距离下次响铃还有 42 分钟", nextRingStatus(now, now.plusMinutes(42)))
    }

    @Test
    fun `没有闹钟或时刻已过时不显示倒计时`() {
        assertNull(countdownText(now, null))
        assertNull(countdownText(now, now.minusMinutes(1)))
        assertEquals("暂无启用的闹钟", nextRingStatus(now, null))
    }

    @Test
    fun `重复规则的文案`() {
        fun weekdays(vararg d: Int) = repeatText(BirdAlarm("x", LocalTime.of(7, 0), d.toSet(), RepeatRule.Weekdays, true, ""))
        // 一天都不选按每天响（排程就是这么算的），文案不能写成只响一次
        assertEquals("每天", weekdays())
        assertEquals("每天", weekdays(1, 2, 3, 4, 5, 6, 7))
        assertEquals("周一至周五", weekdays(1, 2, 3, 4, 5))
        assertEquals("周一 周三", weekdays(3, 1))
        assertEquals("今天 07:30", shortDateTime(LocalDateTime.of(2026, 7, 26, 7, 30), LocalDate.of(2026, 7, 26)))
        assertEquals("9月1日 06:50", shortDateTime(LocalDateTime.of(2026, 9, 1, 6, 50), LocalDate.of(2026, 7, 26)))
    }

    private fun names(count: Int) = (0 until count).map { BirdName("Genus species$it", "鸟$it", "鸟$it", "Bird $it") }

    @Test
    fun `每日一鸟同一天固定同一只，隔天自动换`() {
        val names = names(500)
        val today = pickDailyBird(names, LocalDate.of(2026, 7, 26))
        assertEquals(today, pickDailyBird(names, LocalDate.of(2026, 7, 26)))
        assertNotEquals(today, pickDailyBird(names, LocalDate.of(2026, 7, 27)))
    }

    @Test
    fun `名录还没加载好时没有每日一鸟，没有中文名的不进候选`() {
        assertNull(pickDailyBird(emptyList(), LocalDate.of(2026, 7, 26)))
        val picked = pickDailyBird(listOf(BirdName("Genus a", "A", "", "A"), BirdName("Genus b", "乙", "乙", "B")), LocalDate.of(2026, 7, 26))
        assertEquals("Genus b", picked?.sci)
    }
}

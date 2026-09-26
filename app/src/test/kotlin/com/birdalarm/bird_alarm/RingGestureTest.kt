package com.birdalarm.bird_alarm

import com.birdalarm.bird_alarm.ui.Swipe
import com.birdalarm.bird_alarm.ui.passedThreshold
import com.birdalarm.bird_alarm.ui.swipeOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 锁屏响铃页的盲操手势：上滑关闭 / 下滑贪睡，小幅滑动不算数；三种震法必须能闭眼分辨。 */
class RingGestureTest {
    @Test
    fun `上滑关闭，下滑贪睡`() {
        assertEquals(Swipe.Dismiss, swipeOutcome(-200f, 0f))
        assertEquals(Swipe.Snooze, swipeOutcome(200f, 0f))
        // 距离不够但甩得快也算
        assertEquals(Swipe.Dismiss, swipeOutcome(-40f, -1200f))
        assertEquals(Swipe.Snooze, swipeOutcome(40f, 1200f))
    }

    @Test
    fun `蹭一下（不到阈值）什么也不会发生，也不震`() {
        assertEquals(Swipe.None, swipeOutcome(-60f, -300f))
        assertEquals(Swipe.None, swipeOutcome(60f, 300f))
        assertFalse(passedThreshold(-60f))
        assertFalse(passedThreshold(119f))
        assertTrue(passedThreshold(-120f))
    }

    @Test
    fun `关闭、贪睡、越线三种震法互不相同`() {
        val patterns = HapticPattern.entries.map { it.timings.toList() to it.amplitudes.toList() }
        assertEquals(patterns.size, patterns.toSet().size)
        // 关闭是一记长震，贪睡是三记短震
        assertEquals(1, HapticPattern.Dismiss.amplitudes.count { it > 0 })
        assertEquals(3, HapticPattern.Snooze.amplitudes.count { it > 0 })
        assertTrue(HapticPattern.Dismiss.timings.max() > HapticPattern.Snooze.timings.max())
    }
}

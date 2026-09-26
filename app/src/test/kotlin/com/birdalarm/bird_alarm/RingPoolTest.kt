package com.birdalarm.bird_alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/** 响铃抽鸟：下载的鸟鸣读不到（重启后还没解锁）时只从内置的里抽，不能抽到它再退成系统铃声。 */
class RingPoolTest {
    private val downloaded = "/data/user/0/com.birdalarm.bird_alarm/files/bird_sounds/xc-1.m4a"
    private val starter = "sounds/parus_cinereus.m4a"

    @Test
    fun `解锁后下载的和内置的都能抽`() {
        assertEquals(listOf(starter, downloaded), ringablePool("$starter\n$downloaded") { true })
    }

    @Test
    fun `解锁前跳过下载的`() {
        assertEquals(listOf(starter), ringablePool("$starter\n$downloaded") { false })
    }

    @Test
    fun `只剩下载的且读不到时退回内置`() {
        assertEquals(BirdAlarmAssets.sounds, ringablePool(downloaded) { false })
    }

    @Test
    fun `没下发音库时用内置`() {
        assertEquals(BirdAlarmAssets.sounds, ringablePool(null))
        assertEquals(BirdAlarmAssets.sounds, ringablePool(""))
    }
}

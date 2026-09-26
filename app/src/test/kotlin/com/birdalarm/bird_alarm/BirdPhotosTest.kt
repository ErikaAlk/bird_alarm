package com.birdalarm.bird_alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 每日一鸟照片的查找与缓存。重点守住一条：**查询失败不能被当成「这只鸟没有照片」**记进缓存，
 * 否则第一次没网之后照片永远出不来。
 */
class BirdPhotosTest {
    private class MemoryStore : KeyValueStore {
        val map = HashMap<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getLong(key: String) = map[key] as? Long
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    private fun inat(license: String?, user: String?) = JSONObject(
        mapOf("results" to listOf(mapOf(
            "user" to mapOf("name" to user, "login" to "login_name"),
            "photos" to listOf(mapOf("url" to "https://static.inaturalist.org/photos/1/square.jpg", "license_code" to license)),
        ))),
    ).toString()

    private val commons = """{"query":{"pages":{"1":{"imageinfo":[{"mime":"image/jpeg","thumburl":"https://upload.wikimedia.org/thumb/640px-bird.jpg",
        "extmetadata":{"Artist":{"value":"<a href=\"/wiki/User:Someone\">Rejoice Gassah</a>"},"LicenseShortName":{"value":"CC BY 4.0"}}}]}}}}"""

    @Test
    fun `iNaturalist 有 CC 图时用它，并带上作者与许可证署名`() {
        val result = BirdPhotos.lookup("Cuculus micropterus", MemoryStore(), { inat("cc-by-nc", "Ujjal Kishor De") })
        assertEquals(PhotoStatus.Found, result.status)
        // square 缩略图要换成 medium，不然只有 75px
        assertTrue(result.photo!!.url.endsWith("/medium.jpg"))
        assertEquals("iNaturalist · Ujjal Kishor De · CC-BY-NC", result.photo.attribution)
    }

    @Test
    fun `没有许可证的照片（保留所有权利）不能用，退到 Commons`() {
        val result = BirdPhotos.lookup("Cuculus micropterus", MemoryStore(), { url -> if ("inaturalist" in url) inat(null, "someone") else commons })
        assertEquals(PhotoStatus.Found, result.status)
        assertTrue(result.photo!!.url.contains("upload.wikimedia.org"))
        // Artist 字段是 HTML，要扒成纯文本
        assertEquals("Wikimedia Commons · Rejoice Gassah · CC BY 4.0", result.photo.attribution)
    }

    @Test
    fun `两边都正常应答但确实没图就记「没有」，不再重复请求`() {
        var calls = 0
        val store = MemoryStore()
        val fetch = { url: String -> calls++; if ("inaturalist" in url) """{"results":[]}""" else """{"query":{}}""" }
        assertEquals(PhotoStatus.None, BirdPhotos.lookup("Genus nonexistent", store, fetch).status)
        assertEquals(2, calls)
        assertEquals(PhotoStatus.None, BirdPhotos.lookup("Genus nonexistent", store, fetch).status)
        assertEquals("「确实没有」应命中缓存，不该再发请求", 2, calls)
    }

    @Test
    fun `「没有」只记 7 天`() {
        var calls = 0
        val store = MemoryStore()
        val fetch = { url: String -> calls++; if ("inaturalist" in url) """{"results":[]}""" else """{"query":{}}""" }
        BirdPhotos.lookup("Genus nonexistent", store, fetch, now = 0L)
        BirdPhotos.lookup("Genus nonexistent", store, fetch, now = 8L * 24 * 3600 * 1000)
        assertEquals(4, calls)
    }

    @Test
    fun `查询失败不写缓存，网络恢复后能查到`() {
        val store = MemoryStore()
        val failed = BirdPhotos.lookup("Cuculus micropterus", store, { throw IOException("offline") })
        assertEquals(PhotoStatus.Failed, failed.status)
        assertNull(failed.photo)
        assertTrue(store.map.isEmpty())
        val retry = BirdPhotos.lookup("Cuculus micropterus", store, { inat("cc0", "someone") })
        assertEquals(PhotoStatus.Found, retry.status)
        assertEquals("iNaturalist · someone · CC0", retry.photo!!.attribution)
    }

    @Test
    fun `一边失败一边没图也算失败，下次重试`() {
        val store = MemoryStore()
        val result = BirdPhotos.lookup("Genus x", store, { url -> if ("inaturalist" in url) throw IOException() else """{"query":{}}""" })
        assertEquals(PhotoStatus.Failed, result.status)
        assertTrue(store.map.isEmpty())
    }
}

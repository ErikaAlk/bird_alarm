package com.birdalarm.bird_alarm

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** 一张鸟的照片：图片地址 + 署名（作者与许可证）。都是 CC 授权，**署名是许可证要求的**，别为了好看省掉。 */
data class BirdPhoto(val url: String, val attribution: String)

/** **「查失败」和「确实没有这只鸟的照片」必须分开**：早期版本把两者都记成「没有」，第一次没网之后图就再也出不来。 */
enum class PhotoStatus { Found, None, Failed }

data class PhotoResult(val status: PhotoStatus, val photo: BirdPhoto? = null)

/**
 * 按学名找一张鸟的照片，做法参考原作者的 Birdaholic：先问 iNaturalist（research 级观察、按点赞排序），
 * 没有再退到 Wikimedia Commons 的物种分类。两个都是公开接口，不需要 key。与 Birdaholic 不同的两点：
 * 只取 CC 授权的照片（iNaturalist 票数最高的往往是 All rights reserved）；图片自己下到本地再显示（能设超时、能缓存）。
 */
object BirdPhotos {
    private const val PREFIX = "bird_photo_"
    private const val CC_LICENSES = "cc0,cc-by,cc-by-nc,cc-by-sa,cc-by-nc-sa,cc-by-nd,cc-by-nc-nd"
    // 「确实没有」也别记一辈子：物种照片会陆续被人补上
    private const val NEGATIVE_TTL_MILLIS = 7 * 24 * 60 * 60 * 1000L

    /** 查这只鸟的照片。命中缓存直接返回；查询失败**不写缓存**，下次重试。 */
    fun lookup(
        sci: String,
        cache: KeyValueStore,
        fetch: (String) -> String,
        forceRefresh: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): PhotoResult {
        if (sci.isEmpty()) return PhotoResult(PhotoStatus.None)
        val key = PREFIX + sci
        if (forceRefresh) cache.remove(key) else readCache(cache.getString(key), now)?.let { return it }

        val inat = tryParse { parseINaturalist(fetch(iNaturalistUrl(sci))) }
        var photo = inat.photo
        var failed = inat.status == PhotoStatus.Failed
        if (photo == null) {
            val commons = tryParse { parseCommons(fetch(commonsUrl(sci))) }
            photo = commons.photo
            failed = failed || commons.status == PhotoStatus.Failed
        }
        return when {
            photo != null -> {
                cache.putString(key, JSONObject().put("url", photo.url).put("attribution", photo.attribution).put("ts", now).toString())
                PhotoResult(PhotoStatus.Found, photo)
            }
            // 两个来源都正常应答、就是没有可用的图：记一条会过期的「没有」
            !failed -> {
                cache.putString(key, JSONObject().put("none", true).put("ts", now).toString())
                PhotoResult(PhotoStatus.None)
            }
            // 网络失败：什么都不写，下次重试
            else -> PhotoResult(PhotoStatus.Failed)
        }
    }

    private fun readCache(raw: String?, now: Long): PhotoResult? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val data = JSONObject(raw)
            val url = data.optStringOrNull("url")
            when {
                !url.isNullOrEmpty() -> PhotoResult(PhotoStatus.Found, BirdPhoto(url, data.optString("attribution")))
                now - data.optLong("ts") < NEGATIVE_TTL_MILLIS -> PhotoResult(PhotoStatus.None)
                else -> null
            }
        } catch (_: Exception) {
            null // 缓存坏了当没有，重新查一次
        }
    }

    private inline fun tryParse(block: () -> PhotoResult): PhotoResult =
        try { block() } catch (_: Exception) { PhotoResult(PhotoStatus.Failed) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun iNaturalistUrl(sci: String) = "https://api.inaturalist.org/v1/observations?taxon_name=${enc(sci)}" +
        "&photos=true&quality_grade=research&photo_license=${enc(CC_LICENSES)}&order_by=votes&per_page=5"

    // Commons 上每个物种一个分类（Category:Genus_species），里面全是这种鸟的图
    fun commonsUrl(sci: String) = "https://commons.wikimedia.org/w/api.php?action=query&generator=categorymembers" +
        "&gcmtitle=${enc("Category:" + sci.replace(' ', '_'))}&gcmtype=file&gcmlimit=5&prop=imageinfo" +
        "&iiprop=${enc("url|mime|extmetadata")}&iiurlwidth=640&format=json"

    fun parseINaturalist(body: String): PhotoResult {
        val results = JSONObject(body).optJSONArray("results") ?: return PhotoResult(PhotoStatus.None)
        for (i in 0 until results.length()) {
            val observation = results.getJSONObject(i)
            val photo = observation.optJSONArray("photos")?.optJSONObject(0) ?: continue
            val rawUrl = photo.optStringOrNull("url").orEmpty()
            val license = photo.optStringOrNull("license_code").orEmpty().trim()
            // 没有许可证 = 保留所有权利，跳过（photo_license 已经筛过一道，这里再兜一层）
            if (rawUrl.isEmpty() || license.isEmpty()) continue
            val user = observation.optJSONObject("user")
            val author = user?.optStringOrNull("name")?.trim().takeUnless { it.isNullOrEmpty() }
                ?: user?.optStringOrNull("login")?.trim().orEmpty()
            return PhotoResult(
                PhotoStatus.Found,
                // 接口给的是 square（75px）缩略图，换成 medium（约 500px）
                BirdPhoto(rawUrl.replace("square.", "medium."), listOf("iNaturalist", author, license.uppercase()).filter { it.isNotEmpty() }.joinToString(" · ")),
            )
        }
        return PhotoResult(PhotoStatus.None)
    }

    fun parseCommons(body: String): PhotoResult {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return PhotoResult(PhotoStatus.None)
        for (key in pages.keys()) {
            val info = pages.getJSONObject(key).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (!info.optString("mime").startsWith("image/")) continue
            val url = info.optStringOrNull("thumburl") ?: info.optStringOrNull("url") ?: continue
            val meta = info.optJSONObject("extmetadata")
            val author = plainText(meta?.optJSONObject("Artist")?.optString("value").orEmpty())
            val license = plainText(meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty())
            return PhotoResult(PhotoStatus.Found, BirdPhoto(url, listOf("Wikimedia Commons", author, license).filter { it.isNotEmpty() }.joinToString(" · ")))
        }
        return PhotoResult(PhotoStatus.None)
    }

    // Commons 的 Artist 字段是一段 HTML（常带链接），扒成纯文本；太长会把卡片撑爆，截断
    private fun plainText(html: String): String {
        val text = html.replace(Regex("<[^>]*>"), " ")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
            .replace(Regex("\\s+"), " ").trim()
        return if (text.length > 40) text.take(40) + "…" else text
    }

    /**
     * 把图片下到本地（一个物种一个文件）。自己下而不是直接加载网络图：能设超时、失败能明确报出来，
     * 下过的图之后秒出、离线也有。失败返回 null。
     */
    fun imageFile(dir: File, sci: String, photo: BirdPhoto, forceRefresh: Boolean = false): File? = try {
        val file = File(dir, sci.replace(Regex("[^a-zA-Z0-9_.-]+"), "_") + ".img")
        // 手动重试时把上次下坏的文件删掉，否则会一直读到那个坏文件
        if (forceRefresh) file.delete()
        if (!(file.exists() && file.length() > 0)) {
            val tmp = File(dir, file.name + ".part")
            Http.download(photo.url, tmp)
            tmp.renameTo(file)
        }
        file.takeIf { it.exists() && it.length() > 0 }
    } catch (_: Exception) {
        null
    }
}

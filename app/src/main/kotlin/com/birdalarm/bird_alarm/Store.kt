package com.birdalarm.bird_alarm

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import click.erikaalk.coloroskit.components.CoSnackBarState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class ThemeMode(val key: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色"),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    /** 0 = 关闭渐响。默认开 30 秒：突然满音量太吓人。 */
    val fadeInSeconds: Int = 30,
    val xenoApiKey: String = "",
    val lightSync: Boolean = false,
    val lightWebhook: String = "",
)

/** 覆盖安装时从 Flutter 版的 SharedPreferences 搬过来的条目：去掉 "flutter." 前缀，只留基本类型。 */
fun flutterEntries(all: Map<String, *>): Map<String, Any> = all
    .filterKeys { it.startsWith("flutter.") }
    .mapKeys { it.key.removePrefix("flutter.") }
    .mapNotNull { (k, v) -> if (v is String || v is Long || v is Int || v is Boolean || v is Float) k to v else null }
    .toMap()

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun getLong(key: String) = (prefs.all[key] as? Number)?.toLong()
    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

/**
 * 界面的全部状态和业务。进程级单例：下载途中切页、Activity 重建都不中断。
 * 闹钟真正的执行者是原生引擎（AlarmReceiver / AlarmSoundService），这里只负责算「什么时候响」并交给它（[sync]）。
 */
object Store {
    private const val APP_PREFS = "bird_alarm_app"
    private const val KEY_MIGRATED = "migrated_from_flutter"
    private const val KEY_ALARMS = "bird_alarm_alarms"
    private const val KEY_LIBRARY = "bird_alarm_library"
    private const val KEY_THEME = "bird_alarm_theme_mode"
    private const val KEY_FADE = "bird_alarm_fade_in_seconds"
    private const val KEY_API = "bird_alarm_xeno_api_key"
    private const val KEY_LIGHT = "bird_alarm_light_sync"
    private const val KEY_WEBHOOK = "bird_alarm_light_webhook"

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    lateinit var kv: KeyValueStore
        private set
    val scope = MainScope()
    val snackbar = CoSnackBarState()
    private val syncLock = Mutex()

    var alarms by mutableStateOf<List<BirdAlarm>>(emptyList())
        private set
    var library by mutableStateOf(BirdAlarmAssets.starters)
        private set
    var names by mutableStateOf<List<BirdName>>(emptyList())
        private set
    private var nameIndex: Map<String, BirdName> = emptyMap()
    var settings by mutableStateOf(AppSettings())
        private set
    var lightStatus by mutableStateOf("")
        private set
    /** 正在下载的：id → 进度 0~1，null = 进度未知（查询录音、转码中）。 */
    var downloads by mutableStateOf<Map<String, Float?>>(emptyMap())
        private set
    var previewing by mutableStateOf<String?>(null)
        private set
    /** 本轮正在响的鸟鸣（原生 prefs 的 ringing_asset）。响铃页只看它：原生停了，页面就收起。 */
    var ringingAsset by mutableStateOf<String?>(null)
        private set
    /** 课表或节假日数据变了就加一，界面据此重算「距离下次响铃」。 */
    var calendarVersion by mutableIntStateOf(0)
        private set
    var xenoQuery by mutableStateOf("cnt:China q:A")
    var xenoResults by mutableStateOf<List<BirdSound>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    private var initialized = false
    private var lastFadeIn = 30
    private var lastLightPush: String? = null
    // 响铃页上点了「贪睡」：原生刚排好贪睡，这一轮结束后别重排（没有启用的闹钟时重排会把贪睡一起取消）
    private var snoozedFromOverlay = false
    private var preview: MediaPlayer? = null

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        kv = PrefsStore(prefs)
        migrateFromFlutter()
        settings = readSettings()
        if (settings.fadeInSeconds > 0) lastFadeIn = settings.fadeInSeconds
        alarms = prefs.getString(KEY_ALARMS, null)?.let { runCatching { BirdAlarm.listFromJson(it) }.getOrNull() }
            ?: listOf(BirdAlarm("${System.currentTimeMillis() * 1000}", LocalTime.of(7, 30), setOf(1, 2, 3, 4, 5), RepeatRule.Weekdays, true, "工作日鸟鸣"))
        library = BirdAlarmAssets.starters + (prefs.getString(KEY_LIBRARY, null)?.let { runCatching { BirdSound.listFromJson(it) }.getOrNull() }.orEmpty())
        ringingAsset = AlarmControl.ringingAsset(app)
        AlarmControl.saveFadeIn(app, settings.fadeInSeconds)
        scope.launch {
            withContext(Dispatchers.IO) { ChinaHolidayData.loadCache(kv) }
            sync()
            names = withContext(Dispatchers.IO) { loadNames() }
            nameIndex = names.associateBy { it.sci }
            // 后台拉最新的节假日数据；有更新就按新数据重排
            if (withContext(Dispatchers.IO) { ChinaHolidayData.refresh(kv, { Http.get(it, 8_000) }) }) {
                calendarVersion++
                sync()
            }
        }
    }

    /** Flutter 版的数据（闹钟、音库、设置、照片和节假日缓存）只搬一次；原文件留着不删。 */
    private fun migrateFromFlutter() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val editor = prefs.edit()
        flutterEntries(app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).all).forEach { (k, v) ->
            when (v) {
                is String -> editor.putString(k, v)
                is Long -> editor.putLong(k, v)
                is Int -> editor.putLong(k, v.toLong())
                is Boolean -> editor.putBoolean(k, v)
                is Float -> editor.putFloat(k, v)
            }
        }
        editor.putBoolean(KEY_MIGRATED, true).commit()
    }

    private fun readSettings() = AppSettings(
        themeMode = ThemeMode.entries.firstOrNull { it.key == prefs.getString(KEY_THEME, null) } ?: ThemeMode.System,
        fadeInSeconds = kv.getLong(KEY_FADE)?.toInt() ?: 30,
        xenoApiKey = prefs.getString(KEY_API, null).orEmpty(),
        lightSync = prefs.getBoolean(KEY_LIGHT, false),
        lightWebhook = prefs.getString(KEY_WEBHOOK, null).orEmpty(),
    )

    private fun loadNames(): List<BirdName> = try {
        val array = JSONArray(app.assets.open("data/avilist_ioc_names.json").use { it.readBytes().toString(Charsets.UTF_8) })
        (0 until array.length()).map { i ->
            array.getJSONObject(i).let { BirdName(it.optString("sci"), it.optString("display"), it.optString("cn"), it.optString("en")) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun toast(text: String) {
        scope.launch { snackbar.show(text) }
    }

    // ─────────────────────────── 闹钟 ───────────────────────────

    fun saveAlarm(alarm: BirdAlarm) {
        alarms = if (alarms.any { it.id == alarm.id }) alarms.map { if (it.id == alarm.id) alarm else it } else alarms + alarm
        persistAlarms()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        alarms = alarms.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persistAlarms()
    }

    fun deleteAlarm(id: String) {
        alarms = alarms.filterNot { it.id == id }
        persistAlarms()
    }

    private fun persistAlarms() {
        prefs.edit().putString(KEY_ALARMS, BirdAlarm.listToJson(alarms)).apply()
        sync()
    }

    /** 下一次响铃（界面显示用）。 */
    fun nextRing(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? = nextEnabledOccurrence(alarms, now, skipped())?.first

    private fun skipped(): LocalDateTime? = AlarmControl.skippedTrigger(app).takeIf { it != 0L }?.let(::millisToLocal)

    /** 把接下来几次响铃交给原生引擎，并推给 HA 光闹钟。 */
    fun sync() {
        scope.launch { syncNow() }
    }

    private suspend fun syncNow() = syncLock.withLock {
        // 响铃中不重排：重排会补发「已守护」通知，没有启用的闹钟时还会把正在进行的这一轮撤掉。响完再排
        if (ringingAsset != null) return@withLock
        refreshSchedule()
        val upcoming = upcomingOccurrences(alarms, LocalDateTime.now(), skipped())
        if (upcoming.isEmpty()) {
            AlarmControl.cancel(app)
        } else {
            // 能离线播放的鸟鸣（内置 + 下载 / 导入到本机的）都进响铃的随机抽取池
            val pool = library.mapNotNull { s -> s.nativeRef?.let { it to s.cnName } }
            AlarmControl.schedule(app, upcoming.first().first.epochMillis(), upcoming.map { it.first.epochMillis() }, pool)
        }
        pushLight(upcoming)
    }

    /** 从后台回到前台：课表可能在 Cadence 里改过（也可能刚授了读课表权限），变了就重排；没变也补推一次光闹钟。 */
    fun refreshOnResume() {
        if (!initialized || ringingAsset != null) return
        scope.launch {
            if (refreshSchedule()) syncNow() else pushLight(upcomingOccurrences(alarms, LocalDateTime.now(), skipped()))
        }
    }

    /**
     * 从 Cadence 读今天起 14 天的课（它一次最多给 8 天，分两段读）。没装 Cadence、没授权时当「不知道」，
     * 按 [scheduleDayOf] 的兜底规则走。有变化返回 true。
     */
    private suspend fun refreshSchedule(): Boolean {
        if (alarms.none { it.enabled && it.rule == RepeatRule.ClassSchedule }) return false
        val today = LocalDate.now()
        val starts = withContext(Dispatchers.IO) { readCadence(today) }
        return CadenceSchedule.update(today, 14, starts).also { if (it) calendarVersion++ }
    }

    // 接口约定见 Cadence 仓库的 docs/对外接口.md
    private fun readCadence(first: LocalDate): List<Long>? {
        if (!Permissions.hasScheduleAccess(app)) return null
        val out = ArrayList<Long>()
        for (offset in listOf(0L, 7L)) {
            val from = first.plusDays(offset).atStartOfDay().epochMillis()
            val to = first.plusDays(offset + 7).atStartOfDay().epochMillis()
            val uri = Uri.parse("content://click.erikaalk.cadence.schedule/classes?from=$from&to=$to")
            try {
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val column = c.getColumnIndexOrThrow("start_ms")
                    while (c.moveToNext()) out += c.getLong(column)
                } ?: return null
            } catch (_: Exception) {
                return null
            }
        }
        return out
    }

    /**
     * 把接下来几次响铃对应的「光闹钟开始亮」时刻推给 HA（webhook）。HA 一直在线，由它挑最近一次写进灯里，
     * 所以手机几天不开 App 也照样亮。关掉联动时推 linked=false。内容没变就不重复推。
     */
    private suspend fun pushLight(upcoming: List<Pair<LocalDateTime, BirdAlarm>>) {
        val url = settings.lightWebhook
        val valid = runCatching { URI(url) }.getOrNull()?.let { it.scheme in listOf("http", "https") && !it.host.isNullOrEmpty() } == true
        if (!valid) {
            if (settings.lightSync) lightStatus = "未填写 webhook 地址"
            return
        }
        val linked = settings.lightSync
        val times = if (linked) lightStartTimes(upcoming) else emptyList()
        val body = JSONObject().put("linked", linked).put("times", JSONArray(times.map { it.epochMillis() / 1000 })).toString()
        if (body == lastLightPush) return
        val hhmm = LocalTime.now().hhmm()
        lightStatus = try {
            val code = withContext(Dispatchers.IO) { Http.postJson(url, body) }
            if (code != 200) {
                "$hhmm 推送失败，HA 返回 $code"
            } else {
                lastLightPush = body
                val next = times.firstOrNull { it.isAfter(LocalDateTime.now()) }
                when {
                    !linked -> "$hhmm 已通知 HA 停止联动"
                    next == null -> "$hhmm 已推送，暂无要亮灯的闹钟"
                    else -> "$hhmm 已推送，下次亮灯 ${shortDateTime(next)}"
                }
            }
        } catch (_: Exception) {
            "$hhmm 推送失败，连不上 HA，下次打开应用时重试"
        }
    }

    // ─────────────────────────── 响铃 ───────────────────────────

    /** MainActivity 监听原生 prefs 的 ringing_asset 后调这里。一轮响铃结束（关闭 / 通知里关掉）后重排。 */
    fun onRinging(asset: String?) {
        val wasRinging = ringingAsset != null
        ringingAsset = asset
        if (asset != null) {
            stopPreview()
            return
        }
        if (!wasRinging) return
        if (snoozedFromOverlay) snoozedFromOverlay = false else sync()
    }

    fun dismissRinging() = AlarmControl.stopSound(app)

    fun snoozeRinging() {
        snoozedFromOverlay = true
        AlarmControl.snooze(app)
    }

    /** 正在叫的那只鸟：原生记的是路径，按路径对回音库；Flutter 版留下的旧路径按文件名对。 */
    fun ringingSound(asset: String): BirdSound {
        val path = asset.removePrefix(BirdAlarmAssets.LEGACY_PREFIX)
        return library.firstOrNull { it.nativeRef == path }
            ?: BirdSound("ringing", BirdAlarmAssets.cnNameFor(app, asset), "", "", "")
    }

    fun testAlarm() {
        AlarmControl.test(app)
        toast("已安排 10 秒后响铃")
    }

    // ─────────────────────────── 设置 ───────────────────────────

    fun setThemeMode(mode: ThemeMode) {
        settings = settings.copy(themeMode = mode)
        prefs.edit().putString(KEY_THEME, mode.key).apply()
    }

    fun setFadeIn(seconds: Int) {
        if (seconds > 0) lastFadeIn = seconds
        settings = settings.copy(fadeInSeconds = seconds)
        prefs.edit().putLong(KEY_FADE, seconds.toLong()).apply()
        AlarmControl.saveFadeIn(app, seconds)
    }

    /** 重新打开渐响时还是上次选的时长。 */
    fun setFadeInEnabled(enabled: Boolean) = setFadeIn(if (enabled) lastFadeIn else 0)

    fun setApiKey(key: String) {
        settings = settings.copy(xenoApiKey = key.trim())
        prefs.edit().putString(KEY_API, key.trim()).apply()
    }

    fun setLightSync(enabled: Boolean) {
        settings = settings.copy(lightSync = enabled)
        prefs.edit().putBoolean(KEY_LIGHT, enabled).apply()
        if (!enabled) lightStatus = ""
        sync()
    }

    fun setWebhook(url: String) {
        settings = settings.copy(lightWebhook = url.trim())
        prefs.edit().putString(KEY_WEBHOOK, url.trim()).apply()
        sync()
    }

    // ─────────────────────────── 鸟鸣库 ───────────────────────────

    fun filterNames(query: String): List<BirdName> {
        val q = query.trim()
        // 没输入就什么都不列：名录有一万多条，按顺序列前 30 条永远是那几只鸵鸟，看着像界面卡死了
        if (q.isEmpty()) return emptyList()
        return names.asSequence().filter { it.matches(q) }.take(30).toList()
    }

    fun soundFor(bird: BirdName): BirdSound? = library.firstOrNull { it.sciName == bird.sci }

    private fun persistLibrary() {
        prefs.edit().putString(KEY_LIBRARY, BirdSound.listToJson(library.filterNot { it.builtIn })).apply()
        sync()
    }

    fun addSound(sound: BirdSound) {
        if (library.any { it.id == sound.id }) return
        library = library + sound
        persistLibrary()
    }

    /** 导入本地音频：拷一份进 App 私有目录（选择器给的 content:// 过后可能就读不到了）。 */
    fun importAudio(uri: Uri) = scope.launch {
        try {
            val sound = withContext(Dispatchers.IO) {
                val display = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "本地音频"
                val id = "local-${System.currentTimeMillis()}"
                val ext = display.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }
                val file = File(soundsDir(), if (ext != null) "$id.$ext" else id)
                app.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                val name = display.substringBeforeLast('.')
                BirdSound(id, name, name, "", "用户上传", localPath = file.absolutePath)
            }
            addSound(sound)
            toast("已导入“${sound.cnName}”")
        } catch (e: Exception) {
            toast("导入失败：${e.userMessage()}")
        }
    }

    private fun soundsDir() = File(app.filesDir, "bird_sounds").apply { mkdirs() }

    private fun xenoUrl(query: String, perPage: Int): String {
        val key = settings.xenoApiKey.takeIf { it.isNotEmpty() }?.let { "&key=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        return "https://xeno-canto.org/api/3/recordings?query=${URLEncoder.encode(query, "UTF-8")}&per_page=$perPage$key"
    }

    /** xeno-canto 的 v3 接口要 Key：401 / 403 时直接说去哪儿补救。 */
    private fun Throwable.xenoMessage(): String =
        if (this is HttpStatusException && (code == 401 || code == 403)) "xeno-canto 拒绝了请求，请在“设置”里检查 API Key" else userMessage()

    private fun parseXeno(body: String): List<BirdSound> {
        val recordings = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
        return (0 until recordings.length()).mapNotNull { i ->
            val item = recordings.getJSONObject(i)
            val file = item.optStringOrNull("file")?.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@mapNotNull null
            val sci = "${item.optString("gen")} ${item.optString("sp")}".trim()
            val en = item.optStringOrNull("en") ?: ""
            BirdSound(
                id = "xc-${item.optString("id")}",
                cnName = nameIndex[sci]?.display?.takeIf { it.isNotEmpty() } ?: en.ifEmpty { "xeno-canto 鸟鸣" },
                enName = en, sciName = sci,
                source = "xeno-canto #${item.optString("id")} · ${item.optString("cnt")} · ${item.optString("q")}",
                url = file,
            )
        }
    }

    fun searchXeno() {
        val query = xenoQuery.trim()
        if (query.isEmpty()) return
        scope.launch {
            searching = true
            try {
                xenoResults = withContext(Dispatchers.IO) { parseXeno(Http.get(xenoUrl(query, 12))) }
                if (xenoResults.isEmpty()) toast("无搜索结果")
            } catch (e: Exception) {
                toast("查询失败：${e.xenoMessage()}")
            } finally {
                searching = false
            }
        }
    }

    /** 按鸟种下载：先在 xeno-canto 找一条质量 C 以上的录音，再下载。进度挂在 species-学名 上，名录和每日一鸟都看得到。 */
    fun downloadSpecies(bird: BirdName) {
        val parts = bird.sci.split(Regex("\\s+"))
        if (parts.size < 2) return
        val aliasId = "species-${bird.sci}"
        if (aliasId in downloads) return
        downloads = downloads + (aliasId to null)
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { parseXeno(Http.get(xenoUrl("gen:${parts[0]} sp:${parts[1]} q:\">C\"", 20))) }
                val recording = found.firstOrNull() ?: throw IllegalStateException("xeno-canto 上没有这种鸟的录音")
                downloadNow(recording.copy(cnName = bird.display, enName = bird.en, sciName = bird.sci), aliasId)
            } catch (e: Exception) {
                toast("下载失败：${e.xenoMessage()}")
            } finally {
                downloads = downloads - aliasId
            }
        }
    }

    fun download(sound: BirdSound) {
        if (sound.id in downloads) return
        scope.launch { downloadNow(sound, null) }
    }

    /**
     * 流式下载 → 转码放大 → 加进音库（进随机抽取池）。进度同时驱动 App 内的进度环和通知栏的 Live Update，
     * 通知每变 2% 或每 400ms 才更新一次，否则一次下载会刷成百上千条。
     */
    private suspend fun downloadNow(sound: BirdSound, aliasId: String?) {
        val url = sound.url ?: return
        val ids = setOfNotNull(sound.id, aliasId)
        fun progress(value: Float?) {
            downloads = downloads + ids.associateWith { value }
        }
        progress(0f)
        val title = "正在下载“${sound.cnName}”"
        DownloadNotifier.update(app, title, "0%", 0)
        try {
            val raw = File(soundsDir(), safeFileName(sound.id) + ".mp3")
            var lastPercent = -1
            var lastAt = 0L
            withContext(Dispatchers.IO) {
                Http.download(url, raw) { done, total ->
                    if (total <= 0) return@download
                    val percent = (done * 100 / total).toInt()
                    val now = System.currentTimeMillis()
                    if (percent - lastPercent >= 2 || now - lastAt >= 400) {
                        lastPercent = percent
                        lastAt = now
                        scope.launch { progress(percent / 100f) }
                        DownloadNotifier.update(app, title, "$percent%", percent)
                    }
                }
            }
            // 转码没有可读进度：进度环转圈，通知也如实写在做什么
            progress(null)
            DownloadNotifier.update(app, title, "正在转码为闹钟音频…", -1)
            val path = withContext(Dispatchers.IO) { transcodeOrKeep(raw, sound.id) }
            val downloaded = sound.copy(id = "${sound.id}-local", source = "${sound.source} · 已下载", url = null, localPath = path)
            // 音库里原来那条只能在线播放的，下完就被本机这条替换掉
            library = library.filterNot { it.id == downloaded.id || it.id == sound.id } + downloaded
            persistLibrary()
            DownloadNotifier.finish(app, "鸟鸣已下载", "“${sound.cnName}”已加入随机抽取池")
            toast("已下载“${sound.cnName}”")
        } catch (e: Exception) {
            DownloadNotifier.finish(app, "鸟鸣下载失败", "${sound.cnName}：${e.xenoMessage()}")
            toast("下载失败：${e.xenoMessage()}")
        } finally {
            downloads = downloads - ids
        }
    }

    // 转成 m4a 并放大 2.5 倍；这台机器转不了就留原文件
    private fun transcodeOrKeep(source: File, id: String): String = try {
        val target = File(source.parentFile, safeFileName(id) + ".m4a")
        AudioTranscoder.transcode(source.path, target.path, 2.5f)
        if (target.length() > 0) {
            source.delete()
            target.path
        } else {
            source.path
        }
    } catch (_: Exception) {
        source.path
    }

    private fun safeFileName(value: String) = value.replace(Regex("[^a-zA-Z0-9_.-]+"), "_")

    // ─────────────────────────── 试听 ───────────────────────────

    fun togglePreview(sound: BirdSound) {
        if (ringingAsset != null) return
        if (previewing == sound.id) {
            stopPreview()
            return
        }
        stopPreview()
        val player = MediaPlayer()
        try {
            // 和响铃走同一个音量通道，试听多大声，闹钟就多大声
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            player.isLooping = true
            when {
                sound.localPath != null -> player.setDataSource(sound.localPath)
                sound.assetPath != null -> app.assets.openFd(sound.assetPath).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                !sound.url.isNullOrEmpty() -> player.setDataSource(sound.url)
                else -> throw IllegalStateException()
            }
            player.setOnPreparedListener { if (preview === it) it.start() }
            player.setOnErrorListener { mp, _, _ ->
                if (preview === mp) {
                    stopPreview()
                    toast("无法播放这条鸟鸣")
                }
                true
            }
            preview = player
            previewing = sound.id
            player.prepareAsync()
        } catch (_: Exception) {
            player.release()
            toast("无法播放这条鸟鸣")
        }
    }

    fun stopPreview() {
        preview?.release()
        preview = null
        previewing = null
    }

    // ─────────────────────────── 每日一鸟的照片 ───────────────────────────

    /** 查照片 + 把图下到本地，两步都在 IO 线程。查到了图但下不下来也算失败（可重试）。 */
    fun loadPhoto(sci: String, forceRefresh: Boolean): Pair<PhotoResult, File?> {
        val result = BirdPhotos.lookup(sci, kv, { Http.get(it) }, forceRefresh)
        val file = result.photo?.let { BirdPhotos.imageFile(File(app.filesDir, "bird_photos"), sci, it, forceRefresh) }
        return if (result.status == PhotoStatus.Found && file == null) PhotoResult(PhotoStatus.Failed, result.photo) to null else result to file
    }
}

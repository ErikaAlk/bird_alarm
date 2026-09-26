package com.birdalarm.bird_alarm

import android.content.Context

object BirdAlarmAssets {
    /**
     * Flutter 版把 asset 打在 APK 的 flutter_assets/assets/ 下，原生 prefs 里存的是带这个前缀的路径。
     * 覆盖安装后、第一次打开 App 重新下发音库之前，响铃可能还拿着旧路径，播放和取鸟名时都先去掉它。
     */
    const val LEGACY_PREFIX = "flutter_assets/assets/"

    /** 内置 10 种鸟鸣，都来自 xeno-canto。 */
    val starters = listOf(
        starter("cuculus_micropterus", "四声杜鹃", "Indian Cuckoo", "Cuculus micropterus", "1101770"),
        starter("cuculus_canorus", "大杜鹃", "Common Cuckoo", "Cuculus canorus", "1102893"),
        starter("spilornis_cheela", "蛇雕", "Crested Serpent Eagle", "Spilornis cheela", "1094944"),
        starter("francolinus_pintadeanus", "中华鹧鸪", "Chinese Francolin", "Francolinus pintadeanus", "1034127"),
        starter("horornis_fortipes", "强脚树莺", "Brown-flanked Bush Warbler", "Horornis fortipes", "1088414"),
        starter("horornis_canturians", "远东树莺", "Manchurian Bush Warbler", "Horornis canturians", "1041519"),
        starter("parus_cinereus", "大山雀", "Cinereous Tit", "Parus cinereus", "1093376"),
        starter("dacelo_novaeguineae", "笑翠鸟", "Laughing Kookaburra", "Dacelo novaeguineae", "1086676"),
        starter("psophodes_olivaceus", "绿啸冠鸫", "Eastern Whipbird", "Psophodes olivaceus", "1088985"),
        starter("eudynamys_scolopaceus", "噪鹃", "Asian Koel", "Eudynamys scolopaceus", "1101779"),
    )

    private fun starter(file: String, cn: String, en: String, sci: String, xc: String) = BirdSound(
        id = "starter-" + file.replace('_', '-'),
        cnName = cn, enName = en, sciName = sci,
        source = "内置鸟鸣 · xeno-canto #$xc",
        assetPath = "sounds/$file.m4a",
    )

    val sounds: List<String> get() = starters.mapNotNull { it.assetPath }

    /** 响铃通知里的鸟名：先查 App 下发的「路径 → 中文名」（下载的鸟鸣在这里），再按文件名查内置表。 */
    fun cnNameFor(context: Context, assetPath: String?): String {
        if (assetPath.isNullOrEmpty()) return "鸟鸣"
        try {
            val raw = nativePrefs(context).getString("sound_names", null)
            if (!raw.isNullOrEmpty()) {
                val name = org.json.JSONObject(raw).optString(assetPath)
                if (name.isNotEmpty()) return name
            }
        } catch (_: Exception) {
        }
        val fileName = assetPath.substringAfterLast('/')
        return starters.firstOrNull { it.assetPath?.substringAfterLast('/') == fileName }?.cnName ?: "鸟鸣"
    }
}

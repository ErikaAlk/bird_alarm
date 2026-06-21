package com.birdalarm.bird_alarm

object BirdAlarmAssets {
    val sounds = listOf(
        "flutter_assets/assets/sounds/cuculus_micropterus.m4a",
        "flutter_assets/assets/sounds/cuculus_canorus.m4a",
        "flutter_assets/assets/sounds/spilornis_cheela.m4a",
        "flutter_assets/assets/sounds/francolinus_pintadeanus.m4a",
        "flutter_assets/assets/sounds/horornis_fortipes.m4a",
        "flutter_assets/assets/sounds/horornis_canturians.m4a",
        "flutter_assets/assets/sounds/parus_cinereus.m4a",
        "flutter_assets/assets/sounds/dacelo_novaeguineae.m4a",
        "flutter_assets/assets/sounds/psophodes_olivaceus.m4a",
        "flutter_assets/assets/sounds/eudynamys_scolopaceus.m4a"
    )

    // asset 路径 → 中文鸟名，与 Flutter 侧 _starterLibrary 保持一致。
    // 锁屏响铃实际只会播放这 10 个内置鸟鸣，所以这份映射足以告诉用户"正在叫的是什么鸟"。
    private val cnNames = mapOf(
        "cuculus_micropterus.m4a" to "四声杜鹃",
        "cuculus_canorus.m4a" to "大杜鹃",
        "spilornis_cheela.m4a" to "蛇雕",
        "francolinus_pintadeanus.m4a" to "中华鹧鸪",
        "horornis_fortipes.m4a" to "强脚树莺",
        "horornis_canturians.m4a" to "远东树莺",
        "parus_cinereus.m4a" to "大山雀",
        "dacelo_novaeguineae.m4a" to "笑翠鸟",
        "psophodes_olivaceus.m4a" to "绿啸冠鸫",
        "eudynamys_scolopaceus.m4a" to "噪鹃"
    )

    fun cnNameFor(assetPath: String?): String {
        if (assetPath.isNullOrEmpty()) return "鸟鸣"
        val fileName = assetPath.substringAfterLast('/')
        return cnNames[fileName] ?: "鸟鸣"
    }
}

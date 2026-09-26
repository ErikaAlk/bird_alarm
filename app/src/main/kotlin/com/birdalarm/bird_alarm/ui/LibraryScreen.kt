package com.birdalarm.bird_alarm.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import click.erikaalk.coloroskit.components.CoBarAction
import click.erikaalk.coloroskit.components.CoButton
import click.erikaalk.coloroskit.components.CoButtonSize
import click.erikaalk.coloroskit.components.CoCard
import click.erikaalk.coloroskit.components.CoCardPosition
import click.erikaalk.coloroskit.components.CoCardRow
import click.erikaalk.coloroskit.components.CoCategoryTitle
import click.erikaalk.coloroskit.components.CoCircleProgress
import click.erikaalk.coloroskit.components.CoEmptyState
import click.erikaalk.coloroskit.components.CoListItem
import click.erikaalk.coloroskit.components.CoLoading
import click.erikaalk.coloroskit.components.CoTextField
import click.erikaalk.coloroskit.components.CoTrailing
import click.erikaalk.coloroskit.tokens.CoTokens
import click.erikaalk.coloroskit.tokens.LocalCoDark
import com.birdalarm.bird_alarm.BirdName
import com.birdalarm.bird_alarm.BirdSound
import com.birdalarm.bird_alarm.PhotoResult
import com.birdalarm.bird_alarm.PhotoStatus
import com.birdalarm.bird_alarm.Store
import com.birdalarm.bird_alarm.pickDailyBird
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val L = CoTokens.List

@Composable
fun LibraryScreen(bottomExtra: Dp, onOpenXeno: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, Store.names) { Store.filterNames(query) }
    val today = rememberMinuteClock().value.toLocalDate()
    val daily = remember(today, Store.names) { pickDailyBird(Store.names, today) }
    // 下载 / 导入的排在内置前面，下完一定能在音库里看到
    val library = Store.library.sortedBy { it.builtIn }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { Store.importAudio(it) } }
    BirdPage(
        "鸟鸣",
        bottomExtra = bottomExtra,
        actions = listOf(CoBarAction("导入本地音频", { importer.launch(arrayOf("audio/*")) }, icon = { GlyphIcon(Glyph.Import, it) })),
    ) {
        // 搜索框放最上面。不输入就什么都不列（见 Store.filterNames）
        item(key = "search") {
            CoCard(contentPadding = PaddingValues(horizontal = L.paddingH)) {
                CoTextField(query, { query = it }, label = "搜索鸟种", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            }
        }
        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                item(key = "noResult") { CoEmptyState("无搜索结果") }
            } else {
                item(key = "resultsGap") { Spacer(Modifier.height(L.groupTop)) }
                itemsIndexed(results, key = { _, b -> "sp:" + b.sci }) { i, bird -> SpeciesRow(bird, positionOf(i, results.size)) }
            }
        }
        if (daily != null) {
            item(key = "dailyTitle") { CoCategoryTitle("每日一鸟") }
            item(key = "daily:" + daily.sci) { DailyBirdCard(daily) }
        }
        groupGap("xenoGap")
        item(key = "xeno") { CoListItem("xeno-canto 高级查询", CoCardPosition.Full, trailing = CoTrailing.Arrow, onClick = onOpenXeno) }
        item(key = "libraryTitle") { CoCategoryTitle("音库") }
        itemsIndexed(library, key = { _, s -> "lib:" + s.id }) { i, sound -> SoundRow(sound, positionOf(i, library.size)) }
        item(key = "libraryFooter") { Footer("${library.size} 条鸟鸣，响铃时从能离线播放的里面随机抽一条") }
    }
}

/** 下载中显示进度环（进度未知时转圈），否则是行尾的状态图标。 */
@Composable
private fun DownloadState(progress: Float?) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (progress == null) CoLoading() else CoCircleProgress(progress)
    }
}

@Composable
private fun PlayState(playing: Boolean) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        GlyphIcon(if (playing) Glyph.Pause else Glyph.Play, CoTokens.Color.label1.current)
    }
}

/** 名录搜索结果：音库里已经有就点按试听，没有就点按下载一条回来。 */
@Composable
private fun SpeciesRow(bird: BirdName, position: CoCardPosition) {
    val sound = Store.soundFor(bird)
    val key = "species-${bird.sci}"
    val downloading = key in Store.downloads
    CoCardRow(position, onClick = { if (sound != null) Store.togglePreview(sound) else Store.downloadSpecies(bird) }, enabled = !downloading) {
        Column(Modifier.weight(1f)) {
            RowTitle(bird.display)
            RowText(metaText(listOf(bird.en, bird.sci)))
        }
        when {
            downloading -> DownloadState(Store.downloads[key])
            sound != null -> PlayState(Store.previewing == sound.id)
            else -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Download, CoTokens.Color.label1.current) }
        }
    }
}

/** 音库 / 搜索结果里的一条鸟鸣：点按试听；只能在线播放的可以下载到本机（下载后才进闹钟的抽取池）。 */
@Composable
fun SoundRow(sound: BirdSound, position: CoCardPosition, onAdd: ((BirdSound) -> Unit)? = null) {
    val downloading = sound.id in Store.downloads
    CoCardRow(position, onClick = { Store.togglePreview(sound) }, enabled = sound.playable) {
        Column(Modifier.weight(1f)) {
            RowTitle(sound.cnName)
            RowText(metaText(listOf(sound.enName, sound.sciName)), maxLines = 1)
            RowText(metaText(listOf(sound.source)), maxLines = 1)
        }
        if (onAdd != null) IconButton(Glyph.Plus, "加入音库", { onAdd(sound) })
        when {
            downloading -> DownloadState(Store.downloads[sound.id])
            sound.url != null && sound.localPath == null -> IconButton(Glyph.Download, "下载到本机", { Store.download(sound) })
        }
        PlayState(Store.previewing == sound.id)
    }
}

private sealed interface PhotoState {
    data object Loading : PhotoState
    data class Shown(val image: ImageBitmap, val attribution: String) : PhotoState
    data class Missing(val failed: Boolean) : PhotoState
}

/**
 * 「每日一鸟」：当天固定的一只鸟 + 一张 CC 授权的实拍（卡片底部按许可证要求标出作者）。已经在音库就试听，没有就下载。
 * 照片取不到时换成卡通鸟占位，尺寸不变；**失败要说出来并能点按重试**，只画占位的话用户会以为还在加载。
 */
@Composable
private fun DailyBirdCard(bird: BirdName) {
    var attempt by remember(bird.sci) { mutableIntStateOf(0) }
    val photo by produceState<PhotoState>(PhotoState.Loading, bird.sci, attempt) {
        value = PhotoState.Loading
        value = withContext(Dispatchers.IO) {
            val (result: PhotoResult, file) = Store.loadPhoto(bird.sci, forceRefresh = attempt > 0)
            val bitmap = file?.let { runCatching { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }.getOrNull() }
            if (bitmap != null && result.photo != null) PhotoState.Shown(bitmap, result.photo.attribution)
            else PhotoState.Missing(failed = result.status != PhotoStatus.None)
        }
    }
    val sound = Store.soundFor(bird)
    val speciesKey = "species-${bird.sci}"
    val white = Color.White
    CoCard(contentPadding = PaddingValues(0.dp)) {
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            when (val p = photo) {
                is PhotoState.Shown -> Image(p.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                PhotoState.Loading -> Box(Modifier.fillMaxSize().background(CoTokens.Color.fill4.current), contentAlignment = Alignment.Center) { CoLoading() }
                is PhotoState.Missing -> Box(
                    Modifier.fillMaxSize().background(CoTokens.Color.fill4.current)
                        .clickable(remember { MutableInteractionSource() }, indication = null, enabled = p.failed, role = Role.Button) { attempt++ },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CartoonBird(LocalCoDark.current, Modifier.size(96.dp))
                        BasicText(
                            if (p.failed) "照片加载失败，点按重试" else "暂无照片",
                            style = CoTokens.Type.bodyXS.toTextStyle().copy(color = CoTokens.Color.label2.current),
                        )
                    }
                }
            }
            // 下半部压暗，压在上面的鸟名才读得清（照片亮暗不可控）
            Box(Modifier.fillMaxWidth().height(96.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = L.paddingH, vertical = 12.dp)) {
                BasicText(bird.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = CoTokens.Type.headlineL.dp().copy(color = white))
                BasicText("${bird.en} · ${bird.sci}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = CoTokens.Type.bodyXS.dp().copy(color = white.copy(alpha = 0.88f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(L.paddingH), verticalAlignment = Alignment.CenterVertically) {
            when {
                sound != null && sound.playable ->
                    CoButton(if (Store.previewing == sound.id) "暂停" else "试听", { Store.togglePreview(sound) }, size = CoButtonSize.Small)
                speciesKey in Store.downloads -> DownloadState(Store.downloads[speciesKey])
                else -> CoButton("下载", { Store.downloadSpecies(bird) }, size = CoButtonSize.Small)
            }
            Spacer(Modifier.width(L.paddingH))
            // 照片署名：CC 许可证要求标出作者与协议，别省
            BasicText(
                (photo as? PhotoState.Shown)?.attribution.orEmpty(),
                Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = CoTokens.Type.caption.toTextStyle().copy(color = CoTokens.Color.label2.current, textAlign = TextAlign.End),
            )
        }
    }
}

/** xeno-canto 高级查询：按 xeno-canto 的查询语法搜录音，试听、加进音库或下载到本机。 */
@Composable
fun XenoScreen(onBack: () -> Unit) {
    val library = Store.library
    BirdPage("xeno-canto", onBack = onBack) {
        item(key = "query") {
            CoCard(contentPadding = PaddingValues(horizontal = L.paddingH)) {
                CoTextField(
                    Store.xenoQuery, { Store.xenoQuery = it }, label = "查询条件",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { Store.searchXeno() }),
                )
            }
        }
        item(key = "hint") { Footer("例如 cnt:China q:A，或 gen:Turdus sp:merula") }
        item(key = "search") {
            CoButton(
                if (Store.searching) "正在搜索…" else "搜索", { Store.searchXeno() },
                Modifier.fillMaxWidth().padding(horizontal = L.cardMarginH, vertical = L.groupTop),
                enabled = !Store.searching && Store.xenoQuery.isNotBlank(),
            )
        }
        val results = Store.xenoResults
        if (results.isNotEmpty()) {
            item(key = "resultsTitle") { CoCategoryTitle("搜索结果") }
            itemsIndexed(results, key = { _, s -> "xc:" + s.id }) { i, sound ->
                SoundRow(sound, positionOf(i, results.size), onAdd = if (library.any { it.id == sound.id }) null else Store::addSound)
            }
            item(key = "resultsFooter") { Footer("加进音库的录音可以在线试听，下载到本机后才会在闹钟里响") }
        }
    }
}

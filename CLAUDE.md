# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`bird_alarm`（鸟瘾闹钟）是一个 Flutter 闹钟 App，用随机鸟鸣叫醒，可从 xeno-canto 下载鸟声。
**只针对 Android**——iOS 不维护，改动时无需考虑 iOS。这是 [ErikaAlk](https://github.com/ErikaAlk/bird_alarm)
基于个人使用习惯对原作者项目的 fork，所有改动仅为自用。

## 环境与命令

- Flutter 在 `C:\dev\flutter\bin`（**不一定在 PATH 上**；脚本里用全路径或 `$env:Path += ";C:\dev\flutter\bin"`）。Dart 3.12 / Flutter 3.44。
- `flutter analyze` —— **Dart 改动的主要自验手段**（不需要 Gradle，秒级返回；改完 Dart 必跑）。
- `flutter test` —— `test/widget_test.dart`（首页渲染冒烟 + 滑动切页 + 星期格尺寸/双击 + 设置页）、`test/daily_birds_test.dart`（每日一鸟挑选规则）与 `test/countdown_text_test.dart`（还有多久响铃的文案）。
- **想「看一眼」UI 改动**：临时写个 golden 测试把页面渲染成 PNG（`expectLater(find.byType(BirdAlarmApp), matchesGoldenFile('preview/x.png'))` + `flutter test --update-goldens`），再直接看图；比装机快得多，配色/间距/深色适配一看便知。注意测试字体没有中文，**汉字会显示成方块**，只能判断布局与配色，看完把临时文件删掉别提交。
- `flutter build apk --release --split-per-abi` —— **默认构建方式**（含 Kotlin 的完整构建，能验证原生改动）。**一律用 release，不再用 debug**；按架构拆分，产物 `build\app\outputs\flutter-apk\app-<abi>-release.apk`（arm64-v8a / armeabi-v7a / x86_64）。装机/发版都以此为准。
- `.\install.ps1` —— 构建 release 拆分包 + adb 覆盖安装 + 启动，**默认装 arm64-v8a**。参数 `-Abi armeabi-v7a|x86_64`（换架构）/ `-NoBuild`（用已有包）/ `-NoLaunch`。pwsh 7 下直接在终端跑即可（`LocalMachine` 执行策略 `RemoteSigned`，本地脚本放行，不再需要旧的 `install.bat` 绕执行策略包装器）。

构建环境注意：
- 需要 **Android SDK Platform 36**（compileSdk=36）和 Windows **开发者模式**（Flutter 插件 symlink）。
- Gradle daemon 在某些沙箱化的 shell 里会因 NIO loopback 失败（`PipeImpl ... Invalid argument: connect`），需在普通终端构建。
- `android/gradle.properties` 设了 `-Xmx8G`；内存紧张时守护进程可能起不来。

## 架构：Flutter UI + 原生 Android 闹钟引擎

整个 app 是 **单文件 Flutter UI（`lib/main.dart`，~3800 行）** + **原生 Kotlin 闹钟引擎**，两者通过 MethodChannel `bird_alarm/system_alarm` 通信。

- **原生（Kotlin）是闹钟的真正执行者**，App 关闭也能响：
  - `MainActivity.kt` —— MethodChannel 桥（`scheduleAlarmAt`/`cancelAlarm`/`stopAlarmSound`/`snoozeAlarm`/`prepareAlarmWindow`/`releaseAlarmWindow`/`consumeLaunchAlarm`/`testSystemAlarm`/`transcodeAudio`/`updateSoundSettings`/`updateDownloadProgress`/`finishDownloadProgress`），并通过 `AlarmManager.setAlarmClock` + `setExactAndAllowWhileIdle` 排闹钟。**排闹钟时不再起前台服务**（那会整夜挂前台、是耗电元凶）；只排 `AlarmManager` 精确闹钟 + 发一条普通常驻「已守护」通知（`showGuardNotification`，id=`1011`）。
  - `AlarmReceiver.kt` —— 闹钟广播：起前台服务、播声音、（锁屏/息屏时）拉起响铃界面；并发"响铃前 10 分钟倒计时通知"。响铃那一刻清掉「已守护」通知（id=`1011`），让位给响铃通知。
  - `AlarmSoundService.kt` —— 前台服务，负责持续播放 + 响铃通知 + 贪睡（`ACTION_SNOOZE`，默认 5 分钟）。响铃通知 id = `1001`（唯一，勿与别处冲突）。**前台服务只在「真正响铃」时存在**（由 `AlarmReceiver` 在响铃那一刻 `startForegroundService(ACTION_RING)` 起；精确闹钟触发的广播被允许在后台起前台服务）；`ACTION_ARM` 仅 `testSystemAlarm` 的 10 秒测试还在用。无 action 的重启分支会自停，避免变成「空转前台服务」挂整夜。
  - `NativeAlarmPlayer.kt` —— `MediaPlayer` 播放。`ensureRingingAsset()` 在**响铃那一刻**随机选定本轮鸟鸣并写入 `ringing_asset`（SharedPreferences `bird_alarm_native`）。**闹铃渐响**也在这里：读 prefs 的 `fade_in_seconds`（0=关），从 8% 音量按平方曲线爬到满，`Handler` 每 200ms 调一次音；换铃/停铃时 `cancelFade()`。
  - `BirdAlarmAssets.kt` —— 内置 10 个鸟鸣 asset 路径 + `cnNameFor()` 的中文名映射（须与 Dart 的 `_starterLibrary` 同步）。
  - `DownloadNotifier.kt` —— 鸟鸣下载进度通知（id=`1013`，渠道 `bird_alarm_download`）：下载中是常驻进度条 + 请求提级 Live Update，转码阶段为不确定进度，完成/失败换成会自动消失的普通通知。
  - `AlarmRingActivity.kt` —— **已不再作为响铃 UI**（仅残留 pending-intent 引用，无害）。响铃界面现在是 Flutter 的 `AlarmOverlay`。
- **Flutter（`lib/main.dart`）** 负责：UI、闹钟数据模型与持久化、排闹钟时机的计算（`_nextEnabledAlarmDateTime` → 传给原生 `scheduleAlarmAt`）、以及**响铃时的全屏遮罩 UI**。
  - 关键类：`_AlarmHomePageState`（全部状态/逻辑）、`BirdAlarm`（含 `RepeatRule` 枚举：自定义星期 / 中国工作日 / 中国法定节假日）、`AlarmOverlay`（全屏响铃遮罩）、`ChinaWorkdayCalendar` + `ChinaHolidayData`（节假日判定）、`AppSettings`（全局设置，见下）。
  - **四个 Tab**：闹钟 / 鸟鸣 / 设置 / 关于，靠 `PageView` + 自绘的悬浮底栏 `_FloatingTabBar`（叠在 body 上，各页底部留 `_kFloatingBarInset`）。**既能点底栏也能左右滑动翻页**；每页必须包 `_KeepAlivePage`（`PageView` 会销毁滑出缓存区的页面，不保活则滚动位置/搜索框内容丢失），点底栏走 `_goToTab` → `animateToPage`，高亮由 `onPageChanged` 更新。每页用 `_LargeTitleScrollView`（iOS 大标题）。
  - **设置统一在设置页**（`_SettingsTab`）：主题模式 / 闹铃渐响 / xeno-canto API Key / 权限自检 + 测试闹钟。顶栏不再放设置与测试图标，**新增设置请加在这里，别再开弹窗**。

## 锁屏全屏响铃的关键约束（动 targetSdk 或响铃 UI 前必读）

这是本项目最容易踩坑、且反复折腾过的地方：

- **全屏响铃 = `MainActivity`(`showWhenLocked`) + Flutter `AlarmOverlay`**，**不是**靠从后台启动一个新 Activity。
  Android 14+（targetSdk≥34）的后台启动限制（BAL）会拦截前台服务/广播 `startActivity`（logcat: `Background activity launch blocked ... BAL_BLOCK`），所以**不能依赖后台拉起 `AlarmRingActivity` 来全屏**。
- 可行机制：App 在前台时锁屏 → `MainActivity` 因 `showWhenLocked` 仍显示在锁屏上 → 前台计时器 `_checkAlarms` / `_handleAlarmLaunch` 设置 `_activeAlarm` → `AlarmOverlay` 直接在已可见的 `MainActivity` 上渲染（无需启动新 Activity，绕过 BAL）。
- **必要代价**：App 在前台时锁屏点亮会显示本应用界面。这是"全屏响铃"与"Live Updates 同时成立"的前提，是有意为之，**不要为了"锁屏不显示 app"去掉 `MainActivity` 的 `showWhenLocked`——那样会把全屏弄没**。
- `targetSdk` 保持 **36**（Live Updates 需要）。`prepareAlarmWindow` 只设 `setShowWhenLocked`/`setTurnScreenOn`，**不要 `requestDismissKeyguard`**（用户要无需解锁就能关/贪睡）。
- **省电 vs 屏幕常亮（动响铃 UI 前必读）**：`prepareAlarmWindow` 还会加 `FLAG_KEEP_SCREEN_ON`，它**一旦设上、配合 `showWhenLocked`，会让本应用整夜强制亮屏**（实测整夜掉电 ~50% 的元凶之一）。所以响铃**结束**（关闭/贪睡/通知关闭）后必须调 `releaseAlarmWindow` 清掉它。`releaseAlarmWindow` **只 `clearFlags(FLAG_KEEP_SCREEN_ON | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)`，绝不动 `setShowWhenLocked`/`setTurnScreenOn`**（动了下一次锁屏全屏响铃会被 BAL 拦掉）；并在原生侧用 `getRingingAsset()!=null` 做「仍在响就不释放」的防抢守卫。Flutter 在三条收尾路径（`_dismissAlarm`/`_snoozeAlarm`/`_dismissOverlayIfNativeStopped`）+ 退后台(`paused`/`hidden`)时调它。
- **每秒计时器要分生命周期**：`_ticker` 每秒只更新 `_clock`（`ValueNotifier`，经 `ValueListenableBuilder` 局部重建报时卡片的时间文字）+ 轮询原生（`_checkAlarms`/`_dismissOverlayIfNativeStopped`），**不做整页 `setState`**；它只在 `_activeAlarm!=null` 或 `resumed` 时跑（`_reconcileTicker`），退后台熄屏(`paused`/`hidden`)就停。**绝不在 `inactive` 停**——锁屏遮挡下的前台(showWhenLocked)上报 `inactive`，那时遮罩可能正显示、要继续轮询自动关。原生可能在 `paused` 时拉起响铃，所以 `_ring` 里置 `_activeAlarm` 后要立刻 `_reconcileTicker()` 把计时器拉回来。

## 其他容易踩的点

- **下载卡顿**：`transcodeAudio` 的 MethodChannel 回调默认在 Android 主线程，必须在后台 `Executor` 跑、用 `mainHandler` + `isDestroyed` 守卫回投 `result`（否则 UI 卡死 / 引擎销毁后崩）。
- **响铃通知必须先定鸟再建通知**：`ring()` 里先 `NativeAlarmPlayer.ensureRingingAsset()` 再 `buildNotification`，否则通知里鸟名会回退成"鸟鸣"。
- **节假日数据在线获取**：`ChinaHolidayData` 拉 `https://timor.tech/api/holiday/year/{年}`（每项 `date` + `holiday` 布尔），按年缓存、每周刷新，离线回退 `ChinaWorkdayCalendar` 内置的 2026 表。`isWorkday/isHoliday` 优先用在线数据。内置表只到 2026，跨年靠在线。
- **Live Updates（提级通知）**：用 extra 字符串 `android.requestPromotedOngoing`（不依赖 compileSdk 36 符号），在"即将响铃"倒计时、"正在响铃"和"下载进度"上提级；守护态通知是普通通知、且无关闭键（避免误点）。下载进度额外在 `SDK_INT >= 36` 时调 `setShortCriticalText("42%")` 让状态栏胶囊显示百分比（该符号需 compileSdk 36，已实测能编译）。
- **Flutter 3.44 编译**：`ThemeData.cardTheme` 用 `CardThemeData`（不是 `CardTheme`）。
- **深色模式**：`AppSettings.themeMode` 三态（跟随系统 / 浅色 / 深色），在设置页切换；`BirdAlarmApp` 用 `AnimatedBuilder(animation: appSettings)` 包住 `MaterialApp`，`main()` 里先 `await appSettings.load()` 再 `runApp`（否则会先按系统渲染一帧再跳）。报时卡（`_BirdTimePanel`）的底色是**固定渐变**而非主题色——浅色是日出黄、深色是夜绿（`#0F2C29→#1B4741`）；正因为底色固定，卡片里的文字/图标也必须用**固定色**（`onPanel`/`onPanelMuted`），用主题色会在某一种模式下糊在底色上看不清。两个 painter（`_SkyPatternPainter`/`_CartoonClockBirdPainter`）都吃 `dark` 参数：深色下山丘压暗、卡通鸟描边换浅色（深棕描边在夜色底上会整个消失）。**别再让这张卡恒为浅色**——那样深色模式下就是黑底上贴一张大白纸，夜里很晃眼。
- **响铃设置必须落到原生 prefs**：响铃发生在原生侧、那一刻 App 可能没在跑，所以渐响这类设置由 `_syncSoundSettings()` 经 `updateSoundSettings` 写进 `bird_alarm_native`，**不要指望响铃时回头问 Flutter 要**。
- **双击手势别用 `onDoubleTap`**：`GestureDetector.onDoubleTap` 会让**单击**一直等到双击超时（~300ms）才生效，点一下顿一下。星期格与分段控件都改成自己按时间戳判双击（`_kDoubleTapWindow`），单击零延迟。判「已全选则清空」要用**第一下点击之前**的选择（`_daysBeforeLastTap`），否则全选时永远清不掉。
- **鸟鸣库不要再放「不搜也显示的名录列表」**：名录有一万多条，按顺序列前 30 条永远是那几只鸵鸟，用户会以为界面卡死了。`_filteredBirdNames()` 在搜索词为空时**返回空列表**，搜索框放页面最上方。
- **每日一鸟的照片**：`BirdPhotos.forSpecies(学名)`，两个来源都不需要 key（做法参考原作者的另一个项目 [Birdaholic](https://github.com/oastwy/Birdaholic) 的 `inaturalist_service` / `wikimedia_service`）：
  1. **iNaturalist** `/v1/observations?taxon_name=&quality_grade=research&order_by=votes&photo_license=cc0,cc-by,…`，取首张照片，URL 里 `square.` 换成 `medium.`（约 500px）。**必须带 `photo_license` 过滤**——票数最高的照片经常是 All rights reserved，直接用不合适（Birdaholic 的实现没有过滤这一步）。
  2. 退到 **Wikimedia Commons** 物种分类 `generator=categorymembers&gcmtitle=Category:{学名}`，取 640px `thumburl`，署名从 `extmetadata.Artist`（是 HTML，要扒成纯文本）+ `LicenseShortName` 拼。
  - 结果（含「查过、确实没有」= 空字符串）写进 SharedPreferences，同一只鸟只查一次；取不到就用卡通鸟占位，卡片尺寸不变。
  - **署名不能省**：CC BY / BY-NC 要求标作者与许可证，卡片右下角那行就是干这个的。
- **列表项别用左滑手势**：整页要留给 `PageView` 左右滑动切 Tab，闹钟卡片再做「左滑删除」会抢手势（手指落在卡片上一划就变成拖删除条，页翻不动）。删除走**长按卡片**或编辑弹窗里的删除按钮，两处共用 `showDeleteAlarmDialog`。
- **开关一律用 Material `Switch`**：用户明确不要「安卓苹果缝合」，`CupertinoSwitch` 别再回来。大标题、悬浮底栏、圆角卡片、时间滚轮这些 iOS 味的保留，开关跟系统走。
- **「还有多久响铃」只在 App 内**：报时卡的倒计时由 `countdownText()` 按 `_clock` 每秒重算（只重建那一小块）；**不要**为它加常驻通知——通知里只保留响铃前 10 分钟那条倒计时。
- **选中不能改变尺寸**：`SegmentedButton`/`FilterChip` 选中后会多出对勾、把后面的控件挤走。分段控件和星期格都自绘（`_SegmentedPicker` / `_WeekdayPicker`），等宽 + 固定高，选中只换底色与字重；`test/widget_test.dart` 里有守住这条的用例。

## 仓库约定

- `origin` = `ErikaAlk`（用户的 fork）；`upstream` = 原作者 `oastwy`。**改动推到 `origin`，并在 `origin`（ErikaAlk 的库）上开 PR**，不直推默认分支。`gh` 默认仓库已设为 `ErikaAlk/bird_alarm`（`gh repo set-default`），开 PR 默认就落在用户自己的库上。
- 每次改动同步 `README.md` 的「更新记录」（带日期 + `pubspec.yaml` 版本号，倒序置顶）与代码同提交。
- 改版本号时记得同步 `lib/main.dart` 里 `_AboutPage._appVersion`（关于页显示的版本号，硬编码、需手动跟 `pubspec.yaml` 对齐）。「关于」页有「版本与来源」栏标明这是 ErikaAlk 的 fork、原作者是 `oastwy`，改关于页时务必保留原作者致谢与免责说明。
- `install.ps1` / `鸟瘾闹钟-修复方案.html` 已在 `.gitignore`，是本地工具/文档，不进版本库。

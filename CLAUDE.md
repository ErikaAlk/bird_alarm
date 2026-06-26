# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`bird_alarm`（鸟瘾闹钟）是一个 Flutter 闹钟 App，用随机鸟鸣叫醒，可从 xeno-canto 下载鸟声。
**只针对 Android**——iOS 不维护，改动时无需考虑 iOS。这是 [ErikaAlk](https://github.com/ErikaAlk/bird_alarm)
基于个人使用习惯对原作者项目的 fork，所有改动仅为自用。

## 环境与命令

- Flutter 在 `C:\dev\flutter\bin`（**不一定在 PATH 上**；脚本里用全路径或 `$env:Path += ";C:\dev\flutter\bin"`）。Dart 3.12 / Flutter 3.44。
- `flutter analyze` —— **Dart 改动的主要自验手段**（不需要 Gradle，秒级返回；改完 Dart 必跑）。
- `flutter test` / `flutter test test/widget_test.dart` —— 唯一的测试是 `test/widget_test.dart`（首页渲染冒烟测试）。
- `flutter build apk --release --split-per-abi` —— **默认构建方式**（含 Kotlin 的完整构建，能验证原生改动）。**一律用 release，不再用 debug**；按架构拆分，产物 `build\app\outputs\flutter-apk\app-<abi>-release.apk`（arm64-v8a / armeabi-v7a / x86_64）。装机/发版都以此为准。
- `.\install.ps1` —— 构建 release 拆分包 + adb 覆盖安装 + 启动，**默认装 arm64-v8a**。参数 `-Abi armeabi-v7a|x86_64`（换架构）/ `-NoBuild`（用已有包）/ `-NoLaunch`。pwsh 7 下直接在终端跑即可（`LocalMachine` 执行策略 `RemoteSigned`，本地脚本放行，不再需要旧的 `install.bat` 绕执行策略包装器）。

构建环境注意：
- 需要 **Android SDK Platform 36**（compileSdk=36）和 Windows **开发者模式**（Flutter 插件 symlink）。
- Gradle daemon 在某些沙箱化的 shell 里会因 NIO loopback 失败（`PipeImpl ... Invalid argument: connect`），需在普通终端构建。
- `android/gradle.properties` 设了 `-Xmx8G`；内存紧张时守护进程可能起不来。

## 架构：Flutter UI + 原生 Android 闹钟引擎

整个 app 是 **单文件 Flutter UI（`lib/main.dart`，~1500 行）** + **原生 Kotlin 闹钟引擎**，两者通过 MethodChannel `bird_alarm/system_alarm` 通信。

- **原生（Kotlin）是闹钟的真正执行者**，App 关闭也能响：
  - `MainActivity.kt` —— MethodChannel 桥（`scheduleAlarmAt`/`cancelAlarm`/`stopAlarmSound`/`snoozeAlarm`/`prepareAlarmWindow`/`releaseAlarmWindow`/`consumeLaunchAlarm`/`testSystemAlarm`/`transcodeAudio`），并通过 `AlarmManager.setAlarmClock` + `setExactAndAllowWhileIdle` 排闹钟。**排闹钟时不再起前台服务**（那会整夜挂前台、是耗电元凶）；只排 `AlarmManager` 精确闹钟 + 发一条普通常驻「已守护」通知（`showGuardNotification`，id=`1011`）。
  - `AlarmReceiver.kt` —— 闹钟广播：起前台服务、播声音、（锁屏/息屏时）拉起响铃界面；并发"响铃前 10 分钟倒计时通知"。响铃那一刻清掉「已守护」通知（id=`1011`），让位给响铃通知。
  - `AlarmSoundService.kt` —— 前台服务，负责持续播放 + 响铃通知 + 贪睡（`ACTION_SNOOZE`，默认 5 分钟）。响铃通知 id = `1001`（唯一，勿与别处冲突）。**前台服务只在「真正响铃」时存在**（由 `AlarmReceiver` 在响铃那一刻 `startForegroundService(ACTION_RING)` 起；精确闹钟触发的广播被允许在后台起前台服务）；`ACTION_ARM` 仅 `testSystemAlarm` 的 10 秒测试还在用。无 action 的重启分支会自停，避免变成「空转前台服务」挂整夜。
  - `NativeAlarmPlayer.kt` —— `MediaPlayer` 播放。`ensureRingingAsset()` 在**响铃那一刻**随机选定本轮鸟鸣并写入 `ringing_asset`（SharedPreferences `bird_alarm_native`）。
  - `BirdAlarmAssets.kt` —— 内置 10 个鸟鸣 asset 路径 + `cnNameFor()` 的中文名映射（须与 Dart 的 `_starterLibrary` 同步）。
  - `AlarmRingActivity.kt` —— **已不再作为响铃 UI**（仅残留 pending-intent 引用，无害）。响铃界面现在是 Flutter 的 `AlarmOverlay`。
- **Flutter（`lib/main.dart`）** 负责：UI、闹钟数据模型与持久化、排闹钟时机的计算（`_nextEnabledAlarmDateTime` → 传给原生 `scheduleAlarmAt`）、以及**响铃时的全屏遮罩 UI**。
  - 关键类：`_AlarmHomePageState`（全部状态/逻辑）、`BirdAlarm`（含 `RepeatRule` 枚举：自定义星期 / 中国工作日 / 中国法定节假日）、`AlarmOverlay`（全屏响铃遮罩）、`ChinaWorkdayCalendar` + `ChinaHolidayData`（节假日判定）。

## 锁屏全屏响铃的关键约束（动 targetSdk 或响铃 UI 前必读）

这是本项目最容易踩坑、且反复折腾过的地方：

- **全屏响铃 = `MainActivity`(`showWhenLocked`) + Flutter `AlarmOverlay`**，**不是**靠从后台启动一个新 Activity。
  Android 14+（targetSdk≥34）的后台启动限制（BAL）会拦截前台服务/广播 `startActivity`（logcat: `Background activity launch blocked ... BAL_BLOCK`），所以**不能依赖后台拉起 `AlarmRingActivity` 来全屏**。
- 可行机制：App 在前台时锁屏 → `MainActivity` 因 `showWhenLocked` 仍显示在锁屏上 → 前台计时器 `_checkAlarms` / `_handleAlarmLaunch` 设置 `_activeAlarm` → `AlarmOverlay` 直接在已可见的 `MainActivity` 上渲染（无需启动新 Activity，绕过 BAL）。
- **必要代价**：App 在前台时锁屏点亮会显示本应用界面。这是"全屏响铃"与"Live Updates 同时成立"的前提，是有意为之，**不要为了"锁屏不显示 app"去掉 `MainActivity` 的 `showWhenLocked`——那样会把全屏弄没**。
- `targetSdk` 保持 **36**（Live Updates 需要）。`prepareAlarmWindow` 只设 `setShowWhenLocked`/`setTurnScreenOn`，**不要 `requestDismissKeyguard`**（用户要无需解锁就能关/贪睡）。
- **省电 vs 屏幕常亮（动响铃 UI 前必读）**：`prepareAlarmWindow` 还会加 `FLAG_KEEP_SCREEN_ON`，它**一旦设上、配合 `showWhenLocked`，会让本应用整夜强制亮屏**（实测整夜掉电 ~50% 的元凶之一）。所以响铃**结束**（关闭/贪睡/通知关闭）后必须调 `releaseAlarmWindow` 清掉它。`releaseAlarmWindow` **只 `clearFlags(FLAG_KEEP_SCREEN_ON | FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)`，绝不动 `setShowWhenLocked`/`setTurnScreenOn`**（动了下一次锁屏全屏响铃会被 BAL 拦掉）；并在原生侧用 `getRingingAsset()!=null` 做「仍在响就不释放」的防抢守卫。Flutter 在三条收尾路径（`_dismissAlarm`/`_snoozeAlarm`/`_dismissOverlayIfNativeStopped`）+ 退后台(`paused`/`hidden`)时调它。
- **每秒计时器要分生命周期**：`_ticker`（每秒 `setState` 重建整页 + 轮询原生）只在 `_activeAlarm!=null` 或 `resumed` 时跑（`_reconcileTicker`），退后台熄屏(`paused`/`hidden`)就停。**绝不在 `inactive` 停**——锁屏遮挡下的前台(showWhenLocked)上报 `inactive`，那时遮罩可能正显示、要继续轮询自动关。原生可能在 `paused` 时拉起响铃，所以 `_ring` 里置 `_activeAlarm` 后要立刻 `_reconcileTicker()` 把计时器拉回来。

## 其他容易踩的点

- **下载卡顿**：`transcodeAudio` 的 MethodChannel 回调默认在 Android 主线程，必须在后台 `Executor` 跑、用 `mainHandler` + `isDestroyed` 守卫回投 `result`（否则 UI 卡死 / 引擎销毁后崩）。
- **响铃通知必须先定鸟再建通知**：`ring()` 里先 `NativeAlarmPlayer.ensureRingingAsset()` 再 `buildNotification`，否则通知里鸟名会回退成"鸟鸣"。
- **节假日数据在线获取**：`ChinaHolidayData` 拉 `https://timor.tech/api/holiday/year/{年}`（每项 `date` + `holiday` 布尔），按年缓存、每周刷新，离线回退 `ChinaWorkdayCalendar` 内置的 2026 表。`isWorkday/isHoliday` 优先用在线数据。内置表只到 2026，跨年靠在线。
- **Live Updates（提级通知）**：用 extra 字符串 `android.requestPromotedOngoing`（不依赖 compileSdk 36 符号），只在"即将响铃"倒计时和"正在响铃"上提级；守护态通知是普通通知、且无关闭键（避免误点）。
- **Flutter 3.44 编译**：`ThemeData.cardTheme` 用 `CardThemeData`（不是 `CardTheme`）。
- **深色模式**：跟随系统（无 App 内开关）。报时鸟卡片恒为浅色，其内文字用**固定深色**而非主题色（否则深色下看不清）；卡通鸟/日出渐变插画保留原色。

## 仓库约定

- `origin` = 原作者 `oastwy`；`fork` = `ErikaAlk`（用户的）。**改动推到 `fork`，并在 fork 上开 PR**，不直推默认分支。
- 每次改动同步 `README.md` 的「更新记录」（带日期 + `pubspec.yaml` 版本号，倒序置顶）与代码同提交。
- 改版本号时记得同步 `lib/main.dart` 里 `_AboutPage._appVersion`（关于页显示的版本号，硬编码、需手动跟 `pubspec.yaml` 对齐）。「关于」页有「版本与来源」栏标明这是 ErikaAlk 的 fork、原作者是 `oastwy`，改关于页时务必保留原作者致谢与免责说明。
- `install.ps1` / `鸟瘾闹钟-修复方案.html` 已在 `.gitignore`，是本地工具/文档，不进版本库。

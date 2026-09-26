# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`bird_alarm`（鸟瘾闹钟）是一个 Android 闹钟 App，用随机鸟鸣叫醒，可从 xeno-canto 下载鸟声。
**只针对 Android**。这是 [ErikaAlk](https://github.com/ErikaAlk/bird_alarm) 基于个人使用习惯对原作者项目的 fork，所有改动仅为自用。
**2.0（2026-09-26）起是纯 Kotlin + Jetpack Compose**，界面全走设计库 coloros-ui-kit；1.x 是 Flutter，已整体删除。

## 环境与命令

- 工具链和设计库同一代：Gradle 9.7.1 / AGP 9.4.1 / Kotlin 2.4.20 / Compose BOM 2026.09.00，compileSdk 37，**targetSdk 36**（见锁屏一节），minSdk 26。
  AGP 9 内置 Kotlin，app 模块不要 apply `org.jetbrains.kotlin.android`。升级跟着设计库一起升。
- **设计库经 includeBuild 接入**：`settings.gradle.kts` 逐级往上找 `coloros-ui-kit/android`，两个仓库要并排放在 `code/` 下。
  构建用的是**设计库主检出的工作树**，它切到哪个分支，这边就编进哪个分支的代码；别在设计库主检出里切分支做实验，另开 worktree。
- **Windows 上跑 Gradle 先设** `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'`，否则 agent 的 shell 里报
  `Unable to establish loopback connection`。设了就能在 Windows 直接构建，不用再去 WSL（Flutter 时代的 WSL 构建路径作废）。
- `.\gradlew.bat :app:testDebugUnitTest`：JVM 单测（`app/src/test`）。排程与课表 / 节假日（`ScheduleTest`）、倒计时和文案、每日一鸟（`TextTest`）、
  照片查找与缓存（`BirdPhotosTest`）、响铃页手势与三种震法（`RingGestureTest`）。改了逻辑必跑。
- `.\gradlew.bat :app:assembleRelease`：日常装机的包，`app\build\outputs\apk\release\app-release.apk`（单个通用包，没有原生库，不用再按架构拆）。
  Windows 上改一处重新构建约 1 分钟。
- **release 一直用 debug 签名**（`app/build.gradle.kts` 写死），换签名就装不上旧版，只能卸载重装、闹钟和设置全丢。
- **versionCode 从 5041 起**：Flutter 版按架构拆包时 versionCode = 架构号 × 1000 + 版本号（手机上的 arm64 包是 2040），新版要比它大才能覆盖安装。
  之后每发一版加一。版本名只在 `app/build.gradle.kts`，关于页和设置页读 `BuildConfig.VERSION_NAME`，不用再手动同步。
- `install.ps1`（本地工具，在 `.gitignore` 里）：构建 + adb 覆盖安装 + 启动。
- 模拟器 `pixel9_api36` / `pixel9_api36_2`（见 `~\.claude\ENVIRONMENT.md`）。没装 Cadence 的那台读不到课表，课表规则走「按早八」兜底。

## 架构

单 Activity + Compose 界面 + 原生闹钟引擎（沿用 1.x 的 Kotlin 引擎，没重写）。

- **引擎是闹钟的真正执行者**，App 关闭也能响：
  - `AlarmShared.kt`：排程工具函数（`armAlarmAt`、`armNextUpcoming`、「已守护」通知 id=`1011`）和 `AlarmControl`（界面对引擎的全部操作：`schedule`/`cancel`/`stopSound`/`snooze`/`test`/`saveFadeIn`，Flutter 时代是 MethodChannel 的那一串方法）。
    排闹钟只用 `AlarmManager.setAlarmClock` + `setExactAndAllowWhileIdle`，**不挂前台服务**（整夜挂前台是耗电元凶）。
  - `AlarmReceiver.kt`：闹钟广播，起前台服务、播声音、响铃前 10 分钟倒计时通知；响铃那一刻清掉「已守护」通知。
  - `AlarmSoundService.kt`：前台服务，持续播放 + 响铃通知（id=`1001`，唯一）+ 贪睡（`ACTION_SNOOZE`，5 分钟）。只在真正响铃时存在；`ACTION_ARM` 只有「测试闹钟」在用。
  - `NativeAlarmPlayer.kt`：`MediaPlayer` 播放；`ensureRingingAsset()` 在响铃那一刻随机选鸟并写入 `ringing_asset`；闹铃渐响（读 `fade_in_seconds`）。
  - `BirdAlarmAssets.kt`：内置 10 种鸟鸣（`starters`，音库和原生共用这一份）。
  - `DownloadNotifier.kt`：下载进度通知（id=`1013`，Live Update）。`AudioTranscoder.kt`：下载后转 m4a 并放大 2.5 倍，**必须在后台线程调**。
  - 引擎状态都在 prefs `bird_alarm_native`（`ringing_asset`、`launch_alarm`、`upcoming_triggers`、`sound_pool`、`sound_names`、`skip_trigger_at`、`fade_in_seconds`）。
- **界面层**：
  - `Store.kt`：进程级单例，界面全部状态（Compose state）和业务：闹钟、音库、设置、下载、试听、光闹钟推送。`sync()` 把「接下来 8 次」交给引擎并推给 HA。
    App 自己的数据在 prefs `bird_alarm_app`。
  - `Model.kt`：数据模型（JSON 与 Flutter 版同格式）和排程纯函数（`nextOccurrence`、`upcomingOccurrences`、`lightStartTimes`、`countdownText`…）。
  - `Calendar.kt`：`ChinaWorkdayCalendar` / `ChinaHolidayData`（节假日）/ `CadenceSchedule` / `scheduleDayOf`。
  - `MainActivity.kt`：监听 `ringing_asset` 交给 Store、响铃时点亮屏幕 / 响完释放、主题与系统栏。
  - `ui/`：`App.kt`（三个 tab 横滑翻页 + 悬浮底栏 + 新建闹钟按钮 + 二级页栈 + 响铃页接管）、`AlarmsScreen.kt`（闹钟页、编辑面板）、
    `LibraryScreen.kt`（鸟鸣页、每日一鸟、xeno-canto 查询）、`SettingsScreen.kt`（设置、权限自检、关于）、`RingScreen.kt`（响铃页）、
    `Page.kt`（页面骨架、主题色、小部件）、`Icons.kt`（图标，Lucide 路径）、`Illustration.kt`（卡通报时鸟）。
- **覆盖安装的数据迁移**：第一次启动把 Flutter 的 `FlutterSharedPreferences`（键带 `flutter.` 前缀，整数存成 long）原样搬进 `bird_alarm_app`
  （`Store.migrateFromFlutter`，只搬一次，原文件不删）。Flutter 下载的鸟鸣在 `app_flutter/bird_sounds/`，音库里存的是绝对路径，照用。
  Flutter 版给引擎的内置鸟鸣路径带 `flutter_assets/assets/` 前缀，播放和取鸟名时都先去掉它（`BirdAlarmAssets.LEGACY_PREFIX`），覆盖安装后还没打开 App 就响也不会退成系统铃声。

## 设计

- 遵循全局 DESIGN.md（`DESIGN_SYSTEM_REVISION = "2026.09.24-coloros17"`，写在 `ui/Page.kt`），界面只用 coloros-ui-kit 的组件和 token。
  库里缺的通用件、库的 bug 修在库里，不在这里另起一套。
- 主题色是薄荷绿，按设计库 README 的比例自己派生（`BirdThemeColor`）。十六进制只在主题色、冷启动底色（`res/values*/themes.xml`，库 `bgGrouped` 的镜像）和插画里出现。
- **滚轮 `CoNumberPicker` 要明确给宽度**（`Modifier.width(CoTokens.Picker.minWidth)`）：库里 2026-09-26 以前的版本只靠 `widthIn(min)` 时画布实际宽 0，数字全被裁掉。
  修复在设计库 PR #14；合进 main 之前这边的规避不能删。
- 面板里装卡片分组时，库修好后改用 `CoBottomSheet(grouped = true)`（同在 PR #14），否则亮色下白卡片放在白面板上看不出分组。

## 锁屏全屏响铃的关键约束（动 targetSdk 或响铃页前必读）

- **全屏响铃 = `MainActivity`(`showWhenLocked`) + 应用内响铃页 `RingScreen`**，**不是**从后台启动新 Activity。
  targetSdk ≥ 34 的后台启动限制（BAL）会拦掉前台服务 / 广播里的 `startActivity`（logcat：`Background activity launch blocked`），
  所以 App 在后台时靠响铃通知的**全屏意图**把 MainActivity 拉起来（系统允许），App 在前台时 MainActivity 本来就在锁屏上。2026-09-26 在 API 36 模拟器上两条路都实测过。
- **必要代价**：App 在前台时锁屏点亮会显示本应用界面。这是全屏响铃与 Live Updates 同时成立的前提，**不要为了「锁屏不显示 App」去掉 `showWhenLocked`**。
- `targetSdk` 保持 **36**（Live Updates 需要）。`prepareAlarmWindow` 只设 `setShowWhenLocked` / `setTurnScreenOn`，**不要 `requestDismissKeyguard`**（不用解锁就能关 / 贪睡）。
- **响铃页显示与否只看 `ringing_asset`**：MainActivity 监听这份 prefs（同进程里引擎写入会回调到主线程），有值就显示 `RingScreen`，被清掉（关闭、贪睡、通知里关）就收起。
  所以不再需要 Flutter 时代的每秒轮询、`consumeLaunchAlarm`、`_lastDismissedAt` 那一套去重。
- **省电 vs 屏幕常亮**：`prepareAlarmWindow` 会加 `FLAG_KEEP_SCREEN_ON`，配合 `showWhenLocked` 会让本应用整夜强制亮屏（实测整夜掉电 ~50% 的元凶之一）。
  响铃结束必须 `releaseAlarmWindow` 清掉它（`ringing_asset` 变空时自动调，退到后台时再兜一次）。**只清这两个 flag，绝不动 `setShowWhenLocked` / `setTurnScreenOn`**，
  并且原生还在响（`ringing_asset` 非空）就不释放。
- 响铃开始时，页面（连同编辑面板、弹出菜单这些独立窗口）先拿出组合，否则会压在响铃页上面点不到。响铃中返回键不退出。
- 响铃中 `Store.sync()` 不重排（会补发「已守护」通知，没有启用的闹钟时还会把这一轮撤掉）；一轮结束后重排，**响铃页上点贪睡的那一轮不重排**（重排会撤掉贪睡）。

## 课表规则与 HA 光闹钟

- **「课表」规则**（`RepeatRule.ClassSchedule`）是一个闹钟两个时间：早八那天响 `time`，其余工作日响 `noEarlyTime`，休息日不响。用户明确要求合成一个闹钟，别拆回去。
  某天几点响统一走 `BirdAlarm.timeOn(date)`。
- 早八按 `scheduleDayOf()` 判：Cadence 课表里第一节 9 点前开始 = 早八；否则工作日 = 无早八，休息日 = 节假日。**课表里没有这天（没装 Cadence、没授权、超出范围）的工作日按早八**，
  宁可 7 点响，不能 8:30 才响、睡过第一节课。别改成「不知道就当没课」。
- 课表只在内存（`CadenceSchedule`），每次 `sync` 前和回到前台时读今天起 14 天（Cadence 一次最多 8 天，分两段）。接口约定在 Cadence 仓库的 `docs/对外接口.md`，
  manifest 里的 `<queries><provider>` 和 `READ_SCHEDULE` 权限缺一不可。保存「课表」闹钟时没授权就弹权限。
- **光闹钟联动推的是一串时刻**：`Store.pushLight()` 把接下来 8 次响铃各减提前量 POST 给 HA webhook（`{"linked":true,"times":[秒]}`），HA 自己挑最近一次写进灯。
  这样手机几天不开 App 灯也照样亮。别改成只推「下一次」。内容没变不重复推。
- HA 侧（`Workspace/lab/ha-dorm`）：24 小时内没有要亮的就关掉日出唤醒；开始亮后 40 分钟内不改灯。设备的「时间」是开始变亮的时刻，所以提前量直接减。

## 其他容易踩的点

- **响铃相关设置必须落到原生 prefs**：渐响这类设置由 `Store.setFadeIn` 经 `AlarmControl.saveFadeIn` 写进 `bird_alarm_native`，别指望响铃时回头问界面要。
- **响铃通知先定鸟再建通知**：`ring()` 里先 `ensureRingingAsset()` 再 `buildNotification`，否则通知里鸟名会回退成「鸟鸣」。
- **节假日在线获取**：timor.tech 按年缓存、每周刷新，离线回退内置 2026 表；内置表只到 2026，跨年靠在线。
- **Live Updates**：用 extra 字符串 `android.requestPromotedOngoing`，在「即将响铃」倒计时、「正在响铃」和「下载进度」上提级；守护态通知是普通通知、没有关闭键。
- **响铃页的盲操手势**（`RingScreen`）：整屏上滑关闭、下滑贪睡，阈值 120dp 或甩动 900dp/s，**松手才执行**。越线极短一震、关闭一记长震、贪睡三记短震，
  **三种必须能闭眼分辨**。震动走 `Haptics`（`Vibrator` + 指定振幅 + `USAGE_ALARM`），**别换成 `performHapticFeedback`**（强度由系统设置决定，实测基本感觉不到）；
  manifest 里的 `VIBRATE` 权限必需。阈值别调小，也别改成单击 / 双击。`RingGestureTest` 守住这几条。
- **每日一鸟的照片**（`BirdPhotos`）：先 iNaturalist（**必须带 `photo_license` 过滤**，票数最高的常是 All rights reserved），没有再退到 Wikimedia Commons 物种分类。
  「查失败」和「确实没有」必须分开：失败不写缓存、下次重试，「没有」记 7 天。图片自己下到本地再显示（能设超时、能重试）。**署名不能省**。
- **鸟鸣库不放「不搜也显示的名录列表」**：名录一万多条，按顺序列前 30 条永远是那几只鸵鸟。`Store.filterNames` 搜索词为空返回空。
- xeno-canto 的 v3 查询接口要 API Key（401 / 403 时提示去设置里检查），录音文件本身（`/{id}/download`）不要 Key。
- **星期格双击全选**：自己按时间戳判双击（单击零延迟），判「已全选则清空」要看**第一下之前**的选择。
- **列表项别用左滑手势**：整页左右滑动要留给翻页。删除走长按卡片的菜单或编辑面板里的删除（闹钟随手能重建，DESIGN §11.4 不再二次确认）。
- **行尾 ">" 箭头 = 进下一页**（权限自检、关于、xeno-canto 查询）；单个文本输入（webhook、API Key）开面板、行尾只写当前值不带箭头；
  就地执行的动作（测试闹钟）写「运行」；跳外部的行用外链图标。
- **「还有多久响铃」只在 App 内**（闹钟页大标题下的状态行），不为它加常驻通知。
- 已知遗留（1.x 就有）：自定义规则一天都不选时**每天都响**（界面如实写「每天」），没有「只响一次」；重启后到第一次打开 App 之前闹钟不会重排（没有开机广播）。

## 仓库约定

- `origin` = `ErikaAlk`（用户的 fork）；`upstream` = 原作者 `oastwy`。改动推到 `origin` 并在 `origin` 上开 PR，不直推默认分支。`gh` 默认仓库已设为 `ErikaAlk/bird_alarm`。
- 每次改动同步 `README.md` 的「更新记录」（带日期 + 版本号，倒序置顶）与代码同提交。
- 关于页有原作者致谢与免责说明，改关于页时务必保留。
- `install.ps1` / `鸟瘾闹钟-修复方案.html` 在 `.gitignore` 里，是本地工具 / 文档。

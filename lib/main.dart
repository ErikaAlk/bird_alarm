import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data' show BytesBuilder;
import 'dart:ui' show FontFeature, ImageFilter;

import 'package:audioplayers/audioplayers.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 先把设置读出来再起 UI：否则深色模式会先按系统渲染一帧、再跳到用户选的模式，闪一下。
  await appSettings.load();
  runApp(const BirdAlarmApp());
}

/// 全局设置（外观 / 响铃渐响 / xeno-canto API Key），存在 SharedPreferences 里，改完即存即用。
/// 单独放在这里而不是塞进 `_AlarmHomePageState`：主题模式要在 `MaterialApp` 那一层生效，
/// 比首页更靠上；响铃渐响还要同步给原生（响铃发生在原生侧，那一刻 App 可能没在跑）。
class AppSettings extends ChangeNotifier {
  static const _themeModeKey = 'bird_alarm_theme_mode';
  static const _fadeInKey = 'bird_alarm_fade_in_seconds';
  static const _apiKeyKey = 'bird_alarm_xeno_api_key';

  /// 渐响默认开启 30 秒：突然满音量太吓人。想被立刻叫醒的，在设置页关掉即可。
  static const defaultFadeInSeconds = 30;

  ThemeMode _themeMode = ThemeMode.system;
  int _fadeInSeconds = defaultFadeInSeconds;
  String _xenoApiKey = '';

  ThemeMode get themeMode => _themeMode;

  /// 0 表示关闭渐响（一响就是设定音量）。
  int get fadeInSeconds => _fadeInSeconds;
  bool get fadeInEnabled => _fadeInSeconds > 0;
  String get xenoApiKey => _xenoApiKey;

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    _themeMode = _parseThemeMode(prefs.getString(_themeModeKey));
    _fadeInSeconds = prefs.getInt(_fadeInKey) ?? defaultFadeInSeconds;
    _xenoApiKey = prefs.getString(_apiKeyKey) ?? '';
    notifyListeners();
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    if (mode == _themeMode) return;
    _themeMode = mode;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_themeModeKey, mode.name);
  }

  Future<void> setFadeInSeconds(int seconds) async {
    if (seconds == _fadeInSeconds) return;
    _fadeInSeconds = seconds;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_fadeInKey, seconds);
  }

  Future<void> setXenoApiKey(String key) async {
    final trimmed = key.trim();
    if (trimmed == _xenoApiKey) return;
    _xenoApiKey = trimmed;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_apiKeyKey, trimmed);
  }

  static ThemeMode _parseThemeMode(String? raw) => switch (raw) {
    'light' => ThemeMode.light,
    'dark' => ThemeMode.dark,
    _ => ThemeMode.system,
  };
}

final appSettings = AppSettings();

class BirdAlarmApp extends StatelessWidget {
  const BirdAlarmApp({super.key});

  @override
  Widget build(BuildContext context) {
    // 设置页改主题模式后，整个 MaterialApp 跟着重建。
    return AnimatedBuilder(
      animation: appSettings,
      builder: (context, _) {
        return MaterialApp(
          title: '鸟瘾闹钟',
          debugShowCheckedModeBanner: false,
          themeMode: appSettings.themeMode,
          theme: buildAppTheme(Brightness.light),
          darkTheme: buildAppTheme(Brightness.dark),
          home: const AlarmHomePage(),
        );
      },
    );
  }
}

/// 全应用主题。整体走 iOS 观感：大标题、圆角 20 的卡片、无阴影分组、Cupertino 转场；
/// 浅色保留原来的奶油底色（App 的辨识度），深色用 iOS 系统灰阶而不是 M3 自动生成的紫调。
ThemeData buildAppTheme(Brightness brightness) {
  final light = brightness == Brightness.light;
  // 本项目认领的主题色：薄荷绿 #00D9A3（每个项目一种，表在全局 CLAUDE.md）
  const seed = Color(0xFF00D9A3);
  final scheme = ColorScheme.fromSeed(
    seedColor: seed,
    brightness: brightness,
  ).copyWith(
    // M3 从种子生成的 primary 会被调淡成中间调，这里直接钉死：
    // 深色下用原色（对底 10.2:1），浅色下压深到 #007A5C——薄荷原色在奶油底上只有
    // 1.75:1，而 primary 在这个 App 里还兼着图标和文字色，不压深就看不见
    primary: light ? const Color(0xFF007A5C) : const Color(0xFF00D9A3),
    onPrimary: light ? Colors.white : const Color(0xFF00281E),
    surface: light ? const Color(0xFFFFF5DF) : const Color(0xFF121214),
    surfaceContainerLowest: light ? Colors.white : const Color(0xFF1C1C1E),
    surfaceContainerHighest:
        light ? const Color(0xFFF3EAD3) : const Color(0xFF2C2C2E),
  );
  return ThemeData(
    colorScheme: scheme,
    useMaterial3: true,
    scaffoldBackgroundColor:
        light ? const Color(0xFFFFF5DF) : const Color(0xFF121214),
    splashFactory: InkSparkle.splashFactory,
    // 页面转场用 iOS 的横向推入（返回手势也一并有了）。
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: CupertinoPageTransitionsBuilder(),
        TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
      },
    ),
    appBarTheme: AppBarTheme(
      backgroundColor:
          light ? const Color(0xFFFFF5DF) : const Color(0xFF121214),
      surfaceTintColor: Colors.transparent,
      scrolledUnderElevation: 0,
      centerTitle: false,
    ),
    cardTheme: CardThemeData(
      color: light ? Colors.white : const Color(0xFF1C1C1E),
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
    ),
    dividerTheme: DividerThemeData(
      space: 1,
      thickness: 0.5,
      color: light ? const Color(0x1A000000) : const Color(0x1AFFFFFF),
    ),
    listTileTheme: const ListTileThemeData(
      titleTextStyle: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
    ),
    textTheme: const TextTheme(
      // iOS 大标题的观感：字重更重、字距收紧。
      headlineSmall: TextStyle(
        fontWeight: FontWeight.w700,
        letterSpacing: -0.5,
      ),
      headlineMedium: TextStyle(
        fontWeight: FontWeight.w700,
        letterSpacing: -0.8,
      ),
      titleLarge: TextStyle(fontWeight: FontWeight.w700, letterSpacing: -0.3),
      titleMedium: TextStyle(fontWeight: FontWeight.w600),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: light ? Colors.white : const Color(0xFF2C2C2E),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: scheme.primary, width: 1.5),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      insetPadding: const EdgeInsets.fromLTRB(16, 16, 16, 104),
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      showDragHandle: true,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
    ),
  );
}

/// 闹钟的重复规则：自定义星期 / 中国工作日 / 中国法定节假日。
enum RepeatRule { weekdays, chinaWorkdays, chinaHolidays }

class BirdSound {
  final String id;
  final String cnName;
  final String enName;
  final String sciName;
  final String source;
  final String? url;
  final String? localPath;
  final String? assetPath;

  const BirdSound({
    required this.id,
    required this.cnName,
    required this.enName,
    required this.sciName,
    required this.source,
    this.url,
    this.localPath,
    this.assetPath,
  });

  bool get playable =>
      (url != null && url!.isNotEmpty) ||
      localPath != null ||
      assetPath != null;

  bool get isDownloaded => localPath != null || assetPath != null;

  factory BirdSound.fromJson(Map<String, dynamic> json) => BirdSound(
    id: json['id'] as String,
    cnName: json['cnName'] as String? ?? json['enName'] as String? ?? '未知鸟种',
    enName: json['enName'] as String? ?? '',
    sciName: json['sciName'] as String? ?? '',
    source: json['source'] as String? ?? '本地',
    url: json['url'] as String?,
    localPath: json['localPath'] as String?,
    assetPath: json['assetPath'] as String?,
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'cnName': cnName,
    'enName': enName,
    'sciName': sciName,
    'source': source,
    if (url != null) 'url': url,
    if (localPath != null) 'localPath': localPath,
    if (assetPath != null) 'assetPath': assetPath,
  };
}

class BirdName {
  final String sci;
  final String display;
  final String cn;
  final String en;

  const BirdName({
    required this.sci,
    required this.display,
    required this.cn,
    required this.en,
  });

  factory BirdName.fromJson(Map<String, dynamic> json) => BirdName(
    sci: json['sci'] as String? ?? '',
    display: json['display'] as String? ?? '',
    cn: json['cn'] as String? ?? '',
    en: json['en'] as String? ?? '',
  );
}

class BirdAlarm {
  final String id;
  final TimeOfDay time;
  final Set<int> repeatDays;
  final RepeatRule repeatRule;
  final bool enabled;
  final String label;

  const BirdAlarm({
    required this.id,
    required this.time,
    required this.repeatDays,
    required this.repeatRule,
    required this.enabled,
    required this.label,
  });

  BirdAlarm copyWith({
    TimeOfDay? time,
    Set<int>? repeatDays,
    RepeatRule? repeatRule,
    bool? enabled,
    String? label,
  }) => BirdAlarm(
    id: id,
    time: time ?? this.time,
    repeatDays: repeatDays ?? this.repeatDays,
    repeatRule: repeatRule ?? this.repeatRule,
    enabled: enabled ?? this.enabled,
    label: label ?? this.label,
  );

  static RepeatRule _parseRepeatRule(Map<String, dynamic> json) {
    switch (json['repeatRule'] as String?) {
      case 'chinaWorkdays':
        return RepeatRule.chinaWorkdays;
      case 'chinaHolidays':
        return RepeatRule.chinaHolidays;
      case 'weekdays':
        return RepeatRule.weekdays;
    }
    // 兼容旧数据：以前只有 useChinaWorkdays 布尔字段。
    return (json['useChinaWorkdays'] as bool? ?? false)
        ? RepeatRule.chinaWorkdays
        : RepeatRule.weekdays;
  }

  factory BirdAlarm.fromJson(Map<String, dynamic> json) => BirdAlarm(
    id: json['id'] as String,
    time: TimeOfDay(
      hour: json['hour'] as int? ?? 7,
      minute: json['minute'] as int? ?? 0,
    ),
    repeatDays:
        ((json['repeatDays'] as List<dynamic>?) ?? const [])
            .map((day) => day as int)
            .toSet(),
    repeatRule: _parseRepeatRule(json),
    enabled: json['enabled'] as bool? ?? true,
    label: json['label'] as String? ?? '晨间鸟鸣',
  );

  Map<String, dynamic> toJson() => {
    'id': id,
    'hour': time.hour,
    'minute': time.minute,
    'repeatDays': repeatDays.toList()..sort(),
    'repeatRule': repeatRule.name,
    'enabled': enabled,
    'label': label,
  };
}

class AlarmHomePage extends StatefulWidget {
  const AlarmHomePage({super.key});

  @override
  State<AlarmHomePage> createState() => _AlarmHomePageState();
}

class _AlarmHomePageState extends State<AlarmHomePage>
    with WidgetsBindingObserver {
  static const _alarmsKey = 'bird_alarm_alarms';
  static const _libraryKey = 'bird_alarm_library';
  static const _systemAlarmChannel = MethodChannel('bird_alarm/system_alarm');
  final _random = Random();
  final _player = AudioPlayer();
  final _queryController = TextEditingController(text: 'cnt:China q:A');
  final _speciesSearchController = TextEditingController();

  List<BirdAlarm> _alarms = const [];
  List<BirdSound> _library = _starterLibrary;
  List<BirdSound> _searchResults = const [];
  List<BirdName> _nameList = const [];
  Map<String, BirdName> _nameIndex = const {};
  Set<String> _downloadingIds = const {};
  Timer? _ticker;
  // 渐响用的调音计时器（只在非 Android 的兜底播放路径上用；Android 的渐响在原生侧做）。
  Timer? _fadeTimer;
  AppLifecycleState _lifecycleState = AppLifecycleState.resumed;
  DateTime? _lastTriggeredMinute;
  ActiveAlarm? _activeAlarm;
  // 每秒走时的时钟只驱动报时卡片里的时间文字（ValueListenableBuilder），不再每秒 setState 重建整页。
  final ValueNotifier<DateTime> _clock = ValueNotifier(DateTime.now());
  // 被「倒计时通知 → 关闭闹钟」跳过的那一次触发时刻（毫秒，原生写入）；0 表示无。重排时跳过它。
  int _skipTriggerMs = 0;
  String? _previewingSoundId;
  bool _loaded = false;
  bool _checkingAlarmLaunch = false;
  // 刚关闭/贪睡本轮闹钟的时刻：用来吞掉随后迟到的 alarmFired/resumed 重复触发，
  // 避免第一个遮罩关掉后立刻又弹出第二个（随机鸟）响铃遮罩。
  DateTime? _lastDismissedAt;
  bool _searching = false;
  int _selectedTab = 0;
  final _pageController = PageController();
  // 下载进度：id → 0~1，null 表示进度未知（转码阶段）。同时驱动 App 内进度条与原生 Live Update 通知。
  Map<String, double?> _downloadProgress = const {};
  // 「每日一鸟 / 今日推荐」按日期定种子，当天固定、隔天自动换。缓存住免得每帧重算。
  DailyBird? _dailyPicks;

  static const _starterLibrary = <BirdSound>[
    BirdSound(
      id: 'starter-cuculus-micropterus',
      cnName: '四声杜鹃',
      enName: 'Indian Cuckoo',
      sciName: 'Cuculus micropterus',
      source: '内置鸟鸣 · xeno-canto #1101770',
      assetPath: 'sounds/cuculus_micropterus.m4a',
    ),
    BirdSound(
      id: 'starter-cuculus-canorus',
      cnName: '大杜鹃',
      enName: 'Common Cuckoo',
      sciName: 'Cuculus canorus',
      source: '内置鸟鸣 · xeno-canto #1102893',
      assetPath: 'sounds/cuculus_canorus.m4a',
    ),
    BirdSound(
      id: 'starter-spilornis-cheela',
      cnName: '蛇雕',
      enName: 'Crested Serpent Eagle',
      sciName: 'Spilornis cheela',
      source: '内置鸟鸣 · xeno-canto #1094944',
      assetPath: 'sounds/spilornis_cheela.m4a',
    ),
    BirdSound(
      id: 'starter-francolinus-pintadeanus',
      cnName: '中华鹧鸪',
      enName: 'Chinese Francolin',
      sciName: 'Francolinus pintadeanus',
      source: '内置鸟鸣 · xeno-canto #1034127',
      assetPath: 'sounds/francolinus_pintadeanus.m4a',
    ),
    BirdSound(
      id: 'starter-horornis-fortipes',
      cnName: '强脚树莺',
      enName: 'Brown-flanked Bush Warbler',
      sciName: 'Horornis fortipes',
      source: '内置鸟鸣 · xeno-canto #1088414',
      assetPath: 'sounds/horornis_fortipes.m4a',
    ),
    BirdSound(
      id: 'starter-horornis-canturians',
      cnName: '远东树莺',
      enName: 'Manchurian Bush Warbler',
      sciName: 'Horornis canturians',
      source: '内置鸟鸣 · xeno-canto #1041519',
      assetPath: 'sounds/horornis_canturians.m4a',
    ),
    BirdSound(
      id: 'starter-parus-cinereus',
      cnName: '大山雀',
      enName: 'Cinereous Tit',
      sciName: 'Parus cinereus',
      source: '内置鸟鸣 · xeno-canto #1093376',
      assetPath: 'sounds/parus_cinereus.m4a',
    ),
    BirdSound(
      id: 'starter-dacelo-novaeguineae',
      cnName: '笑翠鸟',
      enName: 'Laughing Kookaburra',
      sciName: 'Dacelo novaeguineae',
      source: '内置鸟鸣 · xeno-canto #1086676',
      assetPath: 'sounds/dacelo_novaeguineae.m4a',
    ),
    BirdSound(
      id: 'starter-psophodes-olivaceus',
      cnName: '绿啸冠鸫',
      enName: 'Eastern Whipbird',
      sciName: 'Psophodes olivaceus',
      source: '内置鸟鸣 · xeno-canto #1088985',
      assetPath: 'sounds/psophodes_olivaceus.m4a',
    ),
    BirdSound(
      id: 'starter-eudynamys-scolopaceus',
      cnName: '噪鹃',
      enName: 'Asian Koel',
      sciName: 'Eudynamys scolopaceus',
      source: '内置鸟鸣 · xeno-canto #1101779',
      assetPath: 'sounds/eudynamys_scolopaceus.m4a',
    ),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _systemAlarmChannel.setMethodCallHandler((call) async {
      if (call.method == 'alarmFired') {
        await _handleAlarmLaunch();
      }
    });
    _configureAlarmAudio();
    if (Platform.isAndroid) {
      _requestAlarmPermissions();
    }
    _load();
    _syncSoundSettings();
    _reconcileTicker();
  }

  // 把「响铃相关」的设置写进原生 prefs。响铃是原生干的活、那一刻 App 可能没在跑，
  // 所以渐响这类设置必须提前落到原生侧，而不是响铃时再问 Flutter 要。
  Future<void> _syncSoundSettings() async {
    if (!Platform.isAndroid) return;
    try {
      await _systemAlarmChannel.invokeMethod<void>('updateSoundSettings', {
        'fadeInSeconds': appSettings.fadeInSeconds,
      });
    } catch (_) {
      // 平台通道不可用时忽略：渐响只是响铃音量曲线，不影响闹钟本身。
    }
  }

  void _tick(Timer _) {
    // 只更新时钟（局部重建报时卡片的时间文字），不再每秒 setState 重建整页 + 重跑鸟名过滤。
    _clock.value = DateTime.now();
    _checkAlarms();
    _dismissOverlayIfNativeStopped();
  }

  // 省电关键：每秒计时器只在「界面真正可见(resumed)」或「正在响铃(_activeAlarm!=null)」时运行。
  // 退到后台 / 锁屏熄屏(paused/hidden)且没有正在响的闹钟时停掉，避免整夜每秒重建整页界面 +
  // 跨平台轮询白耗电。绝不在 inactive 状态停——锁屏遮挡下的前台(showWhenLocked 场景)会上报
  // inactive，那时遮罩可能正显示、需要继续每秒轮询原生以便自动关闭。
  void _reconcileTicker() {
    final isBackground =
        _lifecycleState == AppLifecycleState.paused ||
        _lifecycleState == AppLifecycleState.hidden;
    final shouldRun = _activeAlarm != null || !isBackground;
    if (shouldRun) {
      _ticker ??= Timer.periodic(const Duration(seconds: 1), _tick);
    } else {
      _ticker?.cancel();
      _ticker = null;
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _lifecycleState = state;
    if (state == AppLifecycleState.resumed) {
      _handleAlarmLaunch();
    } else if ((state == AppLifecycleState.paused ||
            state == AppLifecycleState.hidden) &&
        _activeAlarm == null) {
      // 退到后台/熄屏且没有正在响的闹钟时，顺手清掉可能残留的「屏幕常亮」标志（双保险）。
      _releaseAlarmWindow();
    }
    _reconcileTicker();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _ticker?.cancel();
    _fadeTimer?.cancel();
    _clock.dispose();
    _pageController.dispose();
    _queryController.dispose();
    _speciesSearchController.dispose();
    _player.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final alarmRaw = prefs.getString(_alarmsKey);
    final libraryRaw = prefs.getString(_libraryKey);
    // 两个独立的 I/O：鸟名表(bundle)与节假日缓存(prefs)并行加载，缩短冷启动首帧时间。
    await Future.wait([_loadNameIndex(), ChinaHolidayData.loadCache()]);
    setState(() {
      if (alarmRaw != null) {
        _alarms =
            (jsonDecode(alarmRaw) as List<dynamic>)
                .map((item) => BirdAlarm.fromJson(item as Map<String, dynamic>))
                .toList();
      } else {
        _alarms = [
          BirdAlarm(
            id: DateTime.now().microsecondsSinceEpoch.toString(),
            time: const TimeOfDay(hour: 7, minute: 30),
            repeatDays: {1, 2, 3, 4, 5},
            repeatRule: RepeatRule.weekdays,
            enabled: true,
            label: '工作日鸟鸣',
          ),
        ];
      }
      if (libraryRaw != null) {
        _library = [
          ..._starterLibrary,
          ...(jsonDecode(libraryRaw) as List<dynamic>).map(
            (item) => BirdSound.fromJson(item as Map<String, dynamic>),
          ),
        ];
      }
    });
    _loaded = true;
    await _syncSystemAlarm();
    await _handleAlarmLaunch();
    // 后台拉取最新中国节假日数据；有更新则按新数据重排闹钟。
    ChinaHolidayData.refresh().then((changed) {
      if (changed && mounted) _syncSystemAlarm();
    });
  }

  Future<void> _handleAlarmLaunch() async {
    if (!_loaded || _checkingAlarmLaunch || _activeAlarm != null) return;
    _checkingAlarmLaunch = true;
    try {
      final launch =
          await _systemAlarmChannel.invokeMethod<Map<dynamic, dynamic>>(
            'consumeLaunchAlarm',
          ) ??
          const {};
      if (launch['launched'] == true) {
        final assetPath = launch['assetPath'] as String?;
        // 刚关过本轮闹钟的几秒内，只吞掉「迟到且无鸟」(assetPath==null：原生 ringing_asset 已被
        // 关闭清掉)的重复触发，避免随机选鸟弹出第二个遮罩；真正该响的新一轮会带原生刚选定的
        // assetPath，不会被误吞——这样同分钟的第二个闹钟也能正常弹遮罩。
        final dismissedAt = _lastDismissedAt;
        final justDismissed =
            dismissedAt != null &&
            DateTime.now().difference(dismissedAt) < const Duration(seconds: 5);
        if (assetPath == null && justDismissed) return;
        await _ringNextEnabledAlarm(assetPath: assetPath, useNativeAudio: true);
      }
    } catch (_) {
      // 前台计时器在 app 已打开时仍能兜底响铃。
    } finally {
      _checkingAlarmLaunch = false;
    }
  }

  void _checkAlarms() {
    if (_activeAlarm != null) return;
    final now = DateTime.now();
    final minuteStamp = _minuteStamp(now);
    if (_lastTriggeredMinute == minuteStamp) return;
    for (final alarm in _alarms.where((alarm) => alarm.enabled)) {
      if (alarm.time.hour != now.hour || alarm.time.minute != now.minute) {
        continue;
      }
      if (!_alarmRunsOnDate(alarm, now)) continue;
      if (Platform.isAndroid) {
        // 原生引擎才是真正的响铃执行者（选鸟 + 放音）。app 在前台/锁屏可见时，绝不能再用
        // Flutter 自己随机播一只鸟——否则会和原生那只鸟「两只鸟叠着响」，且两个关闭键各停一个。
        // 改为消费原生这一轮，用原生选定的同一只鸟显示遮罩（useNativeAudio=true，不另放音）。
        // 这里不置 _lastTriggeredMinute：原生 launch_alarm 标志可能稍晚才写，靠下一秒重试；
        // 一旦消费成功，_ring 会置 _lastTriggeredMinute 且 _activeAlarm 非空挡住后续触发。
        _handleAlarmLaunch();
        return;
      }
      _lastTriggeredMinute = minuteStamp;
      _ring(alarm);
      break;
    }
  }

  DateTime _minuteStamp(DateTime value) =>
      DateTime(value.year, value.month, value.day, value.hour, value.minute);

  Future<void> _ring(
    BirdAlarm alarm, {
    String? assetPath,
    bool useNativeAudio = false,
  }) async {
    _lastTriggeredMinute = _minuteStamp(DateTime.now());
    // 原生回报的可能是内置 asset 路径，也可能是下载文件的绝对路径，两者都要能对上鸟卡。
    final sound =
        assetPath == null
            ? _library[_random.nextInt(_library.length)]
            : _library.firstWhere(
              (sound) =>
                  sound.assetPath == assetPath || sound.localPath == assetPath,
              orElse: () => _library[_random.nextInt(_library.length)],
            );
    // 遮罩渲染在首页路由的 Stack 里，若此刻有 bottom sheet（新建/编辑、设置、时间选择）
    // 压在 Navigator 上层，遮罩会被弹窗和 ModalBarrier 盖住、按钮点不到；先收掉上层路由。
    // 三处 bottom sheet 调用方都对 null 返回值提前 return，直接 pop 是安全的。
    if (mounted) {
      Navigator.of(context).popUntil((route) => route.isFirst);
    }
    await _prepareAlarmWindow();
    setState(() {
      _previewingSoundId = null;
      _activeAlarm = ActiveAlarm(alarm: alarm, sound: sound);
    });
    // 原生可能在 app 退后台/熄屏(paused)时把本轮闹钟拉起来，此时计时器是停的；
    // 一旦有了正在响的闹钟，立即恢复计时器，保证响铃遮罩能每秒轮询、被通知关闭时自动收起。
    _reconcileTicker();
    if (!useNativeAudio) {
      // 非原生播放的兜底路径（非 Android）也照设置渐响，跟原生行为保持一致。
      await _playSound(sound, fadeInSeconds: appSettings.fadeInSeconds);
    }
  }

  Future<void> _ringNextEnabledAlarm({
    String? assetPath,
    bool useNativeAudio = false,
  }) async {
    if (_activeAlarm != null) return;
    final enabled = _alarms.where((alarm) => alarm.enabled).toList();
    if (enabled.isEmpty) return;
    final now = DateTime.now();
    final dueNow =
        enabled.where((alarm) {
          return alarm.time.hour == now.hour &&
              alarm.time.minute == now.minute &&
              _alarmRunsOnDate(alarm, now);
        }).toList();
    final candidates = dueNow.isNotEmpty ? dueNow : enabled;
    candidates.sort((a, b) => _minutesUntil(a).compareTo(_minutesUntil(b)));
    await _ring(
      candidates.first,
      assetPath: assetPath,
      useNativeAudio: useNativeAudio,
    );
  }

  Future<void> _prepareAlarmWindow() async {
    if (!Platform.isAndroid) return;
    try {
      await _systemAlarmChannel.invokeMethod<void>('prepareAlarmWindow');
    } catch (_) {
      // 平台窗口标志不可用时，闹钟仍能响铃。
    }
  }

  // 响铃结束后请原生释放「屏幕常亮」标志，让屏幕恢复正常熄屏（省电关键）。
  // 原生侧若判定仍在响铃会自动跳过，不会误关正在响的那一轮。
  Future<void> _releaseAlarmWindow() async {
    if (!Platform.isAndroid) return;
    try {
      await _systemAlarmChannel.invokeMethod<void>('releaseAlarmWindow');
    } catch (_) {
      // 平台窗口标志不可用时忽略。
    }
  }

  // 三条收尾路径（app 内关闭 / 通知关闭后自动收起 / 贪睡）的共用尾巴：停播放器、（可选）执行一个
  // 原生动作、收起遮罩、释放屏幕常亮、重置计时器、（可选）重排系统闹钟。每个 await 后都带 mounted 守卫。
  Future<void> _teardownOverlay({
    Future<void> Function()? nativeAction,
    bool resync = true,
  }) async {
    _lastDismissedAt = DateTime.now();
    _fadeTimer?.cancel();
    _fadeTimer = null;
    await _player.stop();
    if (nativeAction != null) await nativeAction();
    if (!mounted) return;
    setState(() {
      _activeAlarm = null;
      _previewingSoundId = null;
    });
    await _releaseAlarmWindow();
    if (!mounted) return;
    _reconcileTicker();
    if (resync) await _syncSystemAlarm();
  }

  Future<void> _dismissAlarm() async {
    if (_activeAlarm == null) return;
    await _teardownOverlay(nativeAction: _stopNativeAlarmSound);
  }

  // 响铃遮罩显示期间，若铃声是从「通知」的关闭/贪睡键停掉的（原生引擎已停、ringing_asset
  // 已清），遮罩这边收不到回调会一直留在屏上。每秒轮询原生是否还在响，停了就把遮罩也关掉，
  // 让「通知关闭」和「app 内关闭」行为一致。仅在 Android 原生响铃模式下生效。
  Future<void> _dismissOverlayIfNativeStopped() async {
    if (_activeAlarm == null || !Platform.isAndroid) return;
    bool stillRinging;
    try {
      stillRinging =
          await _systemAlarmChannel.invokeMethod<bool>('isAlarmRinging') ??
          true;
    } catch (_) {
      return; // 通道不可用时不误关遮罩。
    }
    if (!stillRinging && _activeAlarm != null) {
      // 原生已经停了，这里不必再调 stopAlarmSound。
      await _teardownOverlay();
    }
  }

  Future<void> _snoozeAlarm() async {
    if (_activeAlarm == null) return;
    // 贪睡的重排由原生前台服务负责（停当前铃 + N 分钟后重排）；这里特意不 _syncSystemAlarm(resync=false)，
    // 否则会用「下一次常规发生」覆盖/搅乱原生刚排好的贪睡。
    await _teardownOverlay(
      nativeAction: () async {
        if (!Platform.isAndroid) return;
        try {
          await _systemAlarmChannel.invokeMethod<void>('snoozeAlarm');
        } catch (_) {
          // 平台通道不可用时忽略。
        }
      },
      resync: false,
    );
  }

  Future<void> _stopNativeAlarmSound() async {
    if (!Platform.isAndroid && !Platform.isIOS) return;
    try {
      await _systemAlarmChannel.invokeMethod<void>('stopAlarmSound');
    } catch (_) {
      // Flutter 音频已停；原生服务可能未在运行。
    }
  }

  Future<void> _loadNameIndex() async {
    try {
      final raw = await rootBundle.loadString(
        'assets/data/avilist_ioc_names.json',
      );
      final items =
          (jsonDecode(raw) as List<dynamic>)
              .map((item) => BirdName.fromJson(item as Map<String, dynamic>))
              .toList();
      _nameList = items;
      _nameIndex = {for (final item in items) item.sci: item};
    } catch (_) {
      _nameIndex = const {};
    }
  }

  /// 按响铃时间（时:分）升序排列；时间相同按 id 兜底保证顺序稳定
  /// （id 是等长的微秒时间戳字符串，字典序即创建先后）。
  /// 只在闹钟列表的渲染处调用——一处排序覆盖所有修改 _alarms 的路径，
  /// 各赋值点无须（也不要）自行排序。必须排在拷贝上，不可就地改 _alarms。
  List<BirdAlarm> _sortByTime(List<BirdAlarm> alarms) {
    return [...alarms]..sort((a, b) {
      final byTime = a.time.compareTo(b.time);
      return byTime != 0 ? byTime : a.id.compareTo(b.id);
    });
  }

  Future<void> _save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _alarmsKey,
      jsonEncode(_alarms.map((alarm) => alarm.toJson()).toList()),
    );
    final custom = _library.where((sound) => !sound.id.startsWith('starter-'));
    await prefs.setString(
      _libraryKey,
      jsonEncode(custom.map((sound) => sound.toJson()).toList()),
    );
    await _syncSystemAlarm();
  }

  Future<void> _syncSystemAlarm() async {
    if (!Platform.isAndroid && !Platform.isIOS) return;
    // 先取原生记录的「被倒计时通知跳过的那一次触发时刻」，下面排程时跳过它（避免关了又被排回来）。
    if (Platform.isAndroid) {
      try {
        _skipTriggerMs =
            await _systemAlarmChannel.invokeMethod<int>('getSkippedTrigger') ??
            0;
      } catch (_) {
        _skipTriggerMs = 0;
      }
    }
    final next = _nextEnabledAlarmDateTime();
    try {
      if (next == null) {
        await _systemAlarmChannel.invokeMethod<void>('cancelAlarm');
      } else {
        if (Platform.isIOS) {
          await _systemAlarmChannel.invokeMethod<void>(
            'requestAlarmPermissions',
          );
        }
        // 把整库里"能离线播放的鸟鸣"（内置 asset + 下载到本机的文件）下发给原生，
        // 由原生在响铃那一刻随机选；这样下载的鸟鸣才会真正进入抽取池。
        // 把「接下来若干次」发生时刻一并下发：每次响铃后 / 在通知点「关闭」后，原生据此续排下一次，
        // 不用打开 App，相近的多个闹钟也能一个接一个排上。
        final upcoming = _upcomingTriggers();
        final pool = _nativeSoundPool();
        await _systemAlarmChannel.invokeMethod<void>('scheduleAlarmAt', {
          'triggerAtMillis': next.millisecondsSinceEpoch,
          'upcomingTriggers': upcoming.join(','),
          'label': '鸟瘾闹钟',
          'soundPaths': pool.map((entry) => entry.key).toList(),
          'soundNames': {for (final entry in pool) entry.key: entry.value},
        });
      }
    } catch (_) {
      // The foreground timer still works if the platform channel is unavailable.
    }
  }

  // 原生响铃可离线播放的音库：内置 asset 用完整 flutter_assets 路径，下载/本地文件用绝对路径。
  // 返回 (原生引用路径 → 中文鸟名)，原生据此随机选鸟并在响铃通知里显示正确鸟名。
  List<MapEntry<String, String>> _nativeSoundPool() {
    final pool = <MapEntry<String, String>>[];
    for (final sound in _library) {
      final ref =
          sound.localPath ??
          (sound.assetPath != null
              ? 'flutter_assets/assets/${sound.assetPath}'
              : null);
      if (ref == null) continue;
      pool.add(MapEntry(ref, sound.cnName));
    }
    return pool;
  }

  // 设置页的「检查闹钟权限」：打开自检面板，逐项列出状态。
  // 以前这里直接调 _requestAlarmPermissions——权限都齐时它一个分支都不进、什么也不做，
  // 用户点了没任何反应，看着像按钮坏了。
  Future<void> _showPermissionCheck() async {
    // 推入新页面而不是弹底部浮层：那一行右边有 ">" 箭头，箭头就该意味着「进下一页」。
    // 转场用主题里的 Cupertino 横推，返回手势也一并有了。
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (context) => const _PermissionCheckPage(),
      ),
    );
  }

  Future<void> _requestAlarmPermissions() async {
    if (!Platform.isAndroid) return;
    try {
      await _systemAlarmChannel.invokeMethod<void>('requestAlarmPermissions');
    } catch (_) {
      // Android versions below the runtime notification permission ignore this.
    }
  }

  Future<void> _testSystemAlarm() async {
    if (!Platform.isAndroid) return;
    try {
      await _systemAlarmChannel.invokeMethod<void>('testSystemAlarm');
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('已安排 10 秒后的守护服务测试')));
      }
    } catch (_) {
      // 平台通道不可用时忽略；真机上原生闹钟链路仍会按时响铃。
    }
  }

  // fadeInSeconds > 0 时音量从很轻爬到满（曲线与原生 NativeAlarmPlayer 一致：平方，
  // 起点 8%）。试听走 0，闹钟响铃走设置值。
  Future<void> _playSound(BirdSound sound, {int fadeInSeconds = 0}) async {
    _fadeTimer?.cancel();
    _fadeTimer = null;
    await _player.stop();
    await _configureAlarmAudio();
    await _player.setReleaseMode(ReleaseMode.loop);
    await _player.setVolume(fadeInSeconds > 0 ? _fadeStartVolume : 1);
    if (fadeInSeconds > 0) _startFadeIn(fadeInSeconds);
    try {
      if (sound.localPath != null) {
        await _player.play(
          DeviceFileSource(
            sound.localPath!,
            mimeType: _mimeFor(sound.localPath!),
          ),
        );
      } else if (sound.assetPath != null) {
        await _player.play(
          AssetSource(sound.assetPath!, mimeType: _mimeFor(sound.assetPath!)),
        );
      } else if (sound.url != null && sound.url!.isNotEmpty) {
        await _player.play(
          UrlSource(sound.url!, mimeType: _mimeFor(sound.url!)),
        );
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('当前鸟鸣没有可播放音频，已进入静音挑战模式')));
      }
    }
  }

  static const _fadeStartVolume = 0.08;

  void _startFadeIn(int seconds) {
    final startedAt = DateTime.now();
    _fadeTimer = Timer.periodic(const Duration(milliseconds: 200), (timer) {
      final ratio = (DateTime.now().difference(startedAt).inMilliseconds /
              (seconds * 1000))
          .clamp(0.0, 1.0);
      _player.setVolume(
        _fadeStartVolume + (1 - _fadeStartVolume) * ratio * ratio,
      );
      if (ratio >= 1) {
        timer.cancel();
        _fadeTimer = null;
      }
    });
  }

  Future<void> _configureAlarmAudio() async {
    await _player.setAudioContext(
      AudioContext(
        android: const AudioContextAndroid(
          stayAwake: true,
          contentType: AndroidContentType.sonification,
          usageType: AndroidUsageType.alarm,
          audioFocus: AndroidAudioFocus.gainTransient,
        ),
      ),
    );
  }

  Future<void> _togglePreview(BirdSound sound) async {
    if (_activeAlarm != null) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('闹钟响铃中，先关闭闹钟。')));
      return;
    }
    if (_previewingSoundId == sound.id) {
      await _player.pause();
      if (mounted) setState(() => _previewingSoundId = null);
      return;
    }
    await _playSound(sound);
    if (mounted) setState(() => _previewingSoundId = sound.id);
  }

  Future<void> _pickLocalAudio() async {
    final result = await FilePicker.platform.pickFiles(type: FileType.audio);
    final file = result?.files.single;
    if (file == null || file.path == null) return;
    final name = file.name.replaceFirst(RegExp(r'\.[^.]+$'), '');
    setState(() {
      _library = [
        ..._library,
        BirdSound(
          id: 'local-${DateTime.now().microsecondsSinceEpoch}',
          cnName: name,
          enName: name,
          sciName: '',
          source: '用户上传',
          localPath: file.path,
        ),
      ];
    });
    await _save();
  }

  Future<void> _searchXenoCanto() async {
    final rawQuery = _queryController.text.trim();
    if (rawQuery.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请输入 xeno-canto 查询条件')));
      return;
    }
    setState(() => _searching = true);
    try {
      await _save();
      final query = Uri.encodeQueryComponent(rawQuery);
      final key = Uri.encodeQueryComponent(appSettings.xenoApiKey);
      final keyPart = key.isEmpty ? '' : '&key=$key';
      final uri = Uri.parse(
        'https://xeno-canto.org/api/3/recordings?query=$query&per_page=12$keyPart',
      );
      final response = await http.get(uri);
      if (response.statusCode != 200) {
        throw Exception('HTTP ${response.statusCode}');
      }
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final recordings = (data['recordings'] as List<dynamic>? ?? const []);
      setState(() {
        _searchResults =
            recordings
                .map((raw) {
                  final item = raw as Map<String, dynamic>;
                  var fileUrl = item['file'] as String?;
                  if (fileUrl != null && fileUrl.startsWith('//')) {
                    fileUrl = 'https:$fileUrl';
                  }
                  return BirdSound(
                    id: 'xc-${item['id']}',
                    cnName: _displayNameFor(
                      '${item['gen'] ?? ''} ${item['sp'] ?? ''}'.trim(),
                      item['en'] as String? ?? 'Xeno-canto 鸟鸣',
                    ),
                    enName: item['en'] as String? ?? '',
                    sciName: '${item['gen'] ?? ''} ${item['sp'] ?? ''}'.trim(),
                    source:
                        'xeno-canto #${item['id']} · ${item['cnt'] ?? ''} · ${item['q'] ?? ''}',
                    url: fileUrl,
                  );
                })
                .where((sound) => sound.url != null)
                .toList();
      });
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('xeno-canto 查询失败：$error')));
      }
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  Future<void> _addXenoSound(BirdSound sound) async {
    if (_library.any((item) => item.id == sound.id)) return;
    setState(() => _library = [..._library, sound]);
    await _save();
  }

  // aliasId：鸟种列表按「物种」记进度（species-<学名>），录音本身按录音 id 记，
  // 两边都要看到同一次下载的进度条，所以同一次下载可以挂两个 id。
  Future<void> _downloadXenoSound(BirdSound sound, {String? aliasId}) async {
    final url = sound.url;
    if (url == null || url.isEmpty) return;
    final ids = <String>{sound.id, if (aliasId != null) aliasId};
    setState(() {
      _downloadingIds = {..._downloadingIds, ...ids};
      _downloadProgress = {..._downloadProgress, for (final id in ids) id: 0};
    });
    _pushDownloadNotification(sound.cnName, 0);
    try {
      await _save();
      final bytes = await _downloadWithProgress(url, sound, ids);
      final dir = await getApplicationDocumentsDirectory();
      final audioDir = Directory('${dir.path}/bird_sounds');
      await audioDir.create(recursive: true);
      final fileName = '${_safeFileName(sound.id)}.mp3';
      final file = File('${audioDir.path}/$fileName');
      await file.writeAsBytes(bytes, flush: true);
      // 转码没有可读进度，进度条切成"不确定"状态，通知里也如实说明在做什么。
      _setDownloadProgress(ids, null);
      _pushDownloadNotification(sound.cnName, -1, text: '正在转码为闹钟音频…');
      final localPath = await _prepareDownloadedAudio(file, sound.id);
      final downloaded = BirdSound(
        id: '${sound.id}-local',
        cnName: sound.cnName,
        enName: sound.enName,
        sciName: sound.sciName,
        source: '${sound.source} · 已下载',
        localPath: localPath,
      );
      setState(() {
        _library = [
          ..._library.where((item) => item.id != downloaded.id),
          downloaded,
        ];
      });
      await _save();
      _finishDownloadNotification('鸟鸣下载完成', '「${sound.cnName}」已加入随机抽取池');
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('已下载：${sound.cnName}')));
      }
    } catch (error) {
      _finishDownloadNotification('鸟鸣下载失败', '${sound.cnName}：$error');
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('下载失败：$error')));
      }
    } finally {
      if (mounted) {
        setState(() {
          _downloadingIds = {..._downloadingIds}..removeAll(ids);
          _downloadProgress = {..._downloadProgress}
            ..removeWhere((key, _) => ids.contains(key));
        });
      }
    }
  }

  // 流式下载：一边收字节一边报进度（App 内进度条 + 原生 Live Update 通知）。
  // 通知更新做了节流——每变 2% 或每 400ms 才推一次，否则一次下载会刷成百上千条通知更新。
  Future<List<int>> _downloadWithProgress(
    String url,
    BirdSound sound,
    Set<String> ids,
  ) async {
    final client = http.Client();
    try {
      final response = await client.send(http.Request('GET', Uri.parse(url)));
      if (response.statusCode != 200) {
        throw Exception('HTTP ${response.statusCode}');
      }
      final total = response.contentLength ?? 0;
      final buffer = BytesBuilder(copy: false);
      var lastPushedPercent = -1;
      var lastPushedAt = DateTime.now();
      await for (final chunk in response.stream) {
        buffer.add(chunk);
        if (total <= 0) continue;
        final ratio = buffer.length / total;
        final percent = (ratio * 100).round();
        final now = DateTime.now();
        if (percent - lastPushedPercent >= 2 ||
            now.difference(lastPushedAt).inMilliseconds >= 400) {
          lastPushedPercent = percent;
          lastPushedAt = now;
          _setDownloadProgress(ids, ratio.clamp(0.0, 1.0));
          _pushDownloadNotification(sound.cnName, percent);
        }
      }
      final bytes = buffer.takeBytes();
      if (bytes.isEmpty) throw Exception('下载内容为空');
      return bytes;
    } finally {
      client.close();
    }
  }

  void _setDownloadProgress(Set<String> ids, double? value) {
    if (!mounted) return;
    setState(() {
      _downloadProgress = {
        ..._downloadProgress,
        for (final id in ids) id: value,
      };
    });
  }

  // progress: 0~100；传 -1 表示进度未知（转码中），原生显示不确定进度条。
  void _pushDownloadNotification(
    String birdName,
    int progress, {
    String? text,
  }) {
    if (!Platform.isAndroid) return;
    _systemAlarmChannel
        .invokeMethod<void>('updateDownloadProgress', {
          'title': '正在下载「$birdName」',
          'text': text ?? (progress < 0 ? '处理中…' : '$progress%'),
          'progress': progress,
        })
        .catchError((_) {
          // 通知只是进度反馈，失败不影响下载本身。
        });
  }

  void _finishDownloadNotification(String title, String text) {
    if (!Platform.isAndroid) return;
    _systemAlarmChannel
        .invokeMethod<void>('finishDownloadProgress', {
          'title': title,
          'text': text,
        })
        .catchError((_) {});
  }

  Future<String> _prepareDownloadedAudio(File source, String id) async {
    if (!Platform.isAndroid) return source.path;
    final target = File('${source.parent.path}/${_safeFileName(id)}.m4a');
    try {
      final processedPath = await _systemAlarmChannel.invokeMethod<String>(
        'transcodeAudio',
        {'inputPath': source.path, 'outputPath': target.path, 'gain': 2.5},
      );
      if (processedPath != null && processedPath.isNotEmpty) {
        final processed = File(processedPath);
        if (await processed.exists() && await processed.length() > 0) {
          if (processed.path != source.path && await source.exists()) {
            await source.delete();
          }
          return processed.path;
        }
      }
    } catch (_) {
      // Keep the original download if the device cannot transcode this file.
    }
    return source.path;
  }

  Future<void> _downloadSpeciesFromXeno(BirdName bird) async {
    final parts = bird.sci.split(RegExp(r'\s+'));
    if (parts.length < 2) return;
    _queryController.text = 'gen:${parts[0]} sp:${parts[1]}';
    setState(() => _searching = true);
    final downloadId = 'species-${bird.sci}';
    setState(() => _downloadingIds = {..._downloadingIds, downloadId});
    try {
      await _save();
      final query = Uri.encodeQueryComponent(
        'gen:${parts[0]} sp:${parts[1]} q:">C"',
      );
      final key = Uri.encodeQueryComponent(appSettings.xenoApiKey);
      final keyPart = key.isEmpty ? '' : '&key=$key';
      final uri = Uri.parse(
        'https://xeno-canto.org/api/3/recordings?query=$query&per_page=20$keyPart',
      );
      final response = await http.get(uri);
      if (response.statusCode != 200) {
        throw Exception('HTTP ${response.statusCode}');
      }
      final data = jsonDecode(response.body) as Map<String, dynamic>;
      final recordings =
          (data['recordings'] as List<dynamic>? ?? const [])
              .cast<Map<String, dynamic>>();
      final picked = recordings.firstWhere(
        (item) => (item['file'] as String?)?.isNotEmpty == true,
        orElse: () => const {},
      );
      if (picked.isEmpty) {
        throw Exception('没有可下载录音');
      }
      var fileUrl = picked['file'] as String;
      if (fileUrl.startsWith('//')) fileUrl = 'https:$fileUrl';
      await _downloadXenoSound(
        BirdSound(
          id: 'xc-${picked['id']}',
          cnName: bird.display,
          enName: bird.en,
          sciName: bird.sci,
          source:
              'xeno-canto #${picked['id']} · ${picked['cnt'] ?? ''} · ${picked['q'] ?? ''}',
          url: fileUrl,
        ),
        aliasId: downloadId,
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('下载失败：$error')));
      }
    } finally {
      if (mounted) {
        setState(() {
          _searching = false;
          _downloadingIds = {..._downloadingIds}..remove(downloadId);
        });
      }
    }
  }

  /// 鸟种搜索结果。**没输入搜索词就返回空**——以前不搜也列出名录前 30 条，
  /// 永远是同几只鸵鸟，看着像界面坏了；现在只有搜了才出结果。
  List<BirdName> _filteredBirdNames() {
    final query = _speciesSearchController.text.trim().toLowerCase();
    if (query.isEmpty) return const [];
    return _nameList
        .where(
          (bird) =>
              bird.display.toLowerCase().contains(query) ||
              bird.cn.toLowerCase().contains(query) ||
              bird.en.toLowerCase().contains(query) ||
              bird.sci.toLowerCase().contains(query),
        )
        .take(30)
        .toList();
  }

  String _displayNameFor(String sciName, String englishName) {
    final match = _nameIndex[sciName];
    if (match == null) return englishName;
    return match.display.isNotEmpty ? match.display : englishName;
  }

  Future<void> _editAlarm([BirdAlarm? existing]) async {
    final result = await showModalBottomSheet<AlarmEditorResult>(
      context: context,
      isScrollControlled: true,
      builder: (context) => AlarmEditor(alarm: existing),
    );
    if (result == null) return;
    if (result.delete) {
      if (existing != null) await _deleteAlarm(existing);
      return;
    }
    final saved = result.alarm;
    if (saved == null) return;
    setState(() {
      if (existing == null) {
        _alarms = [..._alarms, saved];
      } else {
        _alarms =
            _alarms
                .map((alarm) => alarm.id == saved.id ? saved : alarm)
                .toList();
      }
    });
    await _save();
  }

  /// 「每日一鸟」：当天算一次就缓存起来，隔天自动重算。挑选逻辑见纯函数 [pickDailyBird]。
  DailyBird _dailyBird() {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final cached = _dailyPicks;
    if (cached != null && cached.day == today) return cached;
    final picked = pickDailyBird(names: _nameList, day: today);
    // day == null 表示鸟名表还没加载完，这种空结果不能缓存，否则今天剩下的时间里
    // 每日一鸟一直是空的（缓存是按日期失效的）。
    if (picked.day != null) _dailyPicks = picked;
    return picked;
  }

  @override
  Widget build(BuildContext context) {
    final active = _activeAlarm;
    return Stack(
      textDirection: TextDirection.ltr,
      children: [
        Scaffold(
          // 底栏不是 bottomNavigationBar，而是叠在 body 之上的悬浮胶囊：内容从它下面穿过去
          // （毛玻璃才有东西可糊），各页滚动内容底部自己留 _kFloatingBarInset 的空白。
          body: Stack(
            children: [
              // 悬浮底栏配 PageView：既能点底栏切页，也能左右滑动翻页（只有底栏不能滑很别扭）。
              // 各页包一层 _KeepAlivePage 保住状态——PageView 会回收滑出缓存区的页面，
              // 不保活的话切回来滚动位置、搜索框内容都没了（IndexedStack 时代不用操心这个）。
              PageView(
                controller: _pageController,
                onPageChanged: (index) => setState(() => _selectedTab = index),
                children: [
                  _KeepAlivePage(
                    child: _AlarmTab(
                      clock: _clock,
                      nextAlarm: _nextAlarmText(),
                      nextAlarmAt: _nextEnabledAlarmDateTime(),
                      alarms: _sortByTime(_alarms),
                      onAddAlarm: () => _editAlarm(),
                      onEditAlarm: _editAlarm,
                      onDeleteAlarm: _deleteAlarm,
                      onAlarmEnabledChanged: (alarm, enabled) async {
                        setState(() {
                          _alarms =
                              _alarms
                                  .map(
                                    (item) =>
                                        item.id == alarm.id
                                            ? item.copyWith(enabled: enabled)
                                            : item,
                                  )
                                  .toList();
                        });
                        await _save();
                      },
                    ),
                  ),
                  _KeepAlivePage(
                    child: _LibraryPanel(
                      library: _library,
                      species: _filteredBirdNames(),
                      daily: _dailyBird(),
                      controller: _queryController,
                      speciesSearchController: _speciesSearchController,
                      searching: _searching,
                      downloadingIds: _downloadingIds,
                      downloadProgress: _downloadProgress,
                      previewingSoundId: _previewingSoundId,
                      results: _searchResults,
                      onUpload: _pickLocalAudio,
                      onSearch: _searchXenoCanto,
                      onSpeciesSearchChanged: (_) => setState(() {}),
                      onAdd: _addXenoSound,
                      onDownloadSpecies: _downloadSpeciesFromXeno,
                      onDownload: _downloadXenoSound,
                      onPreview: _togglePreview,
                    ),
                  ),
                  _KeepAlivePage(
                    child: _SettingsTab(
                      onFadeInChanged: (seconds) async {
                        await appSettings.setFadeInSeconds(seconds);
                        await _syncSoundSettings();
                        if (mounted) setState(() {});
                      },
                      onTestAlarm: _testSystemAlarm,
                      onCheckPermissions: _showPermissionCheck,
                    ),
                  ),
                  const _KeepAlivePage(child: _AboutPage()),
                ],
              ),
              Align(
                alignment: Alignment.bottomCenter,
                child: _FloatingTabBar(
                  selectedIndex: _selectedTab,
                  onSelected: _goToTab,
                ),
              ),
            ],
          ),
        ),
        if (active != null)
          Positioned.fill(
            child: AlarmOverlay(
              active: active,
              onDismiss: _dismissAlarm,
              onSnooze: _snoozeAlarm,
            ),
          ),
      ],
    );
  }

  // 点底栏切页：动画滑过去，和手动滑动是同一条路径（onPageChanged 负责更新高亮）。
  void _goToTab(int index) {
    if (!_pageController.hasClients) {
      setState(() => _selectedTab = index);
      return;
    }
    _pageController.animateToPage(
      index,
      duration: const Duration(milliseconds: 260),
      curve: Curves.easeOutCubic,
    );
  }

  Future<void> _deleteAlarm(BirdAlarm alarm) async {
    setState(() {
      _alarms = _alarms.where((item) => item.id != alarm.id).toList();
    });
    await _save();
  }

  String _nextAlarmText() {
    final now = DateTime.now();
    BirdAlarm? bestAlarm;
    DateTime? bestAt;
    for (final alarm in _alarms.where((alarm) => alarm.enabled)) {
      final at = _nextOccurrence(alarm, from: now);
      if (at == null) continue;
      if (bestAt == null || at.isBefore(bestAt)) {
        bestAt = at;
        bestAlarm = alarm;
      }
    }
    if (bestAt == null || bestAlarm == null) return '暂无启用闹钟';
    // 按日历日差算「今天/明天/后天/N 天后」，而不是流逝分钟数（跨午夜会差一天）。
    final today = DateTime(now.year, now.month, now.day);
    final thatDay = DateTime(bestAt.year, bestAt.month, bestAt.day);
    final dayDiff = thatDay.difference(today).inDays;
    final dayText = switch (dayDiff) {
      0 => '今天',
      1 => '明天',
      2 => '后天',
      _ => '$dayDiff 天后',
    };
    return '$dayText ${bestAlarm.time.format(context)} · ${bestAlarm.label}';
  }

  int _minutesUntil(BirdAlarm alarm) {
    final now = DateTime.now();
    final next = _nextOccurrence(alarm, from: now);
    return next == null ? 999999 : next.difference(now).inMinutes;
  }

  // 某个闹钟在 [from, from+366 天) 内的下一次发生时刻；找不到返回 null。
  // 搜索窗口取一年：足以覆盖「节假日（含周末）」这类相邻匹配可能间隔数日的规则（旧的 8 天窗口
  // 在「仅法定节假日」语义下会因假日相隔太远而返回 null → 闹钟被静默取消、永不响）。
  // 同时跳过被「倒计时通知 → 关闭闹钟」标记的那一次发生（_skipTriggerMs）。
  DateTime? _nextOccurrence(BirdAlarm alarm, {DateTime? from}) {
    final base = from ?? DateTime.now();
    for (var offset = 0; offset < 366; offset++) {
      final day = base.add(Duration(days: offset));
      if (!_alarmRunsOnDate(alarm, day)) continue;
      final candidate = DateTime(
        day.year,
        day.month,
        day.day,
        alarm.time.hour,
        alarm.time.minute,
      );
      if (!candidate.isAfter(base)) continue;
      if (_skipTriggerMs != 0 &&
          candidate.millisecondsSinceEpoch == _skipTriggerMs) {
        continue; // 这一次被「倒计时通知 → 关闭闹钟」跳过，看下一次。
      }
      return candidate;
    }
    return null;
  }

  // 接下来若干次（全局、跨所有启用闹钟）的发生时刻，升序的毫秒值。下发给原生，供「响铃后/关闭后
  // 续排下一次」；取一小串即可覆盖相近的多个闹钟，Flutter 每次同步都会刷新整张表。
  List<int> _upcomingTriggers({int count = 8}) {
    final result = <int>[];
    DateTime? cursor;
    for (var i = 0; i < count; i++) {
      final next = _nextEnabledAlarmDateTime(after: cursor);
      if (next == null) break;
      result.add(next.millisecondsSinceEpoch);
      cursor = next;
    }
    return result;
  }

  // 最近的一次启用闹钟发生时刻。传 after 则求「严格晚于 after」的那一次。
  DateTime? _nextEnabledAlarmDateTime({DateTime? after}) {
    final from = after ?? DateTime.now();
    DateTime? best;
    for (final alarm in _alarms.where((alarm) => alarm.enabled)) {
      final candidate = _nextOccurrence(alarm, from: from);
      if (candidate == null) continue;
      if (best == null || candidate.isBefore(best)) best = candidate;
    }
    return best;
  }

  bool _alarmRunsOnDate(BirdAlarm alarm, DateTime date) {
    switch (alarm.repeatRule) {
      case RepeatRule.chinaWorkdays:
        return ChinaWorkdayCalendar.isWorkday(date);
      case RepeatRule.chinaHolidays:
        // 休息日：法定节假日 + 正常放假的周末；调休补班日与普通工作日不响。
        return !ChinaWorkdayCalendar.isWorkday(date);
      case RepeatRule.weekdays:
        return alarm.repeatDays.isEmpty ||
            alarm.repeatDays.contains(date.weekday);
    }
  }
}

class _AlarmTab extends StatelessWidget {
  final ValueListenable<DateTime> clock;
  final String nextAlarm;
  // 下一次响铃的绝对时刻，用来在报时卡里算「还有多久」。只在 App 内显示，
  // 不进通知——通知只有响铃前 10 分钟那条倒计时。
  final DateTime? nextAlarmAt;
  final List<BirdAlarm> alarms;
  final VoidCallback onAddAlarm;
  final ValueChanged<BirdAlarm> onEditAlarm;
  final ValueChanged<BirdAlarm> onDeleteAlarm;
  final Future<void> Function(BirdAlarm alarm, bool enabled)
  onAlarmEnabledChanged;

  const _AlarmTab({
    required this.clock,
    required this.nextAlarm,
    required this.nextAlarmAt,
    required this.alarms,
    required this.onAddAlarm,
    required this.onEditAlarm,
    required this.onDeleteAlarm,
    required this.onAlarmEnabledChanged,
  });

  @override
  Widget build(BuildContext context) {
    return _LargeTitleScrollView(
      title: '闹钟',
      actions: [
        _CircleActionButton(
          icon: Icons.add,
          tooltip: '新闹钟',
          onPressed: onAddAlarm,
        ),
      ],
      children: [
        _BirdTimePanel(
          clock: clock,
          nextAlarm: nextAlarm,
          nextAlarmAt: nextAlarmAt,
        ),
        const SizedBox(height: 24),
        const _SectionLabel('我的闹钟'),
        if (alarms.isEmpty)
          const _EmptyHint(
            icon: Icons.alarm_add_outlined,
            text: '还没有闹钟，点右上角「+」新建一个。',
          ),
        for (final alarm in alarms)
          _AlarmTile(
            alarm: alarm,
            onChanged: (enabled) => onAlarmEnabledChanged(alarm, enabled),
            onTap: () => onEditAlarm(alarm),
            onDelete: () => onDeleteAlarm(alarm),
          ),
        if (alarms.isNotEmpty)
          const Padding(
            padding: EdgeInsets.only(top: 6, left: 6),
            child: Text(
              '点闹钟可修改，长按可删除。',
              style: TextStyle(fontSize: 12, color: Color(0xFF8A8A8E)),
            ),
          ),
      ],
    );
  }
}

// ────────────────────────────── 通用 UI 构件 ──────────────────────────────

/// 给 PageView 的每一页保活。PageView 会销毁滑出缓存区的页面，不保活的话切回来
/// 滚动位置、搜索框内容、展开状态全没了（换成 PageView 之前用 IndexedStack 不用管这个）。
class _KeepAlivePage extends StatefulWidget {
  final Widget child;

  const _KeepAlivePage({required this.child});

  @override
  State<_KeepAlivePage> createState() => _KeepAlivePageState();
}

class _KeepAlivePageState extends State<_KeepAlivePage>
    with AutomaticKeepAliveClientMixin {
  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return widget.child;
  }
}

/// 悬浮底栏：毛玻璃胶囊 + 选中项高亮。用 [Scaffold.extendBody] 让内容从它下面穿过去，
/// 各页的滚动内容底部留够 [_kFloatingBarInset] 的空白，最后一条不会被压住。
const double _kFloatingBarInset = 108;

/// 判定「双击」的时间窗口。星期格与分段控件都自己按时间戳判双击，好让单击零延迟生效。
const Duration _kDoubleTapWindow = Duration(milliseconds: 320);

class _FloatingTabBar extends StatelessWidget {
  final int selectedIndex;
  final ValueChanged<int> onSelected;

  const _FloatingTabBar({
    required this.selectedIndex,
    required this.onSelected,
  });

  static const _items = <({IconData icon, IconData activeIcon, String label})>[
    (icon: Icons.alarm_outlined, activeIcon: Icons.alarm, label: '闹钟'),
    (
      icon: Icons.graphic_eq_outlined,
      activeIcon: Icons.graphic_eq,
      label: '鸟鸣',
    ),
    (icon: Icons.settings_outlined, activeIcon: Icons.settings, label: '设置'),
    (icon: Icons.info_outline, activeIcon: Icons.info, label: '关于'),
  ];

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final light = theme.brightness == Brightness.light;
    return SafeArea(
      top: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(28),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
            child: Container(
              height: 62,
              decoration: BoxDecoration(
                color: (light ? Colors.white : const Color(0xFF1C1C1E))
                    .withValues(alpha: light ? 0.72 : 0.78),
                borderRadius: BorderRadius.circular(28),
                border: Border.all(
                  color:
                      light ? const Color(0x14000000) : const Color(0x1FFFFFFF),
                ),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: light ? 0.10 : 0.35),
                    blurRadius: 24,
                    offset: const Offset(0, 8),
                  ),
                ],
              ),
              // 透明 Material：让 InkWell 的水波纹画在这层胶囊里，而不是穿到底下页面的
              // Material 上（那样点击反馈会被半透明底盖住、看着没反应）。
              child: Material(
                type: MaterialType.transparency,
                child: Row(
                  children: [
                    for (var index = 0; index < _items.length; index++)
                      Expanded(
                        child: _FloatingTabItem(
                          item: _items[index],
                          selected: index == selectedIndex,
                          onTap: () => onSelected(index),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _FloatingTabItem extends StatelessWidget {
  final ({IconData icon, IconData activeIcon, String label}) item;
  final bool selected;
  final VoidCallback onTap;

  const _FloatingTabItem({
    required this.item,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color =
        selected
            ? theme.colorScheme.primary
            : theme.colorScheme.onSurfaceVariant;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(24),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          AnimatedScale(
            scale: selected ? 1.08 : 1,
            duration: const Duration(milliseconds: 180),
            curve: Curves.easeOutBack,
            child: Icon(
              selected ? item.activeIcon : item.icon,
              size: 23,
              color: color,
            ),
          ),
          const SizedBox(height: 3),
          Text(
            item.label,
            style: TextStyle(
              fontSize: 11,
              height: 1,
              color: color,
              fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
            ),
          ),
        ],
      ),
    );
  }
}

/// iOS 大标题页容器：标题随滚动收起，内容统一 20 边距、底部给悬浮底栏留白。
class _LargeTitleScrollView extends StatelessWidget {
  final String title;
  final List<Widget> actions;
  final List<Widget> children;
  // Tab 页底部要给悬浮底栏让位；推入的独立页面没有底栏，留一点安全边距就够。
  final bool floatingBarBelow;

  const _LargeTitleScrollView({
    required this.title,
    required this.children,
    this.actions = const [],
    this.floatingBarBelow = true,
  });

  @override
  Widget build(BuildContext context) {
    return CustomScrollView(
      slivers: [
        SliverAppBar.large(
          title: Text(title),
          actions: [...actions, const SizedBox(width: 8)],
          expandedHeight: 116,
        ),
        SliverPadding(
          padding: EdgeInsets.fromLTRB(
            20,
            0,
            20,
            floatingBarBelow ? _kFloatingBarInset : 32,
          ),
          sliver: SliverList(delegate: SliverChildListDelegate(children)),
        ),
      ],
    );
  }
}

/// 导航栏上的圆形动作按钮（iOS 那种淡色圆底 + 图标）。
class _CircleActionButton extends StatelessWidget {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  const _CircleActionButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: tooltip,
      child: Semantics(
        button: true,
        label: tooltip,
        child: Material(
          color: scheme.primary.withValues(alpha: 0.12),
          shape: const CircleBorder(),
          child: InkWell(
            onTap: onPressed,
            customBorder: const CircleBorder(),
            child: SizedBox(
              width: 38,
              height: 38,
              child: Icon(icon, size: 22, color: scheme.primary),
            ),
          ),
        ),
      ),
    );
  }
}

/// 分组小标题（iOS 设置里那种灰色小字）。
class _SectionLabel extends StatelessWidget {
  final String text;

  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(6, 4, 6, 8),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.2,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}

/// iOS 分组卡片：子项之间自动插入内缩分隔线。
class _GroupedCard extends StatelessWidget {
  final List<Widget> children;

  const _GroupedCard({required this.children});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).cardTheme.color,
        borderRadius: BorderRadius.circular(20),
      ),
      clipBehavior: Clip.antiAlias,
      // 透明 Material：卡片里的 InkWell / IconButton 水波纹要画在卡片上，
      // 否则会画到下层 Scaffold 的 Material 上、被卡片底色盖住。
      child: Material(
        type: MaterialType.transparency,
        child: Column(
          children: [
            for (var index = 0; index < children.length; index++) ...[
              if (index > 0)
                const Padding(
                  padding: EdgeInsets.only(left: 16),
                  child: Divider(),
                ),
              children[index],
            ],
          ],
        ),
      ),
    );
  }
}

/// 分组卡片里的一行：左图标 + 标题/副标题 + 右侧控件（开关、箭头、说明文字）。
class _SettingsRow extends StatelessWidget {
  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  const _SettingsRow({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Row(
          children: [
            Icon(icon, size: 22, color: theme.colorScheme.primary),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  if (subtitle != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      subtitle!,
                      style: TextStyle(
                        fontSize: 12.5,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 12), trailing!],
          ],
        ),
      ),
    );
  }
}

/// 自绘的分段控件（iOS 那种滑块式）。刻意不用 [SegmentedButton]：它选中后会多出一个对勾，
/// 宽度随之变化、把后面的分段挤走——本项目吃过这个亏。这里每段等宽（Expanded），
/// 选中与否只换底色和字重，尺寸恒定。
class _SegmentedPicker<T> extends StatefulWidget {
  final List<({T value, String label})> segments;
  final T selected;
  final ValueChanged<T> onChanged;
  // 双击某一段的回调（自定义重复里用来「一键全选」）。
  final ValueChanged<T>? onDoubleTap;

  const _SegmentedPicker({
    required this.segments,
    required this.selected,
    required this.onChanged,
    this.onDoubleTap,
  });

  @override
  State<_SegmentedPicker<T>> createState() => _SegmentedPickerState<T>();
}

class _SegmentedPickerState<T> extends State<_SegmentedPicker<T>> {
  DateTime? _lastTapAt;
  T? _lastTapValue;

  // 双击自己按时间戳判，而不是交给 GestureDetector.onDoubleTap：后者会让**单击**一直等到
  // 双击超时（约 300ms）才生效，点一下要顿一下。这里单击立刻生效，紧接着的第二下再当双击处理。
  void _handleTap(T value) {
    final now = DateTime.now();
    final isDoubleTap =
        widget.onDoubleTap != null &&
        _lastTapValue == value &&
        _lastTapAt != null &&
        now.difference(_lastTapAt!) < _kDoubleTapWindow;
    if (isDoubleTap) {
      _lastTapAt = null;
      _lastTapValue = null;
      widget.onDoubleTap!(value);
      return;
    }
    _lastTapAt = now;
    _lastTapValue = value;
    widget.onChanged(value);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final light = theme.brightness == Brightness.light;
    final selected = widget.selected;
    return Container(
      height: 38,
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
        color: light ? const Color(0xFFEDE7D6) : const Color(0xFF2C2C2E),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          for (final segment in widget.segments)
            Expanded(
              child: GestureDetector(
                onTap: () => _handleTap(segment.value),
                behavior: HitTestBehavior.opaque,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 160),
                  curve: Curves.easeOut,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color:
                        segment.value == selected
                            ? (light ? Colors.white : const Color(0xFF48484A))
                            : Colors.transparent,
                    borderRadius: BorderRadius.circular(9),
                    boxShadow:
                        segment.value == selected
                            ? [
                              BoxShadow(
                                color: Colors.black.withValues(alpha: 0.10),
                                blurRadius: 6,
                                offset: const Offset(0, 2),
                              ),
                            ]
                            : null,
                  ),
                  child: Text(
                    segment.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13.5,
                      fontWeight:
                          segment.value == selected
                              ? FontWeight.w600
                              : FontWeight.w500,
                      color:
                          segment.value == selected
                              ? theme.colorScheme.onSurface
                              : theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _EmptyHint extends StatelessWidget {
  final IconData icon;
  final String text;

  const _EmptyHint({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
      decoration: BoxDecoration(
        color: theme.cardTheme.color,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(
        children: [
          Icon(icon, size: 30, color: theme.colorScheme.onSurfaceVariant),
          const SizedBox(height: 10),
          Text(
            text,
            textAlign: TextAlign.center,
            style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

class AlarmEditor extends StatefulWidget {
  final BirdAlarm? alarm;

  const AlarmEditor({super.key, this.alarm});

  @override
  State<AlarmEditor> createState() => _AlarmEditorState();
}

class _AlarmEditorState extends State<AlarmEditor> {
  late TimeOfDay _time;
  late Set<int> _days;
  late RepeatRule _rule;
  late TextEditingController _labelController;
  // 星期格的双击判定：记住上一下点了哪天、什么时候点的、以及点之前的选择。
  DateTime? _lastDayTapAt;
  int? _lastDayTapped;
  Set<int> _daysBeforeLastTap = const {};

  @override
  void initState() {
    super.initState();
    _time = widget.alarm?.time ?? TimeOfDay.now();
    _days = {...?widget.alarm?.repeatDays};
    _rule = widget.alarm?.repeatRule ?? RepeatRule.weekdays;
    _labelController = TextEditingController(
      text: widget.alarm?.label ?? '鸟鸣唤醒',
    );
  }

  @override
  void dispose() {
    _labelController.dispose();
    super.dispose();
  }

  // 双击「自定义」分段或任意一天：一键全选；本来就是全选则清空（再点回来很方便）。
  // 起因是逐天点七下太烦——「每天响」是最常用的组合，值得一个快捷手势。
  // [previousDays] 传的是「这次连击的第一下之前」的选择：第一下单击已经改过一次 _days，
  // 直接看当前状态会误判（全选时双击第一下先减掉一天，就永远清不掉了）。
  void _toggleAllDays([Set<int>? previousDays]) {
    final base = previousDays ?? _days;
    setState(() {
      _rule = RepeatRule.weekdays;
      _days = base.length == 7 ? <int>{} : {1, 2, 3, 4, 5, 6, 7};
    });
  }

  // 星期格的点击：单击立刻切换这一天；320ms 内点到同一天算双击 → 全选/取消全选。
  void _handleDayTap(int day) {
    final now = DateTime.now();
    final lastAt = _lastDayTapAt;
    if (_lastDayTapped == day &&
        lastAt != null &&
        now.difference(lastAt) < _kDoubleTapWindow) {
      _lastDayTapAt = null;
      _lastDayTapped = null;
      _toggleAllDays(_daysBeforeLastTap);
      return;
    }
    _lastDayTapAt = now;
    _lastDayTapped = day;
    _daysBeforeLastTap = {..._days};
    setState(() {
      _days.contains(day) ? _days.remove(day) : _days.add(day);
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          20,
          4,
          20,
          MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // iOS 弹窗的头：取消 / 标题 / 保存。
              Row(
                children: [
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('取消'),
                  ),
                  Expanded(
                    child: Text(
                      widget.alarm == null ? '新闹钟' : '编辑闹钟',
                      textAlign: TextAlign.center,
                      style: theme.textTheme.titleMedium,
                    ),
                  ),
                  TextButton(
                    onPressed: _submit,
                    child: const Text(
                      '保存',
                      style: TextStyle(fontWeight: FontWeight.w700),
                    ),
                  ),
                ],
              ),
              // 时间直接用滚轮，少一次弹窗（iOS 闹钟就是这样）。
              SizedBox(
                height: 172,
                child: CupertinoDatePicker(
                  mode: CupertinoDatePickerMode.time,
                  use24hFormat: true,
                  minuteInterval: 1,
                  initialDateTime: DateTime(
                    2026,
                    1,
                    1,
                    _time.hour,
                    _time.minute,
                  ),
                  onDateTimeChanged:
                      (value) =>
                          _time = TimeOfDay(
                            hour: value.hour,
                            minute: value.minute,
                          ),
                ),
              ),
              const SizedBox(height: 12),
              const _SectionLabel('重复'),
              _SegmentedPicker<RepeatRule>(
                segments: const [
                  (value: RepeatRule.weekdays, label: '自定义'),
                  (value: RepeatRule.chinaWorkdays, label: '工作日'),
                  (value: RepeatRule.chinaHolidays, label: '节假日'),
                ],
                selected: _rule,
                onChanged: (value) => setState(() => _rule = value),
                onDoubleTap: (value) {
                  if (value == RepeatRule.weekdays) _toggleAllDays();
                },
              ),
              const SizedBox(height: 14),
              if (_rule == RepeatRule.weekdays) ...[
                _WeekdayPicker(selected: _days, onTapDay: _handleDayTap),
                const SizedBox(height: 8),
                Text(
                  _days.isEmpty
                      ? '一天都没选 = 只响一次。双击任意一天可一键全选。'
                      : '双击任意一天可一键全选 / 取消全选。',
                  style: TextStyle(
                    fontSize: 12,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ] else
                Text(
                  _rule == RepeatRule.chinaWorkdays
                      ? '仅工作日响铃：周末与法定节假日不响，含调休补班日。'
                      : '休息日响铃：周末和法定节假日都响，调休补班日不响。',
                  style: TextStyle(
                    fontSize: 12,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              const SizedBox(height: 18),
              const _SectionLabel('标签'),
              TextField(
                controller: _labelController,
                textInputAction: TextInputAction.done,
                decoration: const InputDecoration(hintText: '例如：晨跑、上班'),
              ),
              const SizedBox(height: 20),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _submit,
                  child: const Text('保存闹钟'),
                ),
              ),
              // 改已有闹钟时给一个删除入口：卡片长按也能删，但从编辑页里删更好找。
              if (widget.alarm != null) ...[
                const SizedBox(height: 8),
                SizedBox(
                  width: double.infinity,
                  child: TextButton.icon(
                    onPressed: _requestDelete,
                    style: TextButton.styleFrom(
                      foregroundColor: theme.colorScheme.error,
                    ),
                    icon: const Icon(Icons.delete_outline, size: 20),
                    label: const Text('删除闹钟'),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _requestDelete() async {
    final alarm = widget.alarm;
    if (alarm == null) return;
    final confirmed = await showDeleteAlarmDialog(context, alarm);
    if (!confirmed || !mounted) return;
    Navigator.of(context).pop(const AlarmEditorResult.deleted());
  }

  void _submit() {
    Navigator.of(context).pop(
      AlarmEditorResult(
        alarm: BirdAlarm(
          id:
              widget.alarm?.id ??
              DateTime.now().microsecondsSinceEpoch.toString(),
          time: _time,
          repeatDays: _days,
          repeatRule: _rule,
          enabled: widget.alarm?.enabled ?? true,
          label:
              _labelController.text.trim().isEmpty
                  ? '鸟鸣唤醒'
                  : _labelController.text.trim(),
        ),
      ),
    );
  }
}

/// 编辑弹窗的返回值：保存则带回闹钟，删除则 [delete] 为 true（弹窗被划掉时返回 null）。
class AlarmEditorResult {
  final BirdAlarm? alarm;
  final bool delete;

  const AlarmEditorResult({required this.alarm}) : delete = false;

  const AlarmEditorResult.deleted() : alarm = null, delete = true;
}

/// 星期选择：七格等宽、高度固定（46）。选中只换底色与字重，**尺寸恒定**——
/// 旧版用 FilterChip，选中后多出一个对勾把后面的格子挤到下一行，就是为了修这个。
/// 双击任意一格 = 全选 / 取消全选。
class _WeekdayPicker extends StatelessWidget {
  final Set<int> selected;
  // 单击/双击的区分交给调用方（_AlarmEditorState._handleDayTap）按时间戳判，
  // 这样单击不用等双击超时、点一下立刻有反应。
  final ValueChanged<int> onTapDay;

  const _WeekdayPicker({required this.selected, required this.onTapDay});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final light = theme.brightness == Brightness.light;
    return Row(
      children: [
        for (var day = 1; day <= 7; day++)
          // 每格都用相同的对称内边距：只给前六格加右边距的话，第七格会比别人宽 6px。
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3),
              child: GestureDetector(
                onTap: () => onTapDay(day),
                behavior: HitTestBehavior.opaque,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  height: 46,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color:
                        selected.contains(day)
                            ? theme.colorScheme.primary
                            : (light
                                ? const Color(0xFFEDE7D6)
                                : const Color(0xFF2C2C2E)),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Text(
                    _weekdayLabel(day),
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight:
                          selected.contains(day)
                              ? FontWeight.w700
                              : FontWeight.w500,
                      color:
                          selected.contains(day)
                              ? theme.colorScheme.onPrimary
                              : theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

/// 首页顶部的报时卡。以前是「亮黄渐变 + 一整块白色内卡」，深色模式下等于在黑底上贴一张
/// 大白纸，夜里很晃眼；而且两层卡片套着、插画占了一半高度，把闹钟列表挤到屏幕下半截。
/// 现在改成单层卡片：时间是主角，插画缩到右侧，深色模式换夜色渐变 + 浅色文字。
class _BirdTimePanel extends StatelessWidget {
  final ValueListenable<DateTime> clock;
  final String nextAlarm;
  final DateTime? nextAlarmAt;

  const _BirdTimePanel({
    required this.clock,
    required this.nextAlarm,
    required this.nextAlarmAt,
  });

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    // 卡片底色是固定渐变（不是主题色），所以卡片里的文字也用固定色——用主题色会在
    // 某一种模式下糊在底色上看不清（这块以前就踩过）。
    final onPanel = dark ? const Color(0xFFE9F5F1) : const Color(0xFF164A45);
    final onPanelMuted =
        dark ? const Color(0xFF9FC6BE) : const Color(0xFF3C5A54);
    return ClipRRect(
      borderRadius: BorderRadius.circular(24),
      child: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors:
                dark
                    ? const [Color(0xFF0F2C29), Color(0xFF1B4741)]
                    : const [Color(0xFFFFD983), Color(0xFFFFF0C2)],
          ),
        ),
        child: Stack(
          children: [
            Positioned.fill(
              child: CustomPaint(painter: _SkyPatternPainter(dark: dark)),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 16, 18),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Expanded(
                    child: ValueListenableBuilder<DateTime>(
                      valueListenable: clock,
                      // 只有时间文字随时钟每秒重建，渐变 / 插画 / 天空图案不重绘。
                      builder:
                          (context, now, _) => _TimeSummary(
                            now: now,
                            nextAlarm: nextAlarm,
                            nextAlarmAt: nextAlarmAt,
                            onPanel: onPanel,
                            onPanelMuted: onPanelMuted,
                            dark: dark,
                          ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  SizedBox(
                    width: 96,
                    height: 104,
                    child: CustomPaint(
                      painter: _CartoonClockBirdPainter(dark: dark),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _TimeSummary extends StatelessWidget {
  final DateTime now;
  final String nextAlarm;
  final DateTime? nextAlarmAt;
  final Color onPanel;
  final Color onPanelMuted;
  final bool dark;

  const _TimeSummary({
    required this.now,
    required this.nextAlarm,
    required this.nextAlarmAt,
    required this.onPanel,
    required this.onPanelMuted,
    required this.dark,
  });

  @override
  Widget build(BuildContext context) {
    final countdown = countdownText(now, nextAlarmAt);
    final timeText =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          timeText,
          style: TextStyle(
            fontSize: 52,
            height: 1.05,
            fontWeight: FontWeight.w300,
            letterSpacing: -1.5,
            color: onPanel,
            fontFeatures: const [FontFeature.tabularFigures()],
          ),
        ),
        const SizedBox(height: 2),
        Text(
          '${now.month} 月 ${now.day} 日 周${_weekdayLabel(now.weekday)}',
          style: TextStyle(fontSize: 13, color: onPanelMuted),
        ),
        const SizedBox(height: 12),
        // 「下一次唤醒」用半透明胶囊压在渐变上，不再是一整块白色内卡。
        Container(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          decoration: BoxDecoration(
            color: Colors.white.withValues(alpha: dark ? 0.10 : 0.55),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color:
                  dark
                      ? Colors.white.withValues(alpha: 0.12)
                      : const Color(0x805CAAA0),
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.alarm_on_outlined, size: 18, color: onPanel),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 倒计时当主行：它才是「还要睡多久」的答案，而且短，不会把这行撑爆。
                    // 具体是哪一个闹钟放在下面一行，长了就省略号——之前两截塞一行会 overflow。
                    Text(
                      countdown ?? '下一次唤醒',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: onPanel,
                      ),
                    ),
                    const SizedBox(height: 1),
                    Text(
                      nextAlarm,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 11.5, color: onPanelMuted),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// 「还有 8 小时 12 分」这种倒计时文案。只在 App 内显示——通知里只有响铃前 10 分钟
/// 那条倒计时，不想为了看个剩余时间就整夜挂一条常驻通知。
/// 返回 null 表示没有启用的闹钟（或时刻已过），这时不显示这一段。
String? countdownText(DateTime now, DateTime? target) {
  if (target == null) return null;
  final remaining = target.difference(now);
  if (remaining.isNegative) return null;
  final days = remaining.inDays;
  final hours = remaining.inHours % 24;
  final minutes = remaining.inMinutes % 60;
  if (days > 0) {
    return hours > 0 ? '还有 $days 天 $hours 小时' : '还有 $days 天';
  }
  if (remaining.inHours > 0) {
    return minutes > 0
        ? '还有 ${remaining.inHours} 小时 $minutes 分'
        : '还有 ${remaining.inHours} 小时';
  }
  if (remaining.inMinutes > 0) return '还有 $minutes 分钟';
  return '不到 1 分钟';
}

/// 报时卡背景的云与山丘。深色模式下压到几乎看不见的低对比度——原来的亮绿山丘
/// 在夜色渐变上会变成一条刺眼的亮边。
class _SkyPatternPainter extends CustomPainter {
  final bool dark;

  const _SkyPatternPainter({required this.dark});

  @override
  void paint(Canvas canvas, Size size) {
    final cloud =
        Paint()..color = Colors.white.withValues(alpha: dark ? 0.05 : 0.36);
    canvas.drawOval(
      Rect.fromCenter(
        center: Offset(size.width * 0.16, size.height * 0.18),
        width: 90,
        height: 34,
      ),
      cloud,
    );
    canvas.drawOval(
      Rect.fromCenter(
        center: Offset(size.width * 0.84, size.height * 0.22),
        width: 120,
        height: 42,
      ),
      cloud,
    );
    final hill =
        Paint()
          ..color = dark ? const Color(0xFF16413A) : const Color(0xFFB8D98B);
    final path =
        Path()
          ..moveTo(0, size.height)
          ..quadraticBezierTo(
            size.width * 0.32,
            size.height * 0.72,
            size.width * 0.62,
            size.height * 0.86,
          )
          ..quadraticBezierTo(
            size.width * 0.82,
            size.height * 0.95,
            size.width,
            size.height * 0.78,
          )
          ..lineTo(size.width, size.height)
          ..close();
    canvas.drawPath(path, hill);
  }

  @override
  bool shouldRepaint(covariant _SkyPatternPainter oldDelegate) =>
      oldDelegate.dark != dark;
}

/// 卡通报时鸟。深色模式下只换描边色（原来的深棕描边在夜色底上会整个糊掉，
/// 鸟就没轮廓了）；身体、肚子、喙这些主体色保留，深浅两版都认得出是同一只鸟。
class _CartoonClockBirdPainter extends CustomPainter {
  final bool dark;

  const _CartoonClockBirdPainter({this.dark = false});

  @override
  void paint(Canvas canvas, Size size) {
    final scale = size.shortestSide / 180;
    canvas.save();
    canvas.scale(scale, scale);
    final dx = (size.width / scale - 180) / 2;
    final dy = (size.height / scale - 180) / 2;
    canvas.translate(dx, dy);

    final outline =
        Paint()
          ..color = dark ? const Color(0xFFE4EFE9) : const Color(0xFF3C3324)
          ..style = PaintingStyle.stroke
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round
          ..strokeWidth = 4;
    final body = Paint()..color = const Color(0xFF1D9A8A);
    final belly = Paint()..color = const Color(0xFFFFEFBD);
    final wing = Paint()..color = const Color(0xFF126D68);
    final beak = Paint()..color = const Color(0xFFFFA13D);
    final blush = Paint()..color = const Color(0xFFFF9BA6);
    // 眼睛与钟面刻度用的深色点（别叫 dark，会和上面的 dark 字段撞名）。
    final inkDot = Paint()..color = const Color(0xFF2B251D);
    final branch =
        Paint()
          ..color = const Color(0xFF7B4E2D)
          ..strokeCap = StrokeCap.round
          ..strokeWidth = 9;

    canvas.drawLine(const Offset(18, 152), const Offset(164, 140), branch);
    canvas.drawLine(
      const Offset(108, 145),
      const Offset(142, 124),
      branch..strokeWidth = 5,
    );

    canvas.drawOval(const Rect.fromLTWH(48, 47, 88, 101), body);
    canvas.drawOval(const Rect.fromLTWH(67, 79, 53, 58), belly);
    canvas.drawOval(const Rect.fromLTWH(39, 83, 47, 38), wing);
    canvas.drawArc(
      const Rect.fromLTWH(39, 83, 47, 38),
      0.6,
      2.7,
      false,
      outline,
    );
    canvas.drawOval(const Rect.fromLTWH(48, 47, 88, 101), outline);

    final crest =
        Path()
          ..moveTo(78, 51)
          ..quadraticBezierTo(80, 27, 96, 47)
          ..quadraticBezierTo(103, 27, 110, 51);
    canvas.drawPath(crest, body);
    canvas.drawPath(crest, outline);

    final beakPath =
        Path()
          ..moveTo(130, 77)
          ..lineTo(162, 87)
          ..lineTo(130, 96)
          ..close();
    canvas.drawPath(beakPath, beak);
    canvas.drawPath(beakPath, outline);

    canvas.drawCircle(const Offset(99, 76), 7, inkDot);
    canvas.drawCircle(
      const Offset(102, 73),
      2.2,
      Paint()..color = Colors.white,
    );
    canvas.drawCircle(const Offset(116, 94), 6, blush);

    canvas.drawLine(const Offset(72, 145), const Offset(67, 158), outline);
    canvas.drawLine(const Offset(107, 146), const Offset(111, 158), outline);

    final clockFace = Paint()..color = Colors.white;
    canvas.drawCircle(const Offset(46, 55), 25, clockFace);
    canvas.drawCircle(const Offset(46, 55), 25, outline);
    canvas.drawLine(const Offset(46, 55), const Offset(46, 40), outline);
    canvas.drawLine(const Offset(46, 55), const Offset(58, 61), outline);
    for (final point in const [
      Offset(46, 34),
      Offset(67, 55),
      Offset(46, 76),
      Offset(25, 55),
    ]) {
      canvas.drawCircle(point, 2, inkDot);
    }

    final notes =
        Paint()
          ..color = const Color(0xFF245B8F)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 3
          ..strokeCap = StrokeCap.round;
    canvas.drawLine(const Offset(147, 36), const Offset(147, 55), notes);
    canvas.drawCircle(const Offset(141, 56), 5, notes);
    canvas.drawLine(const Offset(151, 36), const Offset(163, 32), notes);
    canvas.drawLine(const Offset(28, 20), const Offset(28, 38), notes);
    canvas.drawCircle(const Offset(23, 39), 5, notes);

    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _CartoonClockBirdPainter oldDelegate) =>
      oldDelegate.dark != dark;
}

/// 闹钟卡片：大号时间 + 标签/重复的次要行 + 开关（Material 开关，跟系统一致）。
/// 删除走**长按**弹确认框，刻意不用左滑：整页要留给左右滑动切 Tab，左滑删除会跟它抢手势
/// ——手指落在卡片上一划就变成拖删除条，翻页翻不动。编辑弹窗里也有一个删除入口。
class _AlarmTile extends StatelessWidget {
  final BirdAlarm alarm;
  final ValueChanged<bool> onChanged;
  final VoidCallback onTap;
  final VoidCallback onDelete;

  const _AlarmTile({
    required this.alarm,
    required this.onChanged,
    required this.onTap,
    required this.onDelete,
  });

  Future<void> _confirmDelete(BuildContext context) async {
    final confirmed = await showDeleteAlarmDialog(context, alarm);
    if (confirmed) onDelete();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Material(
        color: theme.cardTheme.color,
        borderRadius: BorderRadius.circular(20),
        child: InkWell(
          onTap: onTap,
          onLongPress: () => _confirmDelete(context),
          borderRadius: BorderRadius.circular(20),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 16, 16),
            child: Opacity(
              opacity: alarm.enabled ? 1 : 0.45,
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          alarm.time.format(context),
                          style: theme.textTheme.displaySmall?.copyWith(
                            fontWeight: FontWeight.w300,
                            letterSpacing: -1,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '${alarm.label} · ${_repeatText(alarm)}',
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Switch(value: alarm.enabled, onChanged: onChanged),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// 删除闹钟的二次确认（半睡半醒时容易误触，值得多问一句）。长按卡片和编辑页共用。
Future<bool> showDeleteAlarmDialog(
  BuildContext context,
  BirdAlarm alarm,
) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder:
        (context) => AlertDialog(
          title: const Text('删除闹钟'),
          content: Text(
            '确定删除 ${alarm.time.format(context)} 的「${alarm.label}」吗？',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('取消'),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              style: TextButton.styleFrom(
                foregroundColor: Theme.of(context).colorScheme.error,
              ),
              child: const Text('删除'),
            ),
          ],
        ),
  );
  return confirmed ?? false;
}

class _LibraryPanel extends StatelessWidget {
  final List<BirdSound> library;
  final List<BirdName> species;
  final DailyBird daily;
  final TextEditingController controller;
  final TextEditingController speciesSearchController;
  final bool searching;
  final Set<String> downloadingIds;
  final Map<String, double?> downloadProgress;
  final String? previewingSoundId;
  final List<BirdSound> results;
  final VoidCallback onUpload;
  final VoidCallback onSearch;
  final ValueChanged<String> onSpeciesSearchChanged;
  final ValueChanged<BirdSound> onAdd;
  final ValueChanged<BirdName> onDownloadSpecies;
  final ValueChanged<BirdSound> onDownload;
  final ValueChanged<BirdSound> onPreview;

  const _LibraryPanel({
    required this.library,
    required this.species,
    required this.daily,
    required this.controller,
    required this.speciesSearchController,
    required this.searching,
    required this.downloadingIds,
    required this.downloadProgress,
    required this.previewingSoundId,
    required this.results,
    required this.onUpload,
    required this.onSearch,
    required this.onSpeciesSearchChanged,
    required this.onAdd,
    required this.onDownloadSpecies,
    required this.onDownload,
    required this.onPreview,
  });

  BirdSound? _soundFor(BirdName bird) =>
      library.where((sound) => sound.sciName == bird.sci).firstOrNull;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final star = daily.bird;
    final query = speciesSearchController.text.trim();
    return _LargeTitleScrollView(
      title: '鸟鸣库',
      actions: [
        _CircleActionButton(
          icon: Icons.upload_file_outlined,
          tooltip: '上传本地音频',
          onPressed: onUpload,
        ),
      ],
      children: [
        // 搜索框放在最上面。以前这下面还挂着一串「名录前 30 条」，不搜也一直杵在那儿、
        // 永远是同几只鸵鸟，既占地方又看着像坏了——现在不输入就什么都不显示。
        TextField(
          controller: speciesSearchController,
          textInputAction: TextInputAction.search,
          decoration: InputDecoration(
            hintText: '搜鸟种：中文 / 英文 / 拉丁名',
            prefixIcon: const Icon(Icons.search),
            suffixIcon:
                query.isEmpty
                    ? null
                    : IconButton(
                      tooltip: '清空',
                      icon: const Icon(Icons.close),
                      onPressed: () {
                        speciesSearchController.clear();
                        onSpeciesSearchChanged('');
                      },
                    ),
          ),
          onChanged: onSpeciesSearchChanged,
        ),
        if (query.isNotEmpty) ...[
          const SizedBox(height: 12),
          if (species.isEmpty)
            const _EmptyHint(icon: Icons.search_off, text: '没找到匹配的鸟种，换个词试试。')
          else
            _GroupedCard(
              children: [
                for (final bird in species)
                  _SpeciesDownloadTile(
                    bird: bird,
                    sound: _soundFor(bird),
                    downloading: downloadingIds.contains('species-${bird.sci}'),
                    progress: downloadProgress['species-${bird.sci}'],
                    previewing: _soundFor(bird)?.id == previewingSoundId,
                    onPreview: onPreview,
                    onDownload: onDownloadSpecies,
                  ),
              ],
            ),
        ],
        const SizedBox(height: 22),
        if (star != null) ...[
          const _SectionLabel('每日一鸟'),
          _DailyBirdCard(
            bird: star,
            sound: _soundFor(star),
            downloading: downloadingIds.contains('species-${star.sci}'),
            progress: downloadProgress['species-${star.sci}'],
            previewing: _soundFor(star)?.id == previewingSoundId,
            onPreview: onPreview,
            onDownload: onDownloadSpecies,
          ),
          const SizedBox(height: 22),
        ],
        const _SectionLabel('xeno-canto 高级查询'),
        Container(
          decoration: BoxDecoration(
            color: theme.cardTheme.color,
            borderRadius: BorderRadius.circular(20),
          ),
          clipBehavior: Clip.antiAlias,
          child: Material(
            type: MaterialType.transparency,
            child: ExpansionTile(
              shape: const Border(),
              collapsedShape: const Border(),
              title: const Text('按条件搜索录音', style: TextStyle(fontSize: 15)),
              leading: const Icon(Icons.travel_explore),
              childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              children: [
                TextField(
                  controller: controller,
                  decoration: const InputDecoration(
                    hintText: '例如：cnt:China q:A 或 gen:Turdus sp:merula',
                  ),
                  onSubmitted: (_) => onSearch(),
                ),
                const SizedBox(height: 10),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton.icon(
                    onPressed: searching ? null : onSearch,
                    icon:
                        searching
                            ? const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                            : const Icon(Icons.search),
                    label: Text(searching ? '搜索中…' : '搜索 xeno-canto'),
                  ),
                ),
              ],
            ),
          ),
        ),
        if (results.isNotEmpty) ...[
          const SizedBox(height: 22),
          const _SectionLabel('搜索结果'),
          _GroupedCard(
            children: [
              for (final result in results)
                _SoundTile(
                  sound: result,
                  previewing: previewingSoundId == result.id,
                  downloading: downloadingIds.contains(result.id),
                  progress: downloadProgress[result.id],
                  onPreview: onPreview,
                  onDownload: onDownload,
                  onAdd: onAdd,
                ),
            ],
          ),
        ],
        const SizedBox(height: 22),
        _SectionLabel('当前音库 · ${library.length} 条'),
        _GroupedCard(
          children: [
            // 下载/自定义的鸟鸣排在内置前面，且不截断，确保下载后一定能在音库里看到。
            for (final sound in [
              ...library.where((sound) => !sound.id.startsWith('starter-')),
              ...library.where((sound) => sound.id.startsWith('starter-')),
            ])
              _SoundTile(
                sound: sound,
                previewing: previewingSoundId == sound.id,
                downloading: downloadingIds.contains(sound.id),
                progress: downloadProgress[sound.id],
                onPreview: onPreview,
                onDownload: onDownload,
              ),
          ],
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(6, 8, 6, 0),
          child: Text(
            '能离线播放的鸟鸣都在闹钟的随机抽取池里；下载后的 xeno-canto 鸟鸣也算。',
            style: TextStyle(fontSize: 12, color: Color(0xFF8A8A8E)),
          ),
        ),
      ],
    );
  }
}

/// 「每日一鸟」卡片：当天固定的一只鸟 + 一张大图（iNaturalist / Wikimedia Commons，
/// 都是 CC 授权，卡片底部按许可证要求标出作者）。已经在音库里就能直接试听，
/// 没有就一键从 xeno-canto 下一条回来。取不到图时退回渐变底 + 卡通鸟，尺寸不变。
class _DailyBirdCard extends StatefulWidget {
  final BirdName bird;
  final BirdSound? sound;
  final bool downloading;
  final double? progress;
  final bool previewing;
  final ValueChanged<BirdSound> onPreview;
  final ValueChanged<BirdName> onDownload;

  const _DailyBirdCard({
    required this.bird,
    required this.sound,
    required this.downloading,
    required this.progress,
    required this.previewing,
    required this.onPreview,
    required this.onDownload,
  });

  @override
  State<_DailyBirdCard> createState() => _DailyBirdCardState();
}

class _DailyBirdCardState extends State<_DailyBirdCard> {
  BirdPhoto? _photo;
  File? _photoFile;
  BirdPhotoStatus _photoStatus = BirdPhotoStatus.failed;
  bool _loadingPhoto = true;

  @override
  void initState() {
    super.initState();
    _loadPhoto();
  }

  @override
  void didUpdateWidget(_DailyBirdCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 过了零点换鸟时会走到这里。
    if (oldWidget.bird.sci != widget.bird.sci) _loadPhoto();
  }

  /// 查照片 + 把图下到本地。两步都可能失败，失败会明确显示出来并支持点一下重试，
  /// 而不是永远停在占位图上（这是上一版的毛病）。
  Future<void> _loadPhoto({bool forceRefresh = false}) async {
    if (mounted) {
      setState(() {
        _loadingPhoto = true;
        if (forceRefresh) {
          _photo = null;
          _photoFile = null;
        }
      });
    }
    final result = await BirdPhotos.forSpecies(
      widget.bird.sci,
      forceRefresh: forceRefresh,
    );
    final file =
        result.photo == null
            ? null
            : await BirdPhotos.imageFile(
              widget.bird.sci,
              result.photo!,
              forceRefresh: forceRefresh,
            );
    if (!mounted) return;
    setState(() {
      _photo = result.photo;
      _photoFile = file;
      // 查到了图但下不下来，也算失败（可重试），不然又是「永远转圈」。
      _photoStatus =
          result.status == BirdPhotoStatus.found && file == null
              ? BirdPhotoStatus.failed
              : result.status;
      _loadingPhoto = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final light = theme.brightness == Brightness.light;
    final bird = widget.bird;
    final playable = widget.sound?.playable == true;
    final photo = _photo;
    return Container(
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(24),
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors:
              light
                  ? const [Color(0xFF00C08F), Color(0xFF46E6BC)]
                  : const [Color(0xFF00654E), Color(0xFF00A87E)],
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: 190,
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (_photoFile != null)
                  Image.file(
                    _photoFile!,
                    fit: BoxFit.cover,
                    errorBuilder:
                        (context, error, stack) => _PhotoPlaceholder(
                          status: BirdPhotoStatus.failed,
                          onRetry: () => _loadPhoto(forceRefresh: true),
                        ),
                  )
                else
                  _PhotoPlaceholder(
                    loading: _loadingPhoto,
                    status: _photoStatus,
                    onRetry: () => _loadPhoto(forceRefresh: true),
                  ),
                // 图片下半部压暗，保证压在上面的鸟名读得清（照片亮暗不可控）。
                const DecoratedBox(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.center,
                      end: Alignment.bottomCenter,
                      colors: [Colors.transparent, Color(0xB3000000)],
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(18, 14, 18, 14),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          const Icon(
                            Icons.auto_awesome,
                            size: 16,
                            color: Colors.white,
                          ),
                          const SizedBox(width: 6),
                          Text(
                            '今天认识这只鸟',
                            style: TextStyle(
                              fontSize: 12.5,
                              color: Colors.white.withValues(alpha: 0.9),
                              shadows: const [
                                Shadow(blurRadius: 6, color: Color(0x99000000)),
                              ],
                            ),
                          ),
                        ],
                      ),
                      const Spacer(),
                      Text(
                        bird.display,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.w800,
                          letterSpacing: -0.5,
                          color: Colors.white,
                          shadows: [
                            Shadow(blurRadius: 8, color: Color(0xB3000000)),
                          ],
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${bird.en} · ${bird.sci}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.white.withValues(alpha: 0.88),
                          shadows: const [
                            Shadow(blurRadius: 6, color: Color(0x99000000)),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
            child: Row(
              children: [
                if (playable)
                  FilledButton.icon(
                    onPressed: () => widget.onPreview(widget.sound!),
                    style: _actionStyle,
                    icon: Icon(
                      widget.previewing ? Icons.pause : Icons.play_arrow,
                      size: 20,
                    ),
                    label: Text(widget.previewing ? '暂停' : '试听'),
                  )
                else
                  FilledButton.icon(
                    onPressed:
                        widget.downloading
                            ? null
                            : () => widget.onDownload(bird),
                    style: _actionStyle,
                    icon:
                        widget.downloading
                            ? SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                value: widget.progress,
                                color: const Color(0xFF00382B),
                              ),
                            )
                            : const Icon(Icons.download, size: 20),
                    label: Text(
                      widget.downloading
                          ? (widget.progress == null
                              ? '处理中…'
                              : '${(widget.progress! * 100).round()}%')
                          : '下载这只鸟',
                    ),
                  ),
                const SizedBox(width: 12),
                // 照片署名：CC 许可证要求标出作者与协议，别省。
                Expanded(
                  child: Text(
                    photo?.attribution ??
                        (_loadingPhoto
                            ? '正在找照片…'
                            : (_photoStatus == BirdPhotoStatus.failed
                                ? '照片加载失败'
                                : '暂无照片')),
                    textAlign: TextAlign.right,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 10.5,
                      height: 1.3,
                      color: Colors.white.withValues(alpha: 0.75),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  static final ButtonStyle _actionStyle = FilledButton.styleFrom(
    backgroundColor: Colors.white,
    foregroundColor: const Color(0xFF00382B),
    disabledBackgroundColor: Colors.white70,
    padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
  );
}

/// 没照片时的占位：半透明底 + 那只卡通鸟，尺寸与真图一致，卡片不会忽大忽小。
/// **失败要说出来并且能点一下重试**——只画一只卡通鸟的话，用户会以为图还在加载，
/// 干等十几分钟也等不到（上一版就是这样）。
class _PhotoPlaceholder extends StatelessWidget {
  final bool loading;
  final BirdPhotoStatus status;
  final VoidCallback? onRetry;

  const _PhotoPlaceholder({
    this.loading = false,
    this.status = BirdPhotoStatus.none,
    this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final failed = !loading && status == BirdPhotoStatus.failed;
    return Material(
      color: Colors.white.withValues(alpha: 0.10),
      child: InkWell(
        onTap: failed ? onRetry : null,
        child: Center(
          child:
              loading
                  ? const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white70,
                    ),
                  )
                  : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        width: 96,
                        height: 96,
                        child: CustomPaint(
                          painter: const _CartoonClockBirdPainter(dark: true),
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        failed ? '照片没加载出来，点一下重试' : '这只鸟暂时没有可用照片',
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.white.withValues(alpha: 0.85),
                          shadows: const [
                            Shadow(blurRadius: 6, color: Color(0x99000000)),
                          ],
                        ),
                      ),
                    ],
                  ),
        ),
      ),
    );
  }
}

/// 音库 / 搜索结果里的一条鸟鸣：试听 +（可下载时）下载，下载中显示真实进度。
class _SoundTile extends StatelessWidget {
  final BirdSound sound;
  final bool previewing;
  final bool downloading;
  final double? progress;
  final ValueChanged<BirdSound> onPreview;
  final ValueChanged<BirdSound> onDownload;
  final ValueChanged<BirdSound>? onAdd;

  const _SoundTile({
    required this.sound,
    required this.previewing,
    required this.downloading,
    required this.progress,
    required this.onPreview,
    required this.onDownload,
    this.onAdd,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      child: Row(
        children: [
          IconButton(
            tooltip: previewing ? '暂停' : '试听',
            onPressed: sound.playable ? () => onPreview(sound) : null,
            icon: Icon(
              previewing ? Icons.pause_circle : Icons.play_circle_outline,
              size: 28,
            ),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  sound.cnName,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${sound.enName}${sound.sciName.isEmpty ? '' : ' · ${sound.sciName}'}\n${sound.source}',
                  style: TextStyle(
                    fontSize: 11.5,
                    height: 1.35,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          if (onAdd != null)
            IconButton(
              tooltip: '加入音库',
              onPressed: () => onAdd!(sound),
              icon: const Icon(Icons.add_circle_outline),
            ),
          if (sound.url != null && sound.localPath == null)
            _DownloadButton(
              downloading: downloading,
              progress: progress,
              onDownload: () => onDownload(sound),
            ),
        ],
      ),
    );
  }
}

/// 下载按钮：闲时是下载图标，下载中变成带真实百分比的环形进度（进度未知时转圈）。
class _DownloadButton extends StatelessWidget {
  final bool downloading;
  final double? progress;
  final VoidCallback onDownload;

  const _DownloadButton({
    required this.downloading,
    required this.progress,
    required this.onDownload,
  });

  @override
  Widget build(BuildContext context) {
    if (!downloading) {
      return IconButton(
        tooltip: '下载到本机',
        onPressed: onDownload,
        icon: const Icon(Icons.download_for_offline_outlined),
      );
    }
    return SizedBox(
      width: 48,
      height: 48,
      child: Center(
        child: SizedBox(
          width: 22,
          height: 22,
          child: CircularProgressIndicator(strokeWidth: 2.4, value: progress),
        ),
      ),
    );
  }
}

class _SpeciesDownloadTile extends StatelessWidget {
  final BirdName bird;
  final BirdSound? sound;
  final bool downloading;
  final double? progress;
  final bool previewing;
  final ValueChanged<BirdSound> onPreview;
  final ValueChanged<BirdName> onDownload;

  const _SpeciesDownloadTile({
    required this.bird,
    required this.sound,
    required this.downloading,
    required this.progress,
    required this.previewing,
    required this.onPreview,
    required this.onDownload,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final downloaded = sound != null;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 12, 10),
      child: Row(
        children: [
          Icon(
            downloaded ? Icons.check_circle : Icons.radio_button_unchecked,
            size: 20,
            color:
                downloaded
                    ? theme.colorScheme.primary
                    : theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.5),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  bird.display,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  '${bird.en}\n${bird.sci}',
                  style: TextStyle(
                    fontSize: 11.5,
                    height: 1.35,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          if (downloaded)
            IconButton(
              tooltip: previewing ? '暂停' : '试听',
              onPressed:
                  sound?.playable == true ? () => onPreview(sound!) : null,
              icon: Icon(
                previewing ? Icons.pause_circle : Icons.play_circle_outline,
                size: 26,
              ),
            )
          else
            _DownloadButton(
              downloading: downloading,
              progress: progress,
              onDownload: () => onDownload(bird),
            ),
        ],
      ),
    );
  }
}

/// 「每日一鸟」的当日结果。按日期缓存，隔天重算；
/// day 为 null = 鸟名表还没加载好的临时空结果，不该被缓存。
class DailyBird {
  final DateTime? day;
  final BirdName? bird;

  const DailyBird({required this.day, required this.bird});
}

/// 按日期定随机种子挑「每日一鸟」：同一天永远是同一只，隔天自动换。
/// [names] 为空（鸟名表还没加载完）时返回 `day == null` 的空结果，调用方据此不要缓存。
/// 纯函数，方便直接测；UI 侧的缓存在 `_AlarmHomePageState._dailyBird`。
DailyBird pickDailyBird({
  required List<BirdName> names,
  required DateTime day,
}) {
  // 只挑有中文名的鸟种：名录里不少条目没中文名，推给用户看意义不大。
  final named = names.where((bird) => bird.cn.isNotEmpty).toList();
  if (named.isEmpty) return const DailyBird(day: null, bird: null);
  final random = Random(day.year * 10000 + day.month * 100 + day.day);
  return DailyBird(
    day: DateTime(day.year, day.month, day.day),
    bird: named[random.nextInt(named.length)],
  );
}

/// 一张鸟的照片：图片地址 + 署名（作者与许可证）。
/// 用的都是 CC 授权的图，**署名是许可证要求的**，别为了好看把它省掉。
class BirdPhoto {
  final String url;
  final String attribution;

  const BirdPhoto({required this.url, required this.attribution});

  factory BirdPhoto.fromJson(Map<String, dynamic> json) => BirdPhoto(
    url: json['url'] as String? ?? '',
    attribution: json['attribution'] as String? ?? '',
  );

  Map<String, dynamic> toJson() => {'url': url, 'attribution': attribution};
}

/// 查照片的结果。**「查失败」和「确实没有这只鸟的照片」必须分开**——
/// 早期版本把两者都当成「没有」写进缓存，于是第一次没网就永远不再重试、图再也出不来。
enum BirdPhotoStatus { found, none, failed }

class BirdPhotoResult {
  final BirdPhotoStatus status;
  final BirdPhoto? photo;

  const BirdPhotoResult(this.status, [this.photo]);
}

/// 按学名找一张鸟的照片。参考原作者的 Birdaholic（同一个人写的鸟类闪卡 App）：
/// 先问 **iNaturalist**（research-grade 观察记录，按点赞排序），没有再退到
/// **Wikimedia Commons** 的物种分类。两个都是公开接口，不需要 key。
///
/// 与 Birdaholic 的实现有两点不同：
/// 1. 只取 **CC 授权**的照片（`photo_license` 参数）——iNaturalist 上票数最高的
///    往往是 "All rights reserved"，直接拿来显示不合适。
/// 2. 查到之后**把图片本身也下载到本地**，下次直接读文件：手机网络下 CDN 可能很慢，
///    而 `Image.network` 没有超时、卡住就一直转，只能自己下、自己设超时。
class BirdPhotos {
  static const _prefix = 'bird_photo_';
  // 只要 CC 授权（含 CC0）；这串会作为 photo_license 参数发给 iNaturalist。
  static const _ccLicenses =
      'cc0,cc-by,cc-by-nc,cc-by-sa,cc-by-nc-sa,cc-by-nd,cc-by-nc-nd';
  // 「这只鸟确实没有照片」的结论也别记一辈子：物种照片会陆续被人补上。
  static const _negativeTtlMillis = 7 * 24 * 60 * 60 * 1000;
  static const _apiTimeout = Duration(seconds: 12);
  static const _imageTimeout = Duration(seconds: 25);
  static final Map<String, BirdPhoto> _memory = {};

  /// 只给测试用：清掉进程内的那层缓存，好让每个用例从干净状态开始。
  @visibleForTesting
  static void debugClearMemoryCache() => _memory.clear();

  /// 查这只鸟的照片。命中缓存直接返回；查询失败**不写缓存**，下次会重试。
  static Future<BirdPhotoResult> forSpecies(
    String sciName, {
    http.Client? client,
    bool forceRefresh = false,
  }) async {
    if (sciName.isEmpty) return const BirdPhotoResult(BirdPhotoStatus.none);
    if (!forceRefresh && _memory.containsKey(sciName)) {
      return BirdPhotoResult(BirdPhotoStatus.found, _memory[sciName]);
    }
    final prefs = await SharedPreferences.getInstance();
    final key = '$_prefix$sciName';
    if (forceRefresh) {
      _memory.remove(sciName);
      await prefs.remove(key);
    } else {
      final cached = _readCache(prefs, key);
      if (cached != null) {
        if (cached.photo != null) _memory[sciName] = cached.photo!;
        return cached;
      }
    }

    final http.Client httpClient = client ?? http.Client();
    try {
      final fromINaturalist = await _fromINaturalist(sciName, httpClient);
      var photo = fromINaturalist.photo;
      var failed = fromINaturalist.status == BirdPhotoStatus.failed;
      if (photo == null) {
        final fromCommons = await _fromWikimedia(sciName, httpClient);
        photo = fromCommons.photo;
        failed = failed || fromCommons.status == BirdPhotoStatus.failed;
      }
      if (photo != null) {
        _memory[sciName] = photo;
        await prefs.setString(
          key,
          jsonEncode({...photo.toJson(), 'ts': _nowMillis()}),
        );
        return BirdPhotoResult(BirdPhotoStatus.found, photo);
      }
      // 两个来源都正常应答、就是没有可用的图：记一条会过期的「没有」，别每次都重查。
      if (!failed) {
        await prefs.setString(
          key,
          jsonEncode({'none': true, 'ts': _nowMillis()}),
        );
        return const BirdPhotoResult(BirdPhotoStatus.none);
      }
      // 网络失败：**什么都不写**，下次重试。
      return const BirdPhotoResult(BirdPhotoStatus.failed);
    } finally {
      if (client == null) httpClient.close();
    }
  }

  static BirdPhotoResult? _readCache(SharedPreferences prefs, String key) {
    final raw = prefs.getString(key);
    if (raw == null || raw.isEmpty) return null;
    try {
      final data = jsonDecode(raw) as Map<String, dynamic>;
      final url = data['url'] as String?;
      if (url != null && url.isNotEmpty) {
        return BirdPhotoResult(BirdPhotoStatus.found, BirdPhoto.fromJson(data));
      }
      final ts = data['ts'] as int? ?? 0;
      if (_nowMillis() - ts < _negativeTtlMillis) {
        return const BirdPhotoResult(BirdPhotoStatus.none);
      }
    } catch (_) {
      // 缓存坏了当没有，重新查一次。
    }
    return null;
  }

  static int _nowMillis() => DateTime.now().millisecondsSinceEpoch;

  /// 把图片下到本地再显示。`Image.network` 没有超时，CDN 卡住时会一直转圈、
  /// 界面上看着就是「图永远加载不出来」；自己下就能设超时、失败也能明确报出来。
  /// 下过的图留在本地，之后打开秒出、离线也有。
  static Future<File?> imageFile(
    String sciName,
    BirdPhoto photo, {
    http.Client? client,
    bool forceRefresh = false,
  }) async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      // 一个物种存一张，文件名就用学名，换鸟时不会越堆越多。
      final file = File(
        '${dir.path}/bird_photos/${_safeFileName(sciName)}.img',
      );
      // 手动重试时把上次下坏的文件删掉，否则会一直读到那个坏文件。
      if (forceRefresh && await file.exists()) await file.delete();
      if (await file.exists() && await file.length() > 0) return file;
      final http.Client httpClient = client ?? http.Client();
      try {
        final response = await httpClient
            .get(Uri.parse(photo.url))
            .timeout(_imageTimeout);
        if (response.statusCode != 200 || response.bodyBytes.isEmpty) {
          return null;
        }
        await file.parent.create(recursive: true);
        await file.writeAsBytes(response.bodyBytes, flush: true);
        return file;
      } finally {
        if (client == null) httpClient.close();
      }
    } catch (_) {
      return null;
    }
  }

  static Future<BirdPhotoResult> _fromINaturalist(
    String sciName,
    http.Client client,
  ) async {
    try {
      final uri = Uri.https('api.inaturalist.org', '/v1/observations', {
        'taxon_name': sciName,
        'photos': 'true',
        'quality_grade': 'research',
        'photo_license': _ccLicenses,
        'order_by': 'votes',
        'per_page': '5',
      });
      final response = await client.get(uri).timeout(_apiTimeout);
      if (response.statusCode != 200) {
        return const BirdPhotoResult(BirdPhotoStatus.failed);
      }
      final data =
          jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
      for (final item in (data['results'] as List<dynamic>? ?? const [])) {
        final observation = item as Map<String, dynamic>;
        final photos = observation['photos'] as List<dynamic>? ?? const [];
        if (photos.isEmpty) continue;
        final photo = photos.first as Map<String, dynamic>;
        final rawUrl = photo['url'] as String? ?? '';
        final license = (photo['license_code'] as String? ?? '').trim();
        // 没有许可证 = 保留所有权利，跳过（photo_license 已经筛过一道，这里再兜一层）。
        if (rawUrl.isEmpty || license.isEmpty) continue;
        final user = observation['user'] as Map<String, dynamic>?;
        final name = (user?['name'] as String? ?? '').trim();
        final login = (user?['login'] as String? ?? '').trim();
        final author = name.isNotEmpty ? name : login;
        return BirdPhotoResult(
          BirdPhotoStatus.found,
          BirdPhoto(
            // 接口给的是 square（75px）缩略图，换成 medium（约 500px）。
            url: rawUrl.replaceAll('square.', 'medium.'),
            attribution: [
              'iNaturalist',
              if (author.isNotEmpty) author,
              license.toUpperCase(),
            ].join(' · '),
          ),
        );
      }
      return const BirdPhotoResult(BirdPhotoStatus.none);
    } catch (_) {
      return const BirdPhotoResult(BirdPhotoStatus.failed);
    }
  }

  static Future<BirdPhotoResult> _fromWikimedia(
    String sciName,
    http.Client client,
  ) async {
    try {
      // Commons 上每个物种一个分类（Category:Genus species），里面全是这种鸟的图。
      final uri = Uri.https('commons.wikimedia.org', '/w/api.php', {
        'action': 'query',
        'generator': 'categorymembers',
        'gcmtitle': 'Category:${sciName.replaceAll(' ', '_')}',
        'gcmtype': 'file',
        'gcmlimit': '5',
        'prop': 'imageinfo',
        'iiprop': 'url|mime|extmetadata',
        'iiurlwidth': '640',
        'format': 'json',
      });
      final response = await client.get(uri).timeout(_apiTimeout);
      if (response.statusCode != 200) {
        return const BirdPhotoResult(BirdPhotoStatus.failed);
      }
      final data =
          jsonDecode(utf8.decode(response.bodyBytes)) as Map<String, dynamic>;
      final pages =
          (data['query'] as Map<String, dynamic>?)?['pages']
              as Map<String, dynamic>?;
      for (final page in pages?.values ?? const []) {
        final infoList =
            (page as Map<String, dynamic>)['imageinfo'] as List<dynamic>?;
        if (infoList == null || infoList.isEmpty) continue;
        final info = infoList.first as Map<String, dynamic>;
        if (!(info['mime'] as String? ?? '').startsWith('image/')) continue;
        final url = info['thumburl'] as String? ?? info['url'] as String? ?? '';
        if (url.isEmpty) continue;
        final meta = info['extmetadata'] as Map<String, dynamic>?;
        final author = _plainText(_metaValue(meta, 'Artist'));
        final license = _plainText(_metaValue(meta, 'LicenseShortName'));
        return BirdPhotoResult(
          BirdPhotoStatus.found,
          BirdPhoto(
            url: url,
            attribution: [
              'Wikimedia Commons',
              if (author.isNotEmpty) author,
              if (license.isNotEmpty) license,
            ].join(' · '),
          ),
        );
      }
      return const BirdPhotoResult(BirdPhotoStatus.none);
    } catch (_) {
      return const BirdPhotoResult(BirdPhotoStatus.failed);
    }
  }

  static String _metaValue(Map<String, dynamic>? meta, String key) =>
      ((meta?[key] as Map<String, dynamic>?)?['value'] as String?) ?? '';

  // Commons 的 Artist 字段是一段 HTML（常带链接），扒成纯文本再显示。
  static String _plainText(String html) {
    final text =
        html
            .replaceAll(RegExp(r'<[^>]*>'), ' ')
            .replaceAll('&amp;', '&')
            .replaceAll('&quot;', '"')
            .replaceAll('&#039;', "'")
            .replaceAll(RegExp(r'\s+'), ' ')
            .trim();
    // 太长的署名会把卡片撑爆，截断但保留可辨认的部分。
    return text.length > 40 ? '${text.substring(0, 40)}…' : text;
  }
}

class _AboutPage extends StatelessWidget {
  const _AboutPage();

  // 关于页展示的版本号。发版时与 pubspec.yaml 的 version 同步更新，设置页页脚也用它。
  static const appVersion = 'v1.5.1';

  @override
  Widget build(BuildContext context) {
    return _LargeTitleScrollView(
      title: '关于',
      children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: _mintCardDecoration(context),
          child: Row(
            children: [
              SizedBox(
                width: 96,
                height: 96,
                child: CustomPaint(
                  // 关于页的卡片在深色下也是深色底，插画要跟着换描边色。
                  painter: _CartoonClockBirdPainter(
                    dark: Theme.of(context).brightness == Brightness.dark,
                  ),
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '鸟瘾闹钟',
                      style: Theme.of(context).textTheme.headlineSmall
                          ?.copyWith(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '$appVersion · ErikaAlk fork',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 6),
                    const Text('给鸟瘾综合征患者的早晨自救工具。'),
                  ],
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('版本与来源', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 10),
                Text('当前版本：$appVersion'),
                const SizedBox(height: 10),
                const Text(
                  '这是 ErikaAlk 基于原作者 oastwy 的「鸟瘾闹钟」做的个人自用 fork。在原版基础上去掉了强制认鸟挑战，新增锁屏直接关闹钟、按中国工作日 / 休息日（含周末）重复、闹铃渐响、深色模式与设置页、每日一鸟、闹钟与下载的 Live Updates，并修复了锁屏 / 息屏响铃与整夜耗电等问题。',
                ),
                const SizedBox(height: 8),
                const _SocialLinkTile(
                  icon: Icons.code,
                  label: '本 fork 源码（ErikaAlk）',
                  url: 'https://github.com/ErikaAlk/bird_alarm',
                ),
                const _SocialLinkTile(
                  icon: Icons.account_tree_outlined,
                  label: '原作者项目（oastwy）',
                  url: 'https://github.com/oastwy/bird_alarm',
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('关于我们', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 10),
                const Text(
                  '鸟瘾综合征，是一种听见树上有动静就想抬头、看见电线杆就想数鸟、早晨醒来先判断窗外是哪种叫声的温和症状。',
                ),
                const SizedBox(height: 10),
                const Text('我们把鸟鸣、闹钟和识鸟挑战放在一起，希望每次醒来都不是被噪音拽起来，而是被一只随机出现的鸟叫醒。'),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('致谢', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 10),
                const Text(
                  '内置鸟鸣来自 xeno-canto，一个由全球鸟友共同维护的野生鸟声共享平台。感谢以下录音的上传者。',
                ),
                const SizedBox(height: 10),
                const Text('四声杜鹃 · XC1101770', style: TextStyle(fontSize: 13)),
                const Text('大杜鹃 · XC1102893', style: TextStyle(fontSize: 13)),
                const Text('蛇雕 · XC1094944', style: TextStyle(fontSize: 13)),
                const Text('中华鹧鸪 · XC1034127', style: TextStyle(fontSize: 13)),
                const Text('强脚树莺 · XC1088414', style: TextStyle(fontSize: 13)),
                const Text('远东树莺 · XC1041519', style: TextStyle(fontSize: 13)),
                const Text('大山雀 · XC1093376', style: TextStyle(fontSize: 13)),
                const Text('笑翠鸟 · XC1086676', style: TextStyle(fontSize: 13)),
                const Text('绿啸冠鸫 · XC1088985', style: TextStyle(fontSize: 13)),
                const Text('噪鹃 · XC1101779', style: TextStyle(fontSize: 13)),
                const SizedBox(height: 8),
                const SizedBox(height: 10),
                const Text(
                  '「每日一鸟」的照片来自 iNaturalist 与 Wikimedia Commons，只取 CC 授权的图片，作者与许可证标在卡片上。鸟种名录来自 IOC / AviList。',
                ),
                const SizedBox(height: 8),
                Text(
                  '所有录音均遵循 xeno-canto Creative Commons 授权协议使用。',
                  style: TextStyle(
                    fontSize: 12,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('找到我们', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 6),
                Text(
                  '以下为原作者 oastwy 的频道与联系方式，本 fork 予以保留；本 fork 的问题请走上方 GitHub，勿打扰原作者。',
                  style: TextStyle(
                    fontSize: 12,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: 10),
                const Text('小红书、B站、小宇宙、抖音和微博等平台，全网同名。'),
                const SizedBox(height: 10),
                const _SocialLinkTile(
                  icon: Icons.podcasts_outlined,
                  label: '小宇宙',
                  url:
                      'https://www.xiaoyuzhoufm.com/podcast/6688a873ae8e21859ade308b',
                ),
                const _SocialLinkTile(
                  icon: Icons.bookmark_border,
                  label: '小红书',
                  url:
                      'https://www.xiaohongshu.com/user/profile/6516e3ef00000000240167e9',
                ),
                const _SocialLinkTile(
                  icon: Icons.ondemand_video_outlined,
                  label: 'B站',
                  url: 'https://space.bilibili.com/3546850323860358',
                ),
                const SizedBox(height: 10),
                const SelectableText(
                  '有问题请联系：birderrrr@gmail.com\n微信 / v：hotpeaker',
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _SocialLinkTile extends StatelessWidget {
  final IconData icon;
  final String label;
  final String url;

  const _SocialLinkTile({
    required this.icon,
    required this.label,
    required this.url,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(icon),
      title: Text(label),
      trailing: const Icon(Icons.open_in_new),
      onTap:
          () => launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication),
    );
  }
}

/// 权限自检页。两件事：
/// 1. 以前设置页那个「检查闹钟权限」只在**缺**权限时才跳系统设置，全授权了就一声不吭——
///    按了没反应，跟坏了一样。现在把每一项的状态都列出来。
/// 2. 它是**从右侧推入的独立页面**，不是底部浮层：设置行右边那个 ">" 箭头在所有系统里
///    都意味着「进下一页」，点了却从底下弹出个浮层，手感是拧的。
class _PermissionCheckPage extends StatefulWidget {
  const _PermissionCheckPage();

  @override
  State<_PermissionCheckPage> createState() => _PermissionCheckPageState();
}

class _PermissionCheckPageState extends State<_PermissionCheckPage>
    with WidgetsBindingObserver {
  static const _items = <({String key, String title, String detail})>[
    (key: 'notifications', title: '通知', detail: '响铃通知、倒计时、下载进度都要它'),
    (key: 'fullScreenIntent', title: '全屏通知', detail: '锁屏到点直接弹出响铃页，而不是一条横幅'),
    (key: 'exactAlarm', title: '精确闹钟', detail: '到点准时响，不被系统推迟'),
    (key: 'battery', title: '后台不受限', detail: '省电策略不掐后台，整夜也能按时响'),
  ];

  Map<String, bool>? _status;
  bool _checking = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _check();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // 从系统设置页返回时自动重查，不用手动刷新。
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check() async {
    if (mounted) setState(() => _checking = true);
    Map<String, bool>? status;
    try {
      final raw = await _AlarmHomePageState._systemAlarmChannel
          .invokeMethod<Map<dynamic, dynamic>>('checkAlarmPermissions');
      if (raw != null) {
        status = {
          for (final entry in raw.entries)
            entry.key.toString(): entry.value == true,
        };
      }
    } catch (_) {
      // 平台通道不可用（比如非 Android）：下面显示「查不到」。
    }
    if (!mounted) return;
    setState(() {
      _status = status;
      _checking = false;
    });
  }

  Future<void> _open(String type) async {
    try {
      await _AlarmHomePageState._systemAlarmChannel.invokeMethod<void>(
        'openPermissionSetting',
        {'type': type},
      );
    } catch (_) {
      // 打不开就算了，下面还有「打开应用设置」兜底。
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _status;
    final missing =
        status == null
            ? -1
            : _items.where((item) => status[item.key] != true).length;
    return Scaffold(
      body: _LargeTitleScrollView(
        title: '权限自检',
        floatingBarBelow: false,
        actions: [
          if (_checking)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 14, vertical: 18),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            )
          else
            _CircleActionButton(
              icon: Icons.refresh,
              tooltip: '重新检查',
              onPressed: _check,
            ),
        ],
        children: [
          Text(
            switch (missing) {
              -1 => '查不到权限状态（这台设备可能不是 Android）。',
              0 => '闹钟需要的权限都齐了。',
              _ => '有 $missing 项还没开，点右边去开启。',
            },
            style: TextStyle(
              fontSize: 13,
              color:
                  missing > 0
                      ? theme.colorScheme.error
                      : theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 14),
          if (status != null)
            _GroupedCard(
              children: [
                for (final item in _items)
                  _PermissionRow(
                    title: item.title,
                    detail: item.detail,
                    granted: status[item.key] == true,
                    onOpen: () => _open(item.key),
                  ),
              ],
            ),
          const SizedBox(height: 14),
          Text(
            '部分国产 ROM 还有「自启动」「后台弹出界面」「锁屏显示」等私有开关，系统不让 App 查询，'
            '需要你在应用设置里手动确认一次。',
            style: TextStyle(
              fontSize: 12,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: () => _open('appDetails'),
              icon: const Icon(Icons.open_in_new, size: 18),
              label: const Text('打开本应用的系统设置页'),
            ),
          ),
        ],
      ),
    );
  }
}

class _PermissionRow extends StatelessWidget {
  final String title;
  final String detail;
  final bool granted;
  final VoidCallback onOpen;

  const _PermissionRow({
    required this.title,
    required this.detail,
    required this.granted,
    required this.onOpen,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 12, 12, 12),
      child: Row(
        children: [
          Icon(
            granted ? Icons.check_circle : Icons.error_outline,
            size: 22,
            color:
                granted ? theme.colorScheme.primary : theme.colorScheme.error,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  detail,
                  style: TextStyle(
                    fontSize: 12,
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          if (granted)
            Text(
              '已开启',
              style: TextStyle(
                fontSize: 12.5,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            )
          else
            TextButton(onPressed: onOpen, child: const Text('去开启')),
        ],
      ),
    );
  }
}

/// 设置页：以前设置散在顶栏图标和弹窗里（只有一个 API Key），现在全部收进这一个 Tab——
/// 外观、响铃渐响、xeno-canto Key、权限自检都在这儿，按 iOS 分组列表排布。
class _SettingsTab extends StatefulWidget {
  final ValueChanged<int> onFadeInChanged;
  final VoidCallback onTestAlarm;
  final VoidCallback onCheckPermissions;

  const _SettingsTab({
    required this.onFadeInChanged,
    required this.onTestAlarm,
    required this.onCheckPermissions,
  });

  @override
  State<_SettingsTab> createState() => _SettingsTabState();
}

class _SettingsTabState extends State<_SettingsTab> {
  late final TextEditingController _apiKeyController;
  // 关掉渐响时记住上次的时长，重新打开还是它，不用再选一遍。
  int _lastFadeInSeconds = AppSettings.defaultFadeInSeconds;
  bool _obscureApiKey = true;

  @override
  void initState() {
    super.initState();
    _apiKeyController = TextEditingController(text: appSettings.xenoApiKey);
    if (appSettings.fadeInSeconds > 0) {
      _lastFadeInSeconds = appSettings.fadeInSeconds;
    }
  }

  @override
  void dispose() {
    _apiKeyController.dispose();
    super.dispose();
  }

  Future<void> _saveApiKey() async {
    await appSettings.setXenoApiKey(_apiKeyController.text);
    if (!mounted) return;
    FocusScope.of(context).unfocus();
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('已保存 xeno-canto API Key')));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final fadeInSeconds = appSettings.fadeInSeconds;
    return _LargeTitleScrollView(
      title: '设置',
      children: [
        const _SectionLabel('外观'),
        _GroupedCard(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        Icons.dark_mode_outlined,
                        size: 22,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 14),
                      const Text(
                        '深色模式',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  _SegmentedPicker<ThemeMode>(
                    segments: const [
                      (value: ThemeMode.system, label: '跟随系统'),
                      (value: ThemeMode.light, label: '浅色'),
                      (value: ThemeMode.dark, label: '深色'),
                    ],
                    selected: appSettings.themeMode,
                    onChanged: (mode) async {
                      await appSettings.setThemeMode(mode);
                      if (mounted) setState(() {});
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
        const SizedBox(height: 22),
        const _SectionLabel('响铃'),
        _GroupedCard(
          children: [
            _SettingsRow(
              icon: Icons.volume_up_outlined,
              title: '闹铃渐响',
              subtitle: '音量由轻到响慢慢升上来，不会一上来就吓一跳',
              trailing: Switch(
                value: fadeInSeconds > 0,
                onChanged:
                    (enabled) => widget.onFadeInChanged(
                      enabled ? _lastFadeInSeconds : 0,
                    ),
              ),
            ),
            if (fadeInSeconds > 0)
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '渐响时长',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      '从最轻升到设定音量所用的时间。要被立刻叫醒就把渐响关掉。',
                      style: TextStyle(
                        fontSize: 12.5,
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _SegmentedPicker<int>(
                      segments: const [
                        (value: 10, label: '10 秒'),
                        (value: 30, label: '30 秒'),
                        (value: 60, label: '60 秒'),
                      ],
                      selected: fadeInSeconds,
                      onChanged: (seconds) {
                        _lastFadeInSeconds = seconds;
                        widget.onFadeInChanged(seconds);
                      },
                    ),
                  ],
                ),
              ),
          ],
        ),
        const SizedBox(height: 22),
        const _SectionLabel('鸟鸣下载'),
        _GroupedCard(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(
                        Icons.key_outlined,
                        size: 22,
                        color: theme.colorScheme.primary,
                      ),
                      const SizedBox(width: 14),
                      const Expanded(
                        child: Text(
                          'xeno-canto API Key',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w500,
                          ),
                        ),
                      ),
                      TextButton(
                        onPressed: _saveApiKey,
                        child: const Text('保存'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _apiKeyController,
                    obscureText: _obscureApiKey,
                    decoration: InputDecoration(
                      hintText: '没有 Key 也能用，但请求次数有限制',
                      suffixIcon: IconButton(
                        tooltip: _obscureApiKey ? '显示' : '隐藏',
                        onPressed:
                            () => setState(
                              () => _obscureApiKey = !_obscureApiKey,
                            ),
                        icon: Icon(
                          _obscureApiKey
                              ? Icons.visibility_outlined
                              : Icons.visibility_off_outlined,
                        ),
                      ),
                    ),
                    onSubmitted: (_) => _saveApiKey(),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    '在 xeno-canto.org 注册后可在个人页面拿到免费 Key，填进来能提升搜索额度。',
                    style: TextStyle(
                      fontSize: 12,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            _SettingsRow(
              icon: Icons.language,
              title: '打开 xeno-canto 网站',
              onTap:
                  () => launchUrl(
                    Uri.parse('https://xeno-canto.org'),
                    mode: LaunchMode.externalApplication,
                  ),
              // 跳外部浏览器不是「进下一页」，别用 ">" 箭头。
              trailing: Icon(
                Icons.open_in_new,
                size: 18,
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
        const SizedBox(height: 22),
        const _SectionLabel('系统与自检'),
        _GroupedCard(
          children: [
            _SettingsRow(
              icon: Icons.verified_user_outlined,
              title: '检查闹钟权限',
              subtitle: '逐项列出通知 / 全屏通知 / 精确闹钟 / 后台限制的状态',
              trailing: Icon(
                Icons.chevron_right,
                color: theme.colorScheme.onSurfaceVariant,
              ),
              onTap: widget.onCheckPermissions,
            ),
            _SettingsRow(
              icon: Icons.notifications_active_outlined,
              title: '测试系统闹钟',
              subtitle: '10 秒后触发一次，用来确认响铃链路正常',
              // 这一行是「就地执行一个动作」，不是进下一页，所以给个「运行」而不是箭头。
              trailing: Text(
                '运行',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: theme.colorScheme.primary,
                ),
              ),
              onTap: widget.onTestAlarm,
            ),
          ],
        ),
        const SizedBox(height: 24),
        Center(
          child: Text(
            '鸟瘾闹钟 ${_AboutPage.appVersion}',
            style: TextStyle(
              fontSize: 12,
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      ],
    );
  }
}

class ActiveAlarm {
  final BirdAlarm alarm;
  final BirdSound sound;

  const ActiveAlarm({required this.alarm, required this.sound});
}

/// 响铃遮罩的震动反馈。走原生 `vibrate`（Vibrator + 指定振幅 + USAGE_ALARM），
/// **不用 Flutter 的 `HapticFeedback`**——那个走系统触感反馈，强度由系统设置决定、
/// 通常轻到感觉不到，而且三种情况震得一模一样，摸黑分不出自己到底关了还是贪睡了。
///
/// 三种模式刻意做得能闭眼分辨：越过触发线是一记极短轻震，关闭是一记长实震，
/// 贪睡是三记短震。原生不可用时退回 `HapticFeedback` 兜底。
class AlarmHaptics {
  static const _channel = MethodChannel('bird_alarm/system_alarm');

  /// 越过触发线：短促一下，告诉手指「够了，可以松手」。
  static void tick() => _fire('tick', HapticFeedback.selectionClick);

  /// 关闭闹钟：一记长震。
  static void dismissed() => _fire('dismiss', HapticFeedback.heavyImpact);

  /// 贪睡：三记短震，与关闭明显不同。
  static void snoozed() => _fire('snooze', HapticFeedback.mediumImpact);

  static void _fire(String pattern, Future<void> Function() fallback) {
    _channel.invokeMethod<void>('vibrate', {'pattern': pattern}).catchError((
      _,
    ) {
      // 非 Android 或通道不可用：至少给一下系统触感。
      fallback();
    });
  }
}

/// 全屏响铃遮罩：铺满整个屏幕（盖住底部导航栏），显示正在叫的鸟 + 关闭 / 贪睡。
/// 由 MainActivity 的 showWhenLocked 让它能显示在锁屏之上。
///
/// **盲操手势**：整屏任意位置**上滑关闭 / 下滑贪睡**。刚睡醒摸黑按，谁也瞄不准按钮，
/// 所以手势不挑落点；越过阈值先震一下（闭着眼也知道「够了」），松手才真正执行——
/// 中途改主意松手前划回去即可取消。按钮保留，睁眼时照常能点。
class AlarmOverlay extends StatefulWidget {
  final ActiveAlarm active;
  final VoidCallback onDismiss;
  final VoidCallback onSnooze;

  const AlarmOverlay({
    super.key,
    required this.active,
    required this.onDismiss,
    required this.onSnooze,
  });

  @override
  State<AlarmOverlay> createState() => _AlarmOverlayState();
}

class _AlarmOverlayState extends State<AlarmOverlay> {
  // 触发距离取得比较大：半梦半醒时手在屏幕上蹭一下不该把闹钟关掉。
  static const double _threshold = 120;
  static const double _flingVelocity = 900;

  double _drag = 0;
  bool _passed = false;
  bool _fired = false;

  void _handleUpdate(DragUpdateDetails details) {
    if (_fired) return;
    setState(() => _drag += details.delta.dy);
    final passed = _drag.abs() >= _threshold;
    if (passed != _passed) {
      setState(() => _passed = passed);
      // 越过/退回阈值都震一下：这是盲操时唯一的「反馈」。
      AlarmHaptics.tick();
    }
  }

  void _handleEnd(DragEndDetails details) {
    if (_fired) return;
    final velocity = details.primaryVelocity ?? 0;
    final up = _drag <= -_threshold || velocity <= -_flingVelocity;
    final down = _drag >= _threshold || velocity >= _flingVelocity;
    if (up || down) {
      up ? _dismiss() : _snooze();
      return;
    }
    setState(() {
      _drag = 0;
      _passed = false;
    });
  }

  // 关闭与贪睡各自震一种花样：摸黑操作时，震动是唯一能确认「我刚才到底干了什么」的信号。
  void _dismiss() {
    if (_fired) return;
    _fired = true;
    AlarmHaptics.dismissed();
    widget.onDismiss();
  }

  void _snooze() {
    if (_fired) return;
    _fired = true;
    AlarmHaptics.snoozed();
    widget.onSnooze();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final light = theme.brightness == Brightness.light;
    final active = widget.active;
    final up = _drag < 0;
    return Material(
      color: light ? const Color(0xFFFFF5DF) : theme.colorScheme.surface,
      child: GestureDetector(
        // opaque：整屏都能起手，不用瞄准任何控件。
        behavior: HitTestBehavior.opaque,
        onVerticalDragUpdate: _handleUpdate,
        onVerticalDragEnd: _handleEnd,
        onVerticalDragCancel:
            () => setState(() {
              _drag = 0;
              _passed = false;
            }),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(28, 16, 28, 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _SwipeHint(
                  icon: Icons.keyboard_arrow_up,
                  label: '上滑关闭闹钟',
                  activeLabel: '松手关闭闹钟',
                  active: _passed && up,
                  progress: _drag < 0 ? (-_drag / _threshold).clamp(0, 1) : 0,
                ),
                Expanded(
                  // 内容跟着手指走一点点，手势有「拖得动」的实感。
                  child: Transform.translate(
                    offset: Offset(0, _drag * 0.35),
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.notifications_active,
                          size: 68,
                          color: theme.colorScheme.primary,
                        ),
                        const SizedBox(height: 18),
                        Text(
                          active.alarm.label,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.headlineSmall,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          '正在叫的是',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodyMedium,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          active.sound.cnName,
                          textAlign: TextAlign.center,
                          style: theme.textTheme.displaySmall?.copyWith(
                            fontWeight: FontWeight.bold,
                            color: theme.colorScheme.primary,
                          ),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          '来源：${active.sound.source}',
                          textAlign: TextAlign.center,
                          style: theme.textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                ),
                FilledButton.icon(
                  onPressed: _dismiss,
                  icon: const Icon(Icons.alarm_off),
                  label: const Text('关闭闹钟'),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                  ),
                ),
                const SizedBox(height: 10),
                OutlinedButton.icon(
                  onPressed: _snooze,
                  icon: const Icon(Icons.snooze),
                  label: const Text('贪睡 5 分钟'),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                ),
                const SizedBox(height: 8),
                _SwipeHint(
                  icon: Icons.keyboard_arrow_down,
                  label: '下滑贪睡 5 分钟',
                  activeLabel: '松手贪睡 5 分钟',
                  active: _passed && !up,
                  progress: _drag > 0 ? (_drag / _threshold).clamp(0, 1) : 0,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 响铃遮罩上下两条滑动提示。跟着手指的位移变亮变粗，越过阈值换成「松手就…」。
class _SwipeHint extends StatelessWidget {
  final IconData icon;
  final String label;
  final String activeLabel;
  final bool active;
  final double progress;

  const _SwipeHint({
    required this.icon,
    required this.label,
    required this.activeLabel,
    required this.active,
    required this.progress,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color =
        Color.lerp(
          theme.colorScheme.onSurfaceVariant,
          theme.colorScheme.primary,
          progress,
        )!;
    return AnimatedOpacity(
      opacity: 0.55 + 0.45 * progress,
      duration: const Duration(milliseconds: 120),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 20 + 6 * progress, color: color),
          const SizedBox(width: 6),
          Text(
            active ? activeLabel : label,
            style: TextStyle(
              fontSize: 13,
              fontWeight: active ? FontWeight.w700 : FontWeight.w500,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

String _weekdayLabel(int day) {
  const labels = {1: '一', 2: '二', 3: '三', 4: '四', 5: '五', 6: '六', 7: '日'};
  return labels[day]!;
}

// 浅色用奶绿信息卡配色，深色跟随 M3。库页与关于页的信息卡共用同一份装饰。
BoxDecoration _mintCardDecoration(BuildContext context) {
  final light = Theme.of(context).brightness == Brightness.light;
  return BoxDecoration(
    color:
        light
            ? const Color(0xFFEAF6F2)
            : Theme.of(context).colorScheme.surfaceContainerHighest,
    borderRadius: BorderRadius.circular(20),
    border: Border.all(
      color:
          light
              ? const Color(0xFFB7DCD4)
              : Theme.of(context).colorScheme.outlineVariant,
    ),
  );
}

String _repeatText(BirdAlarm alarm) {
  switch (alarm.repeatRule) {
    case RepeatRule.chinaWorkdays:
      return '中国工作日';
    case RepeatRule.chinaHolidays:
      return '休息日';
    case RepeatRule.weekdays:
      final days = alarm.repeatDays;
      if (days.isEmpty) return '仅一次';
      if (days.length == 7) return '每天';
      return days.map(_weekdayLabel).join(' ');
  }
}

String _safeFileName(String value) {
  return value.replaceAll(RegExp(r'[^a-zA-Z0-9_.-]+'), '_');
}

String? _mimeFor(String path) {
  final lower = path.toLowerCase();
  if (lower.endsWith('.m4a') || lower.endsWith('.mp4')) return 'audio/mp4';
  if (lower.endsWith('.mp3')) return 'audio/mpeg';
  if (lower.endsWith('.wav')) return 'audio/wav';
  if (lower.endsWith('.ogg')) return 'audio/ogg';
  return null;
}

/// 中国节假日数据：在线实时获取（timor.tech），带本地缓存；离线/失败时回退到
/// [ChinaWorkdayCalendar] 内置的 2026 表。数据为 日期(yyyy-MM-dd) -> 是否放假。
class ChinaHolidayData {
  static const _dataPrefix = 'holiday_cn_data_';
  static const _fetchedPrefix = 'holiday_cn_fetched_';
  static final Map<String, bool> _offDays = {};

  /// 查某日是否放假：true=放假, false=调休补班, null=无在线数据。
  static bool? lookup(String dateKey) => _offDays[dateKey];

  /// 用本地缓存填充内存数据（快速、无网络）。排闹钟前调用。
  static Future<void> loadCache() async {
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now();
    for (final year in {now.year, now.year + 1}) {
      final cached = prefs.getString('$_dataPrefix$year');
      if (cached != null) _merge(cached);
    }
  }

  /// 后台刷新当前年与次年的数据；有更新返回 true（调用方可据此重排闹钟）。
  static Future<bool> refresh() async {
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now();
    var changed = false;
    for (final year in {now.year, now.year + 1}) {
      final fetchedAt = prefs.getInt('$_fetchedPrefix$year') ?? 0;
      final hasCache = prefs.getString('$_dataPrefix$year') != null;
      final ageMs = now.millisecondsSinceEpoch - fetchedAt;
      if (hasCache && ageMs < 7 * 86400000) continue; // 一周内刷过就跳过
      try {
        final resp = await http
            .get(Uri.parse('https://timor.tech/api/holiday/year/$year'))
            .timeout(const Duration(seconds: 8));
        if (resp.statusCode == 200 && _merge(resp.body)) {
          await prefs.setString('$_dataPrefix$year', resp.body);
          await prefs.setInt(
            '$_fetchedPrefix$year',
            now.millisecondsSinceEpoch,
          );
          changed = true;
        }
      } catch (_) {
        // 离线/失败：继续用缓存或内置 2026 表。
      }
    }
    return changed;
  }

  // 解析 timor.tech 返回并合并进内存；解析到有效数据返回 true。
  static bool _merge(String body) {
    try {
      final map = jsonDecode(body) as Map<String, dynamic>;
      final holiday = map['holiday'];
      if (holiday is! Map) return false;
      var any = false;
      for (final value in holiday.values) {
        if (value is Map) {
          final date = value['date']?.toString();
          if (date != null && date.isNotEmpty) {
            // 宽松解析：holiday 字段可能是 bool，也可能因接口变动成数字/字符串；
            // 一律按「是否为 true / 1 / 'true'」判定，避免 as bool? 抛错丢掉整年数据。
            final raw = value['holiday'];
            _offDays[date] =
                raw == true || raw == 1 || raw.toString() == 'true';
            any = true;
          }
        }
      }
      return any;
    } catch (_) {
      return false;
    }
  }
}

class ChinaWorkdayCalendar {
  static const _holidayDates2026 = {
    '2026-01-01',
    '2026-01-02',
    '2026-01-03',
    '2026-02-15',
    '2026-02-16',
    '2026-02-17',
    '2026-02-18',
    '2026-02-19',
    '2026-02-20',
    '2026-02-21',
    '2026-02-22',
    '2026-02-23',
    '2026-04-04',
    '2026-04-05',
    '2026-04-06',
    '2026-05-01',
    '2026-05-02',
    '2026-05-03',
    '2026-05-04',
    '2026-05-05',
    '2026-06-19',
    '2026-06-20',
    '2026-06-21',
    '2026-09-25',
    '2026-09-26',
    '2026-09-27',
    '2026-10-01',
    '2026-10-02',
    '2026-10-03',
    '2026-10-04',
    '2026-10-05',
    '2026-10-06',
    '2026-10-07',
  };

  static const _adjustedWorkDates2026 = {
    '2026-01-04',
    '2026-02-14',
    '2026-02-28',
    '2026-05-09',
    '2026-09-20',
    '2026-10-10',
  };

  static bool isWorkday(DateTime date) {
    final off = _offDayOverride(_dateKey(date));
    if (off != null) return !off;
    return date.weekday >= DateTime.monday && date.weekday <= DateTime.friday;
  }

  // 该日期是否放假：true=放假, false=调休补班, null=普通日（按周一~周五判断）。
  // 优先用在线节假日数据（ChinaHolidayData），无则回退到内置 2026 表。
  static bool? _offDayOverride(String key) {
    final online = ChinaHolidayData.lookup(key);
    if (online != null) return online;
    if (_adjustedWorkDates2026.contains(key)) return false;
    if (_holidayDates2026.contains(key)) return true;
    return null;
  }

  static String _dateKey(DateTime date) {
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '${date.year}-$month-$day';
  }
}

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

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

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const BirdAlarmApp());
}

class BirdAlarmApp extends StatelessWidget {
  const BirdAlarmApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '鸟瘾闹钟',
      debugShowCheckedModeBanner: false,
      // 跟随系统深浅色；浅色保留原有奶油观感，深色用 M3 自动配色。
      themeMode: ThemeMode.system,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1D7C76),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFFFF5DF),
        appBarTheme: const AppBarTheme(backgroundColor: Color(0xFFFFF5DF)),
        cardTheme: CardThemeData(
          color: Colors.white,
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1D7C76),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
        cardTheme: CardThemeData(
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
        ),
      ),
      home: const AlarmHomePage(),
    );
  }
}

enum BirdLibraryFilter { all, downloaded, notDownloaded }

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
  static const _xenoApiKeyKey = 'bird_alarm_xeno_api_key';
  static const _systemAlarmChannel = MethodChannel('bird_alarm/system_alarm');
  final _random = Random();
  final _player = AudioPlayer();
  final _queryController = TextEditingController(text: 'cnt:China q:A');
  final _speciesSearchController = TextEditingController();
  final _apiKeyController = TextEditingController();

  List<BirdAlarm> _alarms = const [];
  List<BirdSound> _library = _starterLibrary;
  List<BirdSound> _searchResults = const [];
  List<BirdName> _nameList = const [];
  Map<String, BirdName> _nameIndex = const {};
  Set<String> _downloadingIds = const {};
  Timer? _ticker;
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
  BirdLibraryFilter _libraryFilter = BirdLibraryFilter.all;

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
    _reconcileTicker();
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
    _clock.dispose();
    _queryController.dispose();
    _speciesSearchController.dispose();
    _apiKeyController.dispose();
    _player.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final alarmRaw = prefs.getString(_alarmsKey);
    final libraryRaw = prefs.getString(_libraryKey);
    // 两个独立的 I/O：鸟名表(bundle)与节假日缓存(prefs)并行加载，缩短冷启动首帧时间。
    await Future.wait([_loadNameIndex(), ChinaHolidayData.loadCache()]);
    _apiKeyController.text =
        prefs.getString(_xenoApiKeyKey) ?? _apiKeyController.text;
    setState(() {
      if (alarmRaw != null) {
        _alarms = _sortByTime(
          (jsonDecode(alarmRaw) as List<dynamic>)
              .map((item) => BirdAlarm.fromJson(item as Map<String, dynamic>))
              .toList(),
        );
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
        await _ringNextEnabledAlarm(
          assetPath: assetPath,
          useNativeAudio: true,
        );
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
    await _prepareAlarmWindow();
    setState(() {
      _previewingSoundId = null;
      _activeAlarm = ActiveAlarm(alarm: alarm, sound: sound);
    });
    // 原生可能在 app 退后台/熄屏(paused)时把本轮闹钟拉起来，此时计时器是停的；
    // 一旦有了正在响的闹钟，立即恢复计时器，保证响铃遮罩能每秒轮询、被通知关闭时自动收起。
    _reconcileTicker();
    if (!useNativeAudio) {
      await _playSound(sound);
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
    final dueNow = enabled.where((alarm) {
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

  /// 按响铃时间（时:分）升序排列；时间相同按 id（创建先后）保持稳定顺序。
  List<BirdAlarm> _sortByTime(List<BirdAlarm> alarms) {
    return [...alarms]..sort((a, b) {
      final byTime = (a.time.hour * 60 + a.time.minute)
          .compareTo(b.time.hour * 60 + b.time.minute);
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
    await prefs.setString(_xenoApiKeyKey, _apiKeyController.text.trim());
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
          await _systemAlarmChannel.invokeMethod<void>('requestAlarmPermissions');
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

  Future<void> _playSound(BirdSound sound) async {
    await _player.stop();
    await _configureAlarmAudio();
    await _player.setReleaseMode(ReleaseMode.loop);
    await _player.setVolume(1);
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
      final key = Uri.encodeQueryComponent(_apiKeyController.text.trim());
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

  Future<void> _downloadXenoSound(BirdSound sound) async {
    final url = sound.url;
    if (url == null || url.isEmpty) return;
    setState(() => _downloadingIds = {..._downloadingIds, sound.id});
    try {
      await _save();
      final response = await http.get(Uri.parse(url));
      if (response.statusCode != 200 || response.bodyBytes.isEmpty) {
        throw Exception('HTTP ${response.statusCode}');
      }
      final dir = await getApplicationDocumentsDirectory();
      final audioDir = Directory('${dir.path}/bird_sounds');
      await audioDir.create(recursive: true);
      final fileName = '${_safeFileName(sound.id)}.mp3';
      final file = File('${audioDir.path}/$fileName');
      await file.writeAsBytes(response.bodyBytes, flush: true);
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
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('已下载：${sound.cnName}')));
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('下载失败：$error')));
      }
    } finally {
      if (mounted) {
        setState(() {
          _downloadingIds = {..._downloadingIds}..remove(sound.id);
        });
      }
    }
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
      final key = Uri.encodeQueryComponent(_apiKeyController.text.trim());
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

  List<BirdName> _filteredBirdNames() {
    final query = _speciesSearchController.text.trim().toLowerCase();
    final downloaded = _library.map((sound) => sound.sciName).toSet();
    return _nameList
        .where((bird) {
          final isDownloaded = downloaded.contains(bird.sci);
          if (_libraryFilter == BirdLibraryFilter.downloaded && !isDownloaded) {
            return false;
          }
          if (_libraryFilter == BirdLibraryFilter.notDownloaded &&
              isDownloaded) {
            return false;
          }
          if (query.isEmpty) return true;
          return bird.display.toLowerCase().contains(query) ||
              bird.cn.toLowerCase().contains(query) ||
              bird.en.toLowerCase().contains(query) ||
              bird.sci.toLowerCase().contains(query);
        })
        .take(80)
        .toList();
  }

  String _displayNameFor(String sciName, String englishName) {
    final match = _nameIndex[sciName];
    if (match == null) return englishName;
    return match.display.isNotEmpty ? match.display : englishName;
  }

  Future<void> _editAlarm([BirdAlarm? existing]) async {
    final result = await showModalBottomSheet<BirdAlarm>(
      context: context,
      isScrollControlled: true,
      builder: (context) => AlarmEditor(alarm: existing),
    );
    if (result == null) return;
    setState(() {
      if (existing == null) {
        _alarms = _sortByTime([..._alarms, result]);
      } else {
        _alarms = _sortByTime(
          _alarms
              .map((alarm) => alarm.id == result.id ? result : alarm)
              .toList(),
        );
      }
    });
    await _save();
  }

  Future<void> _showSettings() async {
    final result = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _SettingsSheet(initialApiKey: _apiKeyController.text),
    );
    if (result != null) {
      _apiKeyController.text = result;
      await _save();
    }
  }

  @override
  Widget build(BuildContext context) {
    final active = _activeAlarm;
    return Stack(
      textDirection: TextDirection.ltr,
      children: [
        Scaffold(
      appBar: AppBar(
        title: const Text('鸟瘾闹钟'),
        actions: [
          IconButton(
            tooltip: '测试系统闹钟',
            onPressed: _testSystemAlarm,
            icon: const Icon(Icons.notifications_active_outlined),
          ),
          IconButton(
            tooltip: '设置',
            onPressed: _showSettings,
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
      ),
      floatingActionButton:
          _selectedTab == 0
              ? FloatingActionButton.extended(
                onPressed: () => _editAlarm(),
                icon: const Icon(Icons.add_alarm),
                label: const Text('新闹钟'),
              )
              : null,
      bottomNavigationBar: NavigationBar(
        selectedIndex: _selectedTab,
        onDestinationSelected: (index) => setState(() => _selectedTab = index),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.alarm_outlined),
            selectedIcon: Icon(Icons.alarm),
            label: '闹钟',
          ),
          NavigationDestination(
            icon: Icon(Icons.graphic_eq_outlined),
            selectedIcon: Icon(Icons.graphic_eq),
            label: '鸟鸣',
          ),
          NavigationDestination(
            icon: Icon(Icons.info_outline),
            selectedIcon: Icon(Icons.info),
            label: '关于',
          ),
        ],
      ),
      body: Stack(
        children: [
          IndexedStack(
            index: _selectedTab,
            children: [
              _AlarmTab(
                clock: _clock,
                nextAlarm: _nextAlarmText(),
                alarms: _alarms,
                onEditAlarm: _editAlarm,
                onDeleteAlarm: (alarm) async {
                  setState(() {
                    _alarms =
                        _alarms.where((item) => item.id != alarm.id).toList();
                  });
                  await _save();
                },
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
              SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
                child: _LibraryPanel(
                  library: _library,
                  species: _filteredBirdNames(),
                  controller: _queryController,
                  speciesSearchController: _speciesSearchController,
                  filter: _libraryFilter,
                  searching: _searching,
                  downloadingIds: _downloadingIds,
                  previewingSoundId: _previewingSoundId,
                  results: _searchResults,
                  onUpload: _pickLocalAudio,
                  onSearch: _searchXenoCanto,
                  onSpeciesSearchChanged: (_) => setState(() {}),
                  onFilterChanged:
                      (filter) => setState(() => _libraryFilter = filter),
                  onAdd: _addXenoSound,
                  onDownloadSpecies: _downloadSpeciesFromXeno,
                  onDownload: _downloadXenoSound,
                  onPreview: _togglePreview,
                ),
              ),
              const _AboutPage(),
            ],
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
  final List<BirdAlarm> alarms;
  final ValueChanged<BirdAlarm> onEditAlarm;
  final ValueChanged<BirdAlarm> onDeleteAlarm;
  final Future<void> Function(BirdAlarm alarm, bool enabled)
  onAlarmEnabledChanged;

  const _AlarmTab({
    required this.clock,
    required this.nextAlarm,
    required this.alarms,
    required this.onEditAlarm,
    required this.onDeleteAlarm,
    required this.onAlarmEnabledChanged,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
      children: [
        _BirdTimePanel(clock: clock, nextAlarm: nextAlarm),
        const SizedBox(height: 16),
        Text('闹钟', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        if (alarms.isEmpty)
          const Card(
            child: Padding(
              padding: EdgeInsets.all(18),
              child: Text('还没有闹钟，点右下角新建一个。'),
            ),
          ),
        for (final alarm in alarms)
          _AlarmTile(
            alarm: alarm,
            onChanged: (enabled) => onAlarmEnabledChanged(alarm, enabled),
            onTap: () => onEditAlarm(alarm),
            onDelete: () => onDeleteAlarm(alarm),
          ),
      ],
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

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          20,
          16,
          20,
          MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('设置闹钟', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.schedule),
              title: Text(
                _time.format(context),
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              trailing: const Icon(Icons.edit),
              onTap: () async {
                final picked = await _showCupertinoTimePicker(context, _time);
                if (picked != null) setState(() => _time = picked);
              },
            ),
            TextField(
              controller: _labelController,
              decoration: const InputDecoration(labelText: '标签'),
            ),
            const SizedBox(height: 16),
            const Text('重复'),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: SegmentedButton<RepeatRule>(
                showSelectedIcon: false,
                segments: const [
                  ButtonSegment(value: RepeatRule.weekdays, label: Text('自定义')),
                  ButtonSegment(
                    value: RepeatRule.chinaWorkdays,
                    label: Text('工作日'),
                  ),
                  ButtonSegment(
                    value: RepeatRule.chinaHolidays,
                    label: Text('节假日'),
                  ),
                ],
                selected: {_rule},
                onSelectionChanged:
                    (value) => setState(() => _rule = value.first),
              ),
            ),
            const SizedBox(height: 12),
            if (_rule == RepeatRule.weekdays)
              Wrap(
                spacing: 8,
                children: [
                  for (var day = 1; day <= 7; day++)
                    FilterChip(
                      label: Text(_weekdayLabel(day)),
                      selected: _days.contains(day),
                      surfaceTintColor: Colors.transparent,
                      onSelected: (selected) {
                        setState(() {
                          selected ? _days.add(day) : _days.remove(day);
                        });
                      },
                    ),
                ],
              )
            else
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  _rule == RepeatRule.chinaWorkdays
                      ? '仅工作日响铃：周末与法定节假日不响，含调休补班日。'
                      : '休息日响铃：周末和法定节假日都响，调休补班日不响。',
                  style: TextStyle(
                    fontSize: 12,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            const SizedBox(height: 20),
            FilledButton.icon(
              onPressed: () {
                Navigator.of(context).pop(
                  BirdAlarm(
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
                );
              },
              icon: const Icon(Icons.check),
              label: const Text('保存'),
            ),
          ],
        ),
      ),
    );
  }
}

Future<TimeOfDay?> _showCupertinoTimePicker(
  BuildContext context,
  TimeOfDay initialTime,
) {
  var selected = DateTime(2026, 1, 1, initialTime.hour, initialTime.minute);
  return showModalBottomSheet<TimeOfDay>(
    context: context,
    builder: (context) {
      return SafeArea(
        child: SizedBox(
          height: 330,
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 8, 4),
                child: Row(
                  children: [
                    Text(
                      '选择时间',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const Spacer(),
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: const Text('取消'),
                    ),
                    FilledButton(
                      onPressed:
                          () => Navigator.of(context).pop(
                            TimeOfDay(
                              hour: selected.hour,
                              minute: selected.minute,
                            ),
                          ),
                      child: const Text('确定'),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: CupertinoDatePicker(
                  mode: CupertinoDatePickerMode.time,
                  use24hFormat: true,
                  minuteInterval: 1,
                  initialDateTime: selected,
                  onDateTimeChanged: (value) => selected = value,
                ),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _BirdTimePanel extends StatelessWidget {
  final ValueListenable<DateTime> clock;
  final String nextAlarm;

  const _BirdTimePanel({required this.clock, required this.nextAlarm});

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: Container(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFFFFD983), Color(0xFFFFF0C2)],
          ),
        ),
        child: Stack(
          children: [
            Positioned.fill(child: CustomPaint(painter: _SkyPatternPainter())),
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 14, 18, 18),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final compact = constraints.maxWidth < 430;
                  final bird = SizedBox(
                    width: compact ? 150 : 190,
                    height: compact ? 160 : 190,
                    child: CustomPaint(painter: _CartoonClockBirdPainter()),
                  );
                  // 只让时间文字随时钟每秒重建，外面的渐变 / 卡通鸟 / 天空图案不重绘。
                  final copy = ValueListenableBuilder<DateTime>(
                    valueListenable: clock,
                    builder: (context, now, _) {
                      final timeText =
                          '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}';
                      return _BirdSpeechPanel(
                        timeText: timeText,
                        nextAlarm: nextAlarm,
                      );
                    },
                  );
                  if (compact) {
                    return Column(
                      children: [bird, const SizedBox(height: 8), copy],
                    );
                  }
                  return Row(
                    children: [
                      bird,
                      const SizedBox(width: 14),
                      Expanded(child: copy),
                    ],
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BirdSpeechPanel extends StatelessWidget {
  final String timeText;
  final String nextAlarm;

  const _BirdSpeechPanel({required this.timeText, required this.nextAlarm});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.88),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0xFF5CAAA0), width: 2),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1F5F4B25),
            blurRadius: 18,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.record_voice_over_outlined,
                color: Color(0xFF164A45),
              ),
              const SizedBox(width: 8),
              Text(
                '报时鸟正在值班',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: const Color(0xFF164A45),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            timeText,
            style: Theme.of(context).textTheme.displayMedium?.copyWith(
              color: const Color(0xFF164A45),
              fontWeight: FontWeight.w800,
              letterSpacing: 0,
            ),
          ),
          const SizedBox(height: 8),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(
                Icons.alarm_on_outlined,
                size: 20,
                color: Color(0xFF164A45),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '下一次唤醒',
                      style: TextStyle(color: Color(0xFF3C5A54)),
                    ),
                    Text(
                      nextAlarm,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        color: const Color(0xFF164A45),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _SkyPatternPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final cloud = Paint()..color = Colors.white.withValues(alpha: 0.36);
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
    final hill = Paint()..color = const Color(0xFFB8D98B);
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
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _CartoonClockBirdPainter extends CustomPainter {
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
          ..color = const Color(0xFF3C3324)
          ..style = PaintingStyle.stroke
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round
          ..strokeWidth = 4;
    final body = Paint()..color = const Color(0xFF1D9A8A);
    final belly = Paint()..color = const Color(0xFFFFEFBD);
    final wing = Paint()..color = const Color(0xFF126D68);
    final beak = Paint()..color = const Color(0xFFFFA13D);
    final blush = Paint()..color = const Color(0xFFFF9BA6);
    final dark = Paint()..color = const Color(0xFF2B251D);
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

    canvas.drawCircle(const Offset(99, 76), 7, dark);
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
      canvas.drawCircle(point, 2, dark);
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
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

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

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      child: ListTile(
        onTap: onTap,
        leading: const Icon(Icons.music_note_outlined),
        title: Text(
          alarm.time.format(context),
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        subtitle: Text('${alarm.label} · ${_repeatText(alarm)}'),
        trailing: Wrap(
          spacing: 2,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            IconButton(
              tooltip: '删除闹钟',
              onPressed: onDelete,
              icon: const Icon(Icons.delete_outline),
            ),
            Switch(value: alarm.enabled, onChanged: onChanged),
          ],
        ),
      ),
    );
  }
}

class _LibraryPanel extends StatelessWidget {
  final List<BirdSound> library;
  final List<BirdName> species;
  final TextEditingController controller;
  final TextEditingController speciesSearchController;
  final BirdLibraryFilter filter;
  final bool searching;
  final Set<String> downloadingIds;
  final String? previewingSoundId;
  final List<BirdSound> results;
  final VoidCallback onUpload;
  final VoidCallback onSearch;
  final ValueChanged<String> onSpeciesSearchChanged;
  final ValueChanged<BirdLibraryFilter> onFilterChanged;
  final ValueChanged<BirdSound> onAdd;
  final ValueChanged<BirdName> onDownloadSpecies;
  final ValueChanged<BirdSound> onDownload;
  final ValueChanged<BirdSound> onPreview;

  const _LibraryPanel({
    required this.library,
    required this.species,
    required this.controller,
    required this.speciesSearchController,
    required this.filter,
    required this.searching,
    required this.downloadingIds,
    required this.previewingSoundId,
    required this.results,
    required this.onUpload,
    required this.onSearch,
    required this.onSpeciesSearchChanged,
    required this.onFilterChanged,
    required this.onAdd,
    required this.onDownloadSpecies,
    required this.onDownload,
    required this.onPreview,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('鸟鸣库', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        Row(
          children: [
            FilledButton.tonalIcon(
              onPressed: onUpload,
              icon: const Icon(Icons.upload_file),
              label: const Text('上传音频'),
            ),
            const SizedBox(width: 8),
            Text(
              '${library.length} 条',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ],
        ),
        const SizedBox(height: 12),
        Container(
          padding: const EdgeInsets.all(14),
          decoration: _mintCardDecoration(context),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.search),
                  const SizedBox(width: 8),
                  Text('鸟种搜索', style: Theme.of(context).textTheme.titleMedium),
                ],
              ),
              const SizedBox(height: 10),
              TextField(
                controller: speciesSearchController,
                decoration: const InputDecoration(
                  labelText: '搜索中英文或拉丁名',
                  hintText: '例如：杜鹃 / cuckoo / Cuculus',
                  prefixIcon: Icon(Icons.manage_search),
                ),
                onChanged: onSpeciesSearchChanged,
              ),
              const SizedBox(height: 10),
              SegmentedButton<BirdLibraryFilter>(
                segments: const [
                  ButtonSegment(
                    value: BirdLibraryFilter.all,
                    label: Text('全部'),
                  ),
                  ButtonSegment(
                    value: BirdLibraryFilter.downloaded,
                    label: Text('已下载'),
                  ),
                  ButtonSegment(
                    value: BirdLibraryFilter.notDownloaded,
                    label: Text('未下载'),
                  ),
                ],
                selected: {filter},
                onSelectionChanged: (value) => onFilterChanged(value.first),
              ),
              const SizedBox(height: 8),
              for (final bird in species.take(30))
                _SpeciesDownloadTile(
                  bird: bird,
                  sound:
                      library
                          .where((sound) => sound.sciName == bird.sci)
                          .firstOrNull,
                  downloading: downloadingIds.contains('species-${bird.sci}'),
                  previewing:
                      library
                          .where((sound) => sound.sciName == bird.sci)
                          .firstOrNull
                          ?.id ==
                      previewingSoundId,
                  onPreview: onPreview,
                  onDownload: onDownloadSpecies,
                ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text(
            'xeno-canto 高级查询',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          leading: const Icon(Icons.cloud_download_outlined),
          children: [
            TextField(
              controller: controller,
              decoration: InputDecoration(
                labelText: '查询条件',
                hintText: '例如：cnt:China q:A 或 gen:Turdus sp:merula',
                prefixIcon: const Icon(Icons.travel_explore),
                suffixIcon: IconButton(
                  tooltip: '搜索鸟鸣',
                  onPressed: searching ? null : onSearch,
                  icon:
                      searching
                          ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                          : const Icon(Icons.search),
                ),
              ),
              onSubmitted: (_) => onSearch(),
            ),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton.icon(
                onPressed: searching ? null : onSearch,
                icon: const Icon(Icons.search),
                label: const Text('搜索 xeno-canto'),
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        if (results.isNotEmpty)
          Text('搜索结果', style: Theme.of(context).textTheme.titleMedium),
        for (final result in results)
          ListTile(
            dense: true,
            leading: IconButton(
              tooltip: previewingSoundId == result.id ? '暂停' : '试听',
              onPressed: result.playable ? () => onPreview(result) : null,
              icon: Icon(
                previewingSoundId == result.id
                    ? Icons.pause_circle_outline
                    : Icons.play_arrow,
              ),
            ),
            title: Text(result.cnName),
            subtitle: Text('${result.sciName} · ${result.source}'),
            trailing: Wrap(
              spacing: 4,
              children: [
                IconButton(
                  tooltip: '加入音库',
                  onPressed: () => onAdd(result),
                  icon: const Icon(Icons.add_circle_outline),
                ),
                IconButton(
                  tooltip: '下载到本机',
                  onPressed:
                      downloadingIds.contains(result.id)
                          ? null
                          : () => onDownload(result),
                  icon:
                      downloadingIds.contains(result.id)
                          ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                          : const Icon(Icons.download_for_offline_outlined),
                ),
              ],
            ),
          ),
        const Divider(height: 24),
        Text('当前音库', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        const Text('有播放按钮的条目会被闹钟随机抽取；下载后的 xeno-canto 鸟鸣可离线播放。'),
        const SizedBox(height: 8),
        // 下载/自定义的鸟鸣排在内置前面，且不截断，确保下载后一定能在音库里看到。
        for (final sound in [
          ...library.where((sound) => !sound.id.startsWith('starter-')),
          ...library.where((sound) => sound.id.startsWith('starter-')),
        ])
          ListTile(
            dense: true,
            leading: Icon(
              sound.playable ? Icons.graphic_eq : Icons.library_music_outlined,
            ),
            title: Text(sound.cnName),
            subtitle: Text(
              '${sound.enName}${sound.sciName.isEmpty ? '' : ' · ${sound.sciName}'}\n${sound.source}',
            ),
            isThreeLine: true,
            trailing: Wrap(
              spacing: 4,
              children: [
                IconButton(
                  tooltip: previewingSoundId == sound.id ? '暂停' : '试听',
                  onPressed: sound.playable ? () => onPreview(sound) : null,
                  icon: Icon(
                    previewingSoundId == sound.id
                        ? Icons.pause_circle_outline
                        : Icons.play_arrow,
                  ),
                ),
                if (sound.url != null && sound.localPath == null)
                  IconButton(
                    tooltip: '下载到本机',
                    onPressed:
                        downloadingIds.contains(sound.id)
                            ? null
                            : () => onDownload(sound),
                    icon:
                        downloadingIds.contains(sound.id)
                            ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                            : const Icon(Icons.download_for_offline_outlined),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}

class _SpeciesDownloadTile extends StatelessWidget {
  final BirdName bird;
  final BirdSound? sound;
  final bool downloading;
  final bool previewing;
  final ValueChanged<BirdSound> onPreview;
  final ValueChanged<BirdName> onDownload;

  const _SpeciesDownloadTile({
    required this.bird,
    required this.sound,
    required this.downloading,
    required this.previewing,
    required this.onPreview,
    required this.onDownload,
  });

  @override
  Widget build(BuildContext context) {
    final downloaded = sound != null;
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        downloaded ? Icons.check_circle : Icons.radio_button_unchecked,
      ),
      title: Text(bird.display),
      subtitle: Text('${bird.en}\n${bird.sci}'),
      isThreeLine: true,
      trailing: Wrap(
        spacing: 4,
        children: [
          IconButton(
            tooltip: previewing ? '暂停' : '试听',
            onPressed: sound?.playable == true ? () => onPreview(sound!) : null,
            icon: Icon(
              previewing ? Icons.pause_circle_outline : Icons.play_arrow,
            ),
          ),
          IconButton(
            tooltip: downloaded ? '已下载' : '下载 xeno 鸟鸣',
            onPressed:
                downloaded || downloading ? null : () => onDownload(bird),
            icon:
                downloading
                    ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                    : const Icon(Icons.download_for_offline_outlined),
          ),
        ],
      ),
    );
  }
}

extension _FirstOrNull<T> on Iterable<T> {
  T? get firstOrNull {
    final iterator = this.iterator;
    if (iterator.moveNext()) return iterator.current;
    return null;
  }
}

class _AboutPage extends StatelessWidget {
  const _AboutPage();

  // 关于页展示的版本号——发版时与 pubspec.yaml 的 version 同步更新。
  static const _appVersion = 'v1.3.2';

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
      children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: _mintCardDecoration(context),
          child: Row(
            children: [
              SizedBox(
                width: 96,
                height: 96,
                child: CustomPaint(painter: _CartoonClockBirdPainter()),
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
                      '$_appVersion · ErikaAlk fork',
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
                Text('当前版本：$_appVersion'),
                const SizedBox(height: 10),
                const Text(
                  '这是 ErikaAlk 基于原作者 oastwy 的「鸟瘾闹钟」做的个人自用 fork。在原版基础上去掉了强制认鸟挑战，新增锁屏直接关闹钟、按中国工作日 / 休息日（含周末）重复、深色模式、闹钟 Live Updates，并修复了锁屏 / 息屏响铃与整夜耗电等问题。',
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
                  '内置鸟鸣来自 xeno-canto——一个由全球鸟友共同维护的野生鸟声共享平台。感谢以下录音的上传者，正是他们的记录让这个 App 成为可能。',
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

class _SettingsSheet extends StatefulWidget {
  final String initialApiKey;

  const _SettingsSheet({required this.initialApiKey});

  @override
  State<_SettingsSheet> createState() => _SettingsSheetState();
}

class _SettingsSheetState extends State<_SettingsSheet> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialApiKey);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          20,
          16,
          20,
          MediaQuery.of(context).viewInsets.bottom + 20,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('设置', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 16),
            TextField(
              controller: _controller,
              decoration: const InputDecoration(
                labelText: 'xeno-canto API Key',
                prefixIcon: Icon(Icons.key_outlined),
                helperText: '没有 Key 也可用，但请求次数有限制',
                helperMaxLines: 2,
              ),
              obscureText: true,
            ),
            const SizedBox(height: 8),
            Text(
              '前往 xeno-canto.org 注册账户，在个人页面获取免费 API Key，填入后可提升搜索请求额度。',
              style: TextStyle(
                fontSize: 12,
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
              icon: const Icon(Icons.check),
              label: const Text('保存'),
            ),
          ],
        ),
      ),
    );
  }
}

class ActiveAlarm {
  final BirdAlarm alarm;
  final BirdSound sound;

  const ActiveAlarm({required this.alarm, required this.sound});
}

/// 全屏响铃遮罩：铺满整个屏幕（盖住底部导航栏），显示正在叫的鸟 + 关闭 / 贪睡。
/// 由 MainActivity 的 showWhenLocked 让它能显示在锁屏之上。
class AlarmOverlay extends StatelessWidget {
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
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final light = theme.brightness == Brightness.light;
    return Material(
      color: light ? const Color(0xFFFFF5DF) : theme.colorScheme.surface,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(28, 24, 28, 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Spacer(),
              Icon(
                Icons.notifications_active,
                size: 72,
                color: theme.colorScheme.primary,
              ),
              const SizedBox(height: 20),
              Text(
                active.alarm.label,
                textAlign: TextAlign.center,
                style: theme.textTheme.headlineSmall,
              ),
              const SizedBox(height: 18),
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
              const Spacer(),
              FilledButton.icon(
                onPressed: onDismiss,
                icon: const Icon(Icons.alarm_off),
                label: const Text('关闭闹钟'),
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 16),
                ),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: onSnooze,
                icon: const Icon(Icons.snooze),
                label: const Text('贪睡 5 分钟'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
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
    color: light
        ? const Color(0xFFEAF6F2)
        : Theme.of(context).colorScheme.surfaceContainerHighest,
    borderRadius: BorderRadius.circular(8),
    border: Border.all(
      color: light
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
          await prefs.setInt('$_fetchedPrefix$year', now.millisecondsSinceEpoch);
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

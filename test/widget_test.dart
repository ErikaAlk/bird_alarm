// 首页与关键交互的冒烟测试。纯 Dart，不需要 Gradle；改完 Dart 用 `flutter test` 跑一遍。

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:bird_alarm/main.dart';

void main() {
  // 给 SharedPreferences 一份空的假数据，首页的 _load() 才能真正跑完
  // （否则第一步就抛 MissingPluginException，鸟名表根本不会加载）。
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('Bird alarm home renders core controls', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const BirdAlarmApp());
    await tester.pumpAndSettle();

    // 大标题 + 悬浮底栏里的「闹钟」。
    expect(find.text('闹钟'), findsWidgets);
    expect(find.text('下一次唤醒'), findsOneWidget);
    // 新建闹钟改成了导航栏右上角的圆形「+」，只有 tooltip 文案。
    expect(find.byTooltip('新闹钟'), findsOneWidget);
    // 悬浮底栏的其余三个 Tab。
    for (final label in ['鸟鸣', '设置', '关于']) {
      expect(find.text(label), findsWidgets);
    }
  });

  testWidgets('Swiping left moves to the next tab', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const BirdAlarmApp());
    await tester.pumpAndSettle();
    expect(find.text('下一次唤醒'), findsOneWidget);

    // 有悬浮底栏就该能左右滑动翻页，光能点很别扭。
    await tester.drag(find.byType(PageView), const Offset(-500, 0));
    await tester.pumpAndSettle();

    expect(find.text('搜鸟种：中文 / 英文 / 拉丁名'), findsOneWidget);
    expect(find.text('下一次唤醒'), findsNothing);
  });

  testWidgets('Settings tab exposes appearance and ring settings', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const BirdAlarmApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('设置').last);
    await tester.pumpAndSettle();

    expect(find.text('深色模式'), findsOneWidget);
    expect(find.text('闹铃渐响'), findsOneWidget);
    expect(find.text('xeno-canto API Key'), findsOneWidget);
  });

  testWidgets('Permission check opens a self-check panel', (
    WidgetTester tester,
  ) async {
    // 给原生通道一个假实现：自检面板要拿到各项权限状态才会停下转圈。
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('bird_alarm/system_alarm'),
          (call) async =>
              call.method == 'checkAlarmPermissions'
                  ? <String, bool>{
                    'notifications': true,
                    'fullScreenIntent': false,
                    'exactAlarm': true,
                    'battery': true,
                  }
                  : null,
        );
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('bird_alarm/system_alarm'),
            null,
          ),
    );
    await tester.pumpWidget(const BirdAlarmApp());
    await tester.pumpAndSettle();
    await tester.tap(find.text('设置').last);
    await tester.pumpAndSettle();

    // 这一行在设置页下半截，得先滚下去（sliver 不构建视口外的子项）。
    // 拖动锚点认准设置页自己的 CustomScrollView：它不会随滚动消失，
    // 而 byType(Scrollable).last 会命中 API Key 输入框内部那个横向滚动区。
    final settingsView = find.byType(CustomScrollView).last;
    for (var i = 0; i < 8; i++) {
      await tester.drag(settingsView, const Offset(0, -260));
      await tester.pumpAndSettle();
      // 多滚一屏再停：刚露头时它正压在悬浮底栏下面，点下去会打到底栏、切走 Tab。
      if (find.text('打开 xeno-canto 网站').evaluate().isEmpty &&
          find.text('检查闹钟权限').evaluate().isNotEmpty) {
        break;
      }
    }
    // 这个按钮以前「权限都齐了就什么也不做」，看着像坏的；现在必须弹出自检面板。
    await tester.tap(find.text('检查闹钟权限'));
    await tester.pumpAndSettle();

    expect(find.text('权限自检'), findsOneWidget);
    // 缺的那一项要能看出来、并给出「去开启」。
    expect(find.text('有 1 项还没开，点右边去开启。'), findsOneWidget);
    expect(find.text('去开启'), findsOneWidget);
    expect(find.text('打开本应用的系统设置页'), findsOneWidget);
  });

  testWidgets('Weekday cells keep their size; double tap selects every day', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme(Brightness.light),
        home: Scaffold(
          body: AlarmEditor(
            alarm: BirdAlarm(
              id: 'test',
              time: const TimeOfDay(hour: 7, minute: 0),
              repeatDays: const {1},
              repeatRule: RepeatRule.weekdays,
              enabled: true,
              label: '测试',
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    const days = ['一', '二', '三', '四', '五', '六', '日'];
    Finder cell(String label) =>
        find
            .ancestor(
              of: find.text(label),
              matching: find.byType(AnimatedContainer),
            )
            .first;
    Color colorOf(String label) =>
        (tester.widget<AnimatedContainer>(cell(label)).decoration
                as BoxDecoration)
            .color!;

    final selectedColor = colorOf('一');
    final unselectedColor = colorOf('二');
    expect(selectedColor, isNot(unselectedColor));

    // 选中与否只换颜色，不改尺寸——旧的 FilterChip 选中后会多出对勾把后面的格子挤走。
    final sizes = {for (final day in days) day: tester.getSize(cell(day))};
    expect(sizes.values.toSet().length, 1);
    await tester.tap(cell('二'));
    await tester.pumpAndSettle();
    expect(colorOf('二'), selectedColor);
    for (final day in days) {
      expect(tester.getSize(cell(day)), sizes[day]);
    }

    // 双击任意一天 = 一键全选。
    await tester.tap(cell('三'));
    await tester.pump(const Duration(milliseconds: 50));
    await tester.tap(cell('三'));
    await tester.pumpAndSettle();
    for (final day in days) {
      expect(colorOf(day), selectedColor, reason: '双击后 $day 应处于选中态');
    }
  });
}

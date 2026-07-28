// 锁屏响铃遮罩的盲操手势：上滑关闭 / 下滑贪睡，小幅滑动不算数。

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:bird_alarm/main.dart';

void main() {
  const alarm = BirdAlarm(
    id: 'test',
    time: TimeOfDay(hour: 7, minute: 0),
    repeatDays: {1},
    repeatRule: RepeatRule.weekdays,
    enabled: true,
    label: '晨间鸟鸣',
  );
  const sound = BirdSound(
    id: 'starter-x',
    cnName: '四声杜鹃',
    enName: 'Indian Cuckoo',
    sciName: 'Cuculus micropterus',
    source: '内置鸟鸣',
    assetPath: 'sounds/x.m4a',
  );

  Future<({int dismissed, int snoozed})> swipe(
    WidgetTester tester,
    Offset offset,
  ) async {
    var dismissed = 0;
    var snoozed = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme(Brightness.dark),
        home: AlarmOverlay(
          active: const ActiveAlarm(alarm: alarm, sound: sound),
          onDismiss: () => dismissed++,
          onSnooze: () => snoozed++,
        ),
      ),
    );
    await tester.pumpAndSettle();
    // 从屏幕正中起手：盲操时落点不该有讲究。
    await tester.drag(find.text('四声杜鹃'), offset);
    await tester.pumpAndSettle();
    return (dismissed: dismissed, snoozed: snoozed);
  }

  testWidgets('上滑关闭闹钟', (tester) async {
    final result = await swipe(tester, const Offset(0, -200));
    expect(result.dismissed, 1);
    expect(result.snoozed, 0);
  });

  testWidgets('下滑贪睡', (tester) async {
    final result = await swipe(tester, const Offset(0, 200));
    expect(result.snoozed, 1);
    expect(result.dismissed, 0);
  });

  testWidgets('蹭一下（不到阈值）什么也不会发生', (tester) async {
    final up = await swipe(tester, const Offset(0, -60));
    expect(up.dismissed, 0);
    expect(up.snoozed, 0);

    final down = await swipe(tester, const Offset(0, 60));
    expect(down.dismissed, 0);
    expect(down.snoozed, 0);
  });

  testWidgets('按钮仍然可用，并且屏幕上写着手势提示', (tester) async {
    var dismissed = 0;
    var snoozed = 0;
    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme(Brightness.light),
        home: AlarmOverlay(
          active: const ActiveAlarm(alarm: alarm, sound: sound),
          onDismiss: () => dismissed++,
          onSnooze: () => snoozed++,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('上滑关闭闹钟'), findsOneWidget);
    expect(find.text('下滑贪睡 5 分钟'), findsOneWidget);

    await tester.tap(find.text('关闭闹钟'));
    await tester.tap(find.text('贪睡 5 分钟'));
    expect(dismissed, 1);
    expect(snoozed, 1);
  });
}

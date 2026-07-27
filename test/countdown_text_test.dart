// 报时卡上「距离下次响铃还有多久」的文案。纯函数，只在 App 内显示，不进通知。

import 'package:flutter_test/flutter_test.dart';

import 'package:bird_alarm/main.dart';

void main() {
  final now = DateTime(2026, 7, 26, 22, 0);

  test('按剩余时间挑单位', () {
    expect(countdownText(now, now.add(const Duration(seconds: 30))), '不到 1 分钟');
    expect(
      countdownText(now, now.add(const Duration(minutes: 42))),
      '还有 42 分钟',
    );
    expect(
      countdownText(now, now.add(const Duration(hours: 8, minutes: 12))),
      '还有 8 小时 12 分',
    );
    expect(countdownText(now, now.add(const Duration(hours: 9))), '还有 9 小时');
    expect(
      countdownText(now, now.add(const Duration(days: 2, hours: 3))),
      '还有 2 天 3 小时',
    );
    expect(countdownText(now, now.add(const Duration(days: 3))), '还有 3 天');
  });

  test('没有闹钟或时刻已过时不显示', () {
    expect(countdownText(now, null), isNull);
    expect(
      countdownText(now, now.subtract(const Duration(minutes: 1))),
      isNull,
    );
  });
}

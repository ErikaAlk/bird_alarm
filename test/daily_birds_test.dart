// 「每日一鸟」的挑选规则。纯函数，不碰插件与 UI。

import 'package:flutter_test/flutter_test.dart';

import 'package:bird_alarm/main.dart';

List<BirdName> _names(int count) => [
  for (var index = 0; index < count; index++)
    BirdName(
      sci: 'Genus species$index',
      display: '鸟$index',
      cn: '鸟$index',
      en: 'Bird $index',
    ),
];

void main() {
  test('同一天固定同一只，隔天自动换', () {
    final names = _names(500);
    final today = pickDailyBird(names: names, day: DateTime(2026, 7, 26));
    // 同一天的不同时刻也该是同一只（种子只取年月日）。
    final againSameDay = pickDailyBird(
      names: names,
      day: DateTime(2026, 7, 26, 23, 59),
    );
    final tomorrow = pickDailyBird(names: names, day: DateTime(2026, 7, 27));

    expect(today.bird!.sci, againSameDay.bird!.sci);
    expect(today.bird!.sci, isNot(tomorrow.bird!.sci));
    expect(today.day, DateTime(2026, 7, 26));
  });

  test('鸟名表还没加载好时返回 day=null（调用方据此不缓存）', () {
    final picked = pickDailyBird(names: const [], day: DateTime(2026, 7, 26));

    expect(picked.day, isNull);
    expect(picked.bird, isNull);
  });

  test('没有中文名的条目不进候选', () {
    final picked = pickDailyBird(
      names: const [
        BirdName(sci: 'Genus a', display: 'A', cn: '', en: 'A'),
        BirdName(sci: 'Genus b', display: '乙', cn: '乙', en: 'B'),
      ],
      day: DateTime(2026, 7, 26),
    );

    expect(picked.bird!.sci, 'Genus b');
  });
}

// 「每日一鸟 / 今日推荐」的挑选规则。纯函数，不碰插件与 UI。

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
  test('同一天结果固定，隔天自动换一批', () {
    final names = _names(500);
    final today = pickDailyBirds(
      names: names,
      downloadedSci: const {},
      day: DateTime(2026, 7, 26),
    );
    final againSameDay = pickDailyBirds(
      names: names,
      downloadedSci: const {},
      // 同一天的不同时刻也该是同一批（种子只取年月日）。
      day: DateTime(2026, 7, 26, 23, 59),
    );
    final tomorrow = pickDailyBirds(
      names: names,
      downloadedSci: const {},
      day: DateTime(2026, 7, 27),
    );

    expect(today.star!.sci, againSameDay.star!.sci);
    expect(
      today.recommendations.map((bird) => bird.sci),
      againSameDay.recommendations.map((bird) => bird.sci),
    );
    expect(today.star!.sci, isNot(tomorrow.star!.sci));
    expect(today.day, DateTime(2026, 7, 26));
  });

  test('推荐都是未下载的鸟，彼此不重复、也不与每日一鸟重复', () {
    final names = _names(60);
    final downloaded = {
      for (var index = 0; index < 30; index++) 'Genus species$index',
    };
    final picks = pickDailyBirds(
      names: names,
      downloadedSci: downloaded,
      day: DateTime(2026, 7, 26),
    );

    expect(picks.recommendations, hasLength(6));
    for (final bird in picks.recommendations) {
      expect(downloaded.contains(bird.sci), isFalse);
      expect(bird.sci, isNot(picks.star!.sci));
    }
    expect(
      picks.recommendations.map((bird) => bird.sci).toSet(),
      hasLength(picks.recommendations.length),
    );
  });

  test('鸟名表还没加载好时返回 day=null（调用方据此不缓存）', () {
    final picks = pickDailyBirds(
      names: const [],
      downloadedSci: const {},
      day: DateTime(2026, 7, 26),
    );

    expect(picks.day, isNull);
    expect(picks.star, isNull);
    expect(picks.recommendations, isEmpty);
  });

  test('没有中文名的条目不进候选', () {
    final picks = pickDailyBirds(
      names: const [
        BirdName(sci: 'Genus a', display: 'A', cn: '', en: 'A'),
        BirdName(sci: 'Genus b', display: '乙', cn: '乙', en: 'B'),
      ],
      downloadedSci: const {},
      day: DateTime(2026, 7, 26),
    );

    expect(picks.star!.sci, 'Genus b');
  });
}

import 'package:bird_alarm/main.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

int _ms(int month, int day, int hour, int minute) =>
    DateTime(2026, month, day, hour, minute).millisecondsSinceEpoch;

void main() {
  // 2026-09-28 周一、09-29 周二、09-30 周三是普通工作日；10-03 是国庆假期。
  final monday = DateTime(2026, 9, 28);

  tearDown(() => CadenceSchedule.update(monday, 0, null));

  test('第一节 9 点前是早八，9 点整不是', () {
    CadenceSchedule.update(monday, 3, [
      _ms(9, 28, 10, 20),
      _ms(9, 28, 8, 30),
      _ms(9, 29, 9, 0),
    ]);
    expect(scheduleDayOf(DateTime(2026, 9, 28)), ScheduleDay.earlyClass);
    expect(scheduleDayOf(DateTime(2026, 9, 29)), ScheduleDay.workday);
    // 读到了、那天没课的工作日：无早八
    expect(scheduleDayOf(DateTime(2026, 9, 30)), ScheduleDay.workday);
  });

  test('读不到课表的工作日按早八算，休息日还是节假日', () {
    CadenceSchedule.update(monday, 3, null);
    expect(scheduleDayOf(DateTime(2026, 9, 29)), ScheduleDay.earlyClass);
    expect(scheduleDayOf(DateTime(2026, 10, 3)), ScheduleDay.holiday);
    // 超出读取范围的工作日同样按早八
    CadenceSchedule.update(monday, 1, []);
    expect(scheduleDayOf(DateTime(2026, 9, 28)), ScheduleDay.workday);
    expect(scheduleDayOf(DateTime(2026, 9, 29)), ScheduleDay.earlyClass);
  });

  test('课表没变时不报变化', () {
    final starts = [_ms(9, 28, 8, 30)];
    CadenceSchedule.update(monday, 2, starts);
    expect(CadenceSchedule.update(monday, 2, starts), isFalse);
    expect(CadenceSchedule.update(monday, 2, []), isTrue);
  });

  test('「课表」闹钟一个管两种工作日，休息日不响', () {
    const alarm = BirdAlarm(
      id: 'c',
      time: TimeOfDay(hour: 7, minute: 0),
      repeatDays: {},
      repeatRule: RepeatRule.classSchedule,
      enabled: true,
      label: '',
      noEarlyTime: TimeOfDay(hour: 8, minute: 30),
    );
    CadenceSchedule.update(monday, 2, [_ms(9, 28, 8, 30), _ms(9, 29, 10, 20)]);
    expect(
      alarm.timeOn(DateTime(2026, 9, 28)),
      const TimeOfDay(hour: 7, minute: 0),
    );
    expect(
      alarm.timeOn(DateTime(2026, 9, 29)),
      const TimeOfDay(hour: 8, minute: 30),
    );
    // 读取范围外的工作日：按早八
    expect(
      alarm.timeOn(DateTime(2026, 9, 30)),
      const TimeOfDay(hour: 7, minute: 0),
    );
    expect(alarm.timeOn(DateTime(2026, 10, 3)), isNull);
  });

  test('光闹钟时刻 = 响铃减提前量，去重升序', () {
    BirdAlarm alarm(int lead) => BirdAlarm(
      id: '$lead',
      time: const TimeOfDay(hour: 7, minute: 0),
      repeatDays: const {},
      repeatRule: RepeatRule.classSchedule,
      enabled: true,
      label: '',
      lightLeadMinutes: lead,
    );
    final at = DateTime(2026, 9, 29, 7);
    expect(
      lightStartTimes([
        (at.add(const Duration(days: 1)), alarm(0)),
        (at, alarm(10)),
        (at.add(const Duration(minutes: 5)), alarm(15)),
      ]),
      [DateTime(2026, 9, 29, 6, 50), DateTime(2026, 9, 30, 7)],
    );
  });
}

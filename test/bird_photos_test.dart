// 每日一鸟照片的查找与缓存。重点守住一条：**查询失败不能被当成「这只鸟没有照片」**
// 记进缓存——之前就是这么写的，结果第一次没网之后照片永远出不来。

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:bird_alarm/main.dart';

http.Response _json(Object body) => http.Response(
  jsonEncode(body),
  200,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

Map<String, dynamic> _inatWith({String? license, String? user}) => {
  'results': [
    {
      'user': {'name': user, 'login': 'login_name'},
      'photos': [
        {
          'url': 'https://static.inaturalist.org/photos/1/square.jpg',
          'license_code': license,
        },
      ],
    },
  ],
};

Map<String, dynamic> get _commonsPhoto => {
  'query': {
    'pages': {
      '1': {
        'imageinfo': [
          {
            'mime': 'image/jpeg',
            'thumburl': 'https://upload.wikimedia.org/thumb/640px-bird.jpg',
            'extmetadata': {
              'Artist': {
                'value': '<a href="/wiki/User:Someone">Rejoice Gassah</a>',
              },
              'LicenseShortName': {'value': 'CC BY 4.0'},
            },
          },
        ],
      },
    },
  },
};

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    BirdPhotos.debugClearMemoryCache();
  });

  test('iNaturalist 有 CC 图时用它，并带上作者与许可证署名', () async {
    final client = MockClient(
      (request) async =>
          _json(_inatWith(license: 'cc-by-nc', user: 'Ujjal Kishor De')),
    );

    final result = await BirdPhotos.forSpecies(
      'Cuculus micropterus',
      client: client,
    );

    expect(result.status, BirdPhotoStatus.found);
    // square 缩略图要换成 medium，不然只有 75px。
    expect(result.photo!.url, endsWith('/medium.jpg'));
    expect(
      result.photo!.attribution,
      'iNaturalist · Ujjal Kishor De · CC-BY-NC',
    );
  });

  test('没有许可证的照片（保留所有权利）不能用，退到 Commons', () async {
    final client = MockClient((request) async {
      if (request.url.host.contains('inaturalist')) {
        return _json(_inatWith(license: null, user: 'someone'));
      }
      return _json(_commonsPhoto);
    });

    final result = await BirdPhotos.forSpecies(
      'Cuculus micropterus',
      client: client,
    );

    expect(result.status, BirdPhotoStatus.found);
    expect(result.photo!.url, contains('upload.wikimedia.org'));
    // Artist 字段是 HTML，要扒成纯文本。
    expect(
      result.photo!.attribution,
      'Wikimedia Commons · Rejoice Gassah · CC BY 4.0',
    );
  });

  test('两边都正常应答但确实没图 → 记「没有」，不再重复请求', () async {
    var calls = 0;
    final client = MockClient((request) async {
      calls++;
      return request.url.host.contains('inaturalist')
          ? _json({'results': <dynamic>[]})
          : _json({'query': <String, dynamic>{}});
    });

    final first = await BirdPhotos.forSpecies(
      'Genus nonexistent',
      client: client,
    );
    expect(first.status, BirdPhotoStatus.none);
    expect(calls, 2);

    BirdPhotos.debugClearMemoryCache();
    final second = await BirdPhotos.forSpecies(
      'Genus nonexistent',
      client: client,
    );
    expect(second.status, BirdPhotoStatus.none);
    expect(calls, 2, reason: '「确实没有」应命中缓存，不该再发请求');
  });

  test('查询失败不写缓存，网络恢复后能查到', () async {
    final failing = MockClient((request) async => throw const SocketFailure());
    final failed = await BirdPhotos.forSpecies(
      'Cuculus micropterus',
      client: failing,
    );
    expect(failed.status, BirdPhotoStatus.failed);
    expect(failed.photo, isNull);

    // 关键：上一次失败**不能**被当成「这只鸟没有照片」缓存下来，
    // 否则网络恢复了照片也永远出不来。
    BirdPhotos.debugClearMemoryCache();
    final working = MockClient(
      (request) async => _json(_inatWith(license: 'cc0', user: 'someone')),
    );
    final retry = await BirdPhotos.forSpecies(
      'Cuculus micropterus',
      client: working,
    );
    expect(retry.status, BirdPhotoStatus.found);
    expect(retry.photo!.attribution, 'iNaturalist · someone · CC0');
  });
}

/// MockClient 里用来模拟断网的异常。
class SocketFailure implements Exception {
  const SocketFailure();
}
